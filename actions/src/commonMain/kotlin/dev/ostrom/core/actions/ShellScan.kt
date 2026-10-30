package dev.ostrom.core.actions

/**
 * The built-in detection over the segments of a shell command: which frontier classes they contain, their `git push`
 * invocations and the pull request a merge names. A tool is looked for at every word of a segment the shell runs
 * (so `sudo kubectl apply`, `xargs kubectl delete` are seen), and only where a command may stand in a quoted word,
 * which may be plain text (`git commit -m "fix kubectl apply"` is not a deploy).
 */
internal class ShellScan(private val protectedBranches: Set<String>) {
    val found = HashSet<String>()
    val pushes = ArrayList<PushInvocation>()
    var redirectAssignment = false
        private set

    /** More than [MAX_PUSHES] pushes in one command: not read one by one, refused. */
    var tooManyPushes = false
        private set
    private var apiPull: PullRequestRef? = null
    private var mergePullRequest: PullRequestRef? = null
    private var mergeRead = false

    /** The pull request a merge command targets: the GitHub API path first, then the first `gh pr merge`. */
    val pullRequest: PullRequestRef? get() = apiPull ?: mergePullRequest

    /** What the commands before the current one did: `cd` directories, and whether only `cd` and pushes ran. */
    private val cdDirectories = ArrayList<String>()
    private var onlyCdAndPushes = true
    private var segmentPushes = false

    /** Where the GitHub API words of the current segment start, -1 when it has none. */
    private var apiFrom = -1

    /** Scans a parsed command; [quoteBlind] for the fallback parse of a malformed one (no push there is plain). */
    fun scan(script: ShellScript, quoteBlind: Boolean) {
        cdDirectories.clear()
        onlyCdAndPushes = true
        // The parser puts the commands of the script itself first, in order: what came before them is known here.
        for (segment in script.segments) {
            val words = segment.words
            if (words.any(::redirectingAssignment)) redirectAssignment = true
            val top = segment.depth == 0
            segmentPushes = false
            apiFrom = words.indexOfFirst { it.contains("api.github.com/", ignoreCase = true) }
            val candidates = if (segment.origin == SegmentOrigin.QUOTED) commandPositions(words) else words.indices
            for (i in candidates) {
                if (i < words.size) tool(segment, i, top, script.subshells, quoteBlind)
            }
            if (apiFrom >= 0) githubApi(words, apiFrom)
            if (top) {
                val cd = isCd(words)
                if (cd) cdDirectories += words.getOrElse(1) { "~" }
                onlyCdAndPushes = onlyCdAndPushes && (cd || segmentPushes)
            }
        }
    }

    private fun tool(segment: ShellSegment, i: Int, top: Boolean, subshells: Boolean, quoteBlind: Boolean) {
        val w = segment.words
        when (toolName(w[i])) {
            "git" -> git(segment, i, top, subshells, quoteBlind)
            "gh" -> gh(w, i)
            "helm" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in HELM_VERBS } >= 0)
            "kubectl" -> kubectl(w, i)
            "terraform" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in TERRAFORM_VERBS } >= 0)
            "fly", "flyctl" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { same(it, "deploy") } >= 0)
            "vercel" -> add(BuiltInActions.DEPLOY, anyAfter(w, i) { it.startsWith("--prod", ignoreCase = true) })
            "npm", "pnpm", "yarn" -> packagePublish(w, i)
            "docker", "podman" -> imagePush(w, i)
            "deploy", "deploy.sh" -> add(BuiltInActions.DEPLOY, w.getOrNull(i + 1)?.lowercase() in PRODUCTION)
            "flyway" -> add(BuiltInActions.MIGRATION, anyAfter(w, i) { containsWord(it, "migrate") })
            "prisma" -> add(
                BuiltInActions.MIGRATION,
                same(w.getOrNull(i + 1), "migrate") && same(w.getOrNull(i + 2), "deploy"),
            )
            "alembic" -> add(BuiltInActions.MIGRATION, same(w.getOrNull(i + 1), "upgrade"))
            "liquibase" -> add(BuiltInActions.MIGRATION, anyAfter(w, i) { containsWord(it, "update") })
            "rails", "rake" -> add(BuiltInActions.MIGRATION, startsWith(w.getOrNull(i + 1), "db:migrate"))
            "knex" -> add(BuiltInActions.MIGRATION, startsWith(w.getOrNull(i + 1), "migrate"))
            "fastlane" -> found += BuiltInActions.MOBILE_RELEASE
            "eas" -> add(
                BuiltInActions.MOBILE_RELEASE,
                w.getOrNull(i + 1)?.lowercase() in EAS_VERBS &&
                    anyAfter(w, i + 1) { it.contains("production", ignoreCase = true) },
            )
        }
    }

    private fun add(action: String, condition: Boolean) {
        if (condition) found += action
    }

    private fun startsWith(word: String?, prefix: String): Boolean =
        word.orEmpty().startsWith(prefix, ignoreCase = true)
    }

    private fun git(segment: ShellSegment, i: Int, top: Boolean, subshells: Boolean, quoteBlind: Boolean) {
        val w = segment.words
        val invocation = gitInvocation(w, i) ?: return
        if (!same(w[invocation.verbIndex], "push")) return
        segmentPushes = true
        if (pushes.size >= MAX_PUSHES) {
            tooManyPushes = true
            return
        }
        val directories = (if (top) cdDirectories.toList() else emptyList()) + invocation.directories
        // A command of the script itself (depth 0) is never quoted text: its origin is the script.
        val plain = !quoteBlind && top && atCommandPosition(w, i) &&
            !(subshells && cdDirectories.isNotEmpty()) && directories.none { dynamic(it) || it == "-" }
        pushes += PushInvocation(
            directories, pushArguments(w.subList(invocation.verbIndex + 1, w.size)), plain, invocation.redirect != null,
            standalone = top && onlyCdAndPushes,
        )
    }

    private fun isCd(words: List<String>): Boolean = words[0] == "cd" || words[0] == "pushd"

    private fun gh(w: List<String>, i: Int) {
        val pr = findVerb(w, i + 1) { same(it, "pr") }
        val merge = if (pr < 0) -1 else findVerb(w, pr + 1) { same(it, "merge") }
        if (merge >= 0) {
            found += BuiltInActions.MERGE
            // The first `gh pr merge` of the command names the pull request, as the prototype's `prOfCommand` read it.
            if (!mergeRead) mergePullRequest = mergeArguments(w, i, pr, merge)
            mergeRead = true
        }
        val release = findVerb(w, i + 1) { same(it, "release") }
        if (release >= 0 && findVerb(w, release + 1) { same(it, "create") } >= 0) found += BuiltInActions.PUSH_TAG
        val workflow = findVerb(w, i + 1) { same(it, "workflow") }
        val run = if (workflow < 0) -1 else findVerb(w, workflow + 1) { same(it, "run") }
        add(BuiltInActions.DEPLOY, run >= 0 && findVerb(w, run + 1) { it.contains("deploy", ignoreCase = true) } >= 0)
        val api = findVerb(w, i + 1) { same(it, "api") }
        if (api >= 0 && (apiFrom < 0 || api + 1 < apiFrom)) apiFrom = api + 1
    }

    private fun kubectl(w: List<String>, i: Int) {
        val set = findVerb(w, i + 1) { same(it, "set") }
        val verb = findVerb(w, i + 1) { it.lowercase() in KUBECTL_VERBS } >= 0
        add(BuiltInActions.DEPLOY, verb || (set >= 0 && same(w.getOrNull(set + 1), "image")))
    }

    private fun packagePublish(w: List<String>, i: Int) {
        val npm = findVerb(w, i + 1) { same(it, "npm") }
        if (findVerb(w, i + 1) { same(it, "publish") } >= 0 || (npm >= 0 && same(w.getOrNull(npm + 1), "publish"))) {
            found += BuiltInActions.DEPLOY
        }
    }

    private fun imagePush(w: List<String>, i: Int) {
        val image = findVerb(w, i + 1) { same(it, "image") }
        if (findVerb(w, i + 1) { same(it, "push") } >= 0 || (image >= 0 && same(w.getOrNull(image + 1), "push"))) {
            found += BuiltInActions.DEPLOY
        }
    }

    /** A call to the GitHub API (`gh api`, a URL on api.github.com): merges, ref updates and file writes on main. */
    private fun githubApi(w: List<String>, from: Int) {
        val writes by lazy { writesWithMethod(w) }
        for (j in from until w.size) {
            val word = w[j]
            add(BuiltInActions.MERGE, hasPullsMerge(word) || hasMerges(word) || containsWord(word, "mergePullRequest"))
            apiPull = apiPull ?: apiPullRequest(word)
            val ref = refAfter(word, "git/refs/heads/")
            add(BuiltInActions.PUSH_MAIN, ref != null && protectedBranches.any { it.equals(ref, ignoreCase = true) })
            // The contents API writes a commit on the default branch unless told otherwise.
            if (word.contains("/contents/", ignoreCase = true) && writes) found += BuiltInActions.PUSH_MAIN
        }
    }

    /** The pull request of `gh pr merge` arguments: a URL, else a number (and `-R owner/repo`). */
    private fun mergeArguments(w: List<String>, gh: Int, pr: Int, merge: Int): PullRequestRef? {
        var url: PullRequestRef? = null
        var repo: Pair<String, String>? = null
        var number: Int? = null
        var j = gh + 1
        while (j < w.size) {
            val word = w[j]
            val option = GH_MERGE_VALUE_OPTIONS.firstOrNull { word == it || word.startsWith("$it=") }
            when {
                j == pr || j == merge -> Unit
                option != null -> {
                    val value = if (word == option) w.getOrNull(++j) else word.substringAfter('=')
                    if (option == "-R" || option == "--repo") repo = repo ?: value?.let(::repoSpec)
                }
                word.startsWith("-R") -> repo = repo ?: repoSpec(word.substring(2))
                else -> {
                    url = url ?: pullRequestUrl(word)
                    val digits = word.removePrefix("#")
                    number = number ?: digits.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                }
            }
            j++
        }
        if (url != null) return url
        return number?.let { PullRequestRef(it, repo?.first, repo?.second) }
    }

    private companion object {
        /** Pushes read one by one in a command; more are refused. */
        const val MAX_PUSHES = 16
        val HELM_VERBS = setOf("upgrade", "install", "rollback", "uninstall")
        val TERRAFORM_VERBS = setOf("apply", "destroy")
        val KUBECTL_VERBS = setOf("apply", "rollout", "delete", "scale", "patch", "replace", "edit")
        val PRODUCTION = setOf("prod", "production")
        val EAS_VERBS = setOf("submit", "build")

        /** Options of `gh pr merge` that take a value: the value is never the pull request number. */
        val GH_MERGE_VALUE_OPTIONS = listOf(
            "-R", "--repo", "-b", "--body", "-F", "--body-file", "-t", "--subject", "-A", "--author-email",
            "--match-head-commit",
        )
    }
}
