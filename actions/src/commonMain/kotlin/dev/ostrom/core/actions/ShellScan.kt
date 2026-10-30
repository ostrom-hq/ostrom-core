// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

/**
 * The built-in detection over the segments of a shell command: which frontier classes they contain, their `git push`
 * invocations and the pull request a merge names. A tool is looked for at every word of a segment the shell runs
 * (so `sudo kubectl apply`, `xargs kubectl delete` are seen), and only where a command may stand in a quoted word,
 * which may be plain text (`git commit -m "fix kubectl apply"` is not a deploy).
 */
@Suppress("TooManyFunctions") // One reader per tool family.
internal class ShellScan(private val protectedBranches: Set<String>) {
    val found = HashSet<String>()
    val pushes = ArrayList<PushInvocation>()
    var redirectAssignment = false
        private set

    /** More than [MAX_PUSHES] pushes in one command: not read one by one, refused. */
    var tooManyPushes = false
        private set
    /** A command at a command position whose name is built at run time (`$GIT push`): what runs is unknown. */
    var dynamicCommand = false
        private set

    /** `git <verb>` where the verb is no git command: it may be an alias of the git configuration. */
    val aliases = ArrayList<GitAliasCandidate>()

    /** Merges of different pull requests (or of unnamed ones) in the same reading of the command. */
    var severalMerges = false
        private set
    private val merges = HashSet<String>()

    /** A `gh pr merge` whose arguments run past [MAX_SCAN] words: which pull request it merges is not read. */
    private var mergeUnread = false
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
    @Suppress("CyclomaticComplexMethod") // One check per kind of segment, in one pass.
    fun scan(script: ShellScript, quoteBlind: Boolean) {
        cdDirectories.clear()
        onlyCdAndPushes = true
        merges.clear()
        // The parser puts the commands of the script itself first, in order: what came before them is known here.
        for (segment in script.segments) {
            val words = segment.words
            if (words.any(::redirectingAssignment)) redirectAssignment = true
            val top = segment.depth == 0
            segmentPushes = false
            apiFrom = words.indexOfFirst(::githubApiWord)
            val positions = commandPositions(words)
            if (segment.origin != SegmentOrigin.QUOTED && segment.commandLine) {
                if (positions.any { it < words.size && dynamicCommand(words[it]) }) dynamicCommand = true
            }
            val candidates = if (segment.origin == SegmentOrigin.QUOTED) positions else words.indices
            for (i in candidates) {
                if (i < words.size) tool(segment, i, top, script.subshells, quoteBlind)
            }
            // The whole call is read: a GraphQL body may come before the URL (`curl -d '…mergePullRequest…' URL`).
            if (apiFrom >= 0) githubApi(words)
            if (top) {
                val cd = isCd(words)
                if (cd) cdDirectories += words.getOrElse(1) { "~" }
                onlyCdAndPushes = onlyCdAndPushes && (cd || segmentPushes)
            }
        }
        severalMerges = severalMerges || merges.size > 1 || mergeUnread
    }

    @Suppress("CyclomaticComplexMethod") // One branch per tool family.
    private fun tool(segment: ShellSegment, i: Int, top: Boolean, subshells: Boolean, quoteBlind: Boolean) {
        val w = segment.words
        when (toolName(w[i])) {
            "git" -> git(segment, i, top, subshells, quoteBlind)
            "gh" -> gh(w, i)
            "helm" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in HELM_VERBS } >= 0)
            "kubectl" -> kubectl(w, i)
            "terraform", "tofu" ->
                add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in TERRAFORM_VERBS } >= 0)
            "pulumi" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in PULUMI_VERBS } >= 0)
            "helmfile" -> add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in HELMFILE_VERBS } >= 0)
            "argocd" -> verbThen(w, i, "app", ARGOCD_VERBS, BuiltInActions.DEPLOY)
            "kamal", "firebase", "wrangler", "serverless", "sls" ->
                add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in PUBLISH_VERBS } >= 0)
            "netlify" -> add(
                BuiltInActions.DEPLOY,
                findVerb(w, i + 1) { same(it, "deploy") } >= 0 && anyAfter(w, i) { same(it, "--prod") },
            )
            "gcloud", "cap" -> add(BuiltInActions.DEPLOY, anyAfter(w, i) { same(it, "deploy") })
            "gradle", "gradlew", "mvn", "mvnw" -> add(
                BuiltInActions.MIGRATION,
                anyAfter(w, i) { it.contains("flywayMigrate", ignoreCase = true) || same(it, "flyway:migrate") },
            )
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

    /** `tool <group> <verb>` (`argocd app sync`). */
    private fun verbThen(w: List<String>, i: Int, group: String, verbs: Set<String>, action: String) {
        val at = findVerb(w, i + 1) { same(it, group) }
        add(action, at >= 0 && findVerb(w, at + 1) { it.lowercase() in verbs } >= 0)
    }

    private fun startsWith(word: String?, prefix: String): Boolean =
        word.orEmpty().startsWith(prefix, ignoreCase = true)

    // One branch per git verb that reaches a boundary; guard clauses that refuse early read better than one nested
    // condition.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun git(segment: ShellSegment, i: Int, top: Boolean, subshells: Boolean, quoteBlind: Boolean) {
        val w = segment.words
        val invocation = gitInvocation(w, i) ?: return
        val verb = w[invocation.verbIndex].lowercase()
        // Built only for the few invocations kept: a long run of `cd` must not be copied at every git word.
        val directories by lazy { (if (top) cdDirectories.toList() else emptyList()) + invocation.directories }
        // A verb built at run time, a plumbing push or `git subtree push`: a push Ostrom cannot read.
        // An unknown verb with a redirecting option may be an alias the option itself defines (`-c alias.p=push p`).
        val unreadable = dynamicCommand(verb) || verb in PUSH_PLUMBING ||
            (verb == "subtree" && findVerb(w, invocation.verbIndex + 1) { same(it, "push") } >= 0) ||
            (invocation.redirect != null && verb !in GIT_COMMANDS)
        if (!unreadable && verb != "push") {
            if (verb !in GIT_COMMANDS && aliases.size < MAX_PUSHES) aliases += GitAliasCandidate(verb, directories)
            return
        }
        segmentPushes = true
        if (pushes.size >= MAX_PUSHES) {
            tooManyPushes = true
            return
        }
        // A command of the script itself (depth 0) is never quoted text: its origin is the script.
        val plain = !unreadable && !quoteBlind && top && atCommandPosition(w, i) &&
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
            val target = mergeArguments(w, i, pr, merge)
            merges += if (target == null) "the current branch" else target.toString()
            // The first `gh pr merge` of the command names the pull request, as the prototype's `prOfCommand` read it.
            if (!mergeRead) mergePullRequest = target
            mergeRead = true
        }
        val release = findVerb(w, i + 1) { same(it, "release") }
        if (release >= 0 && findVerb(w, release + 1) { same(it, "create") } >= 0) found += BuiltInActions.PUSH_TAG
        val workflow = findVerb(w, i + 1) { same(it, "workflow") }
        val run = if (workflow < 0) -1 else findVerb(w, workflow + 1) { same(it, "run") }
        add(BuiltInActions.DEPLOY, run >= 0 && findVerb(w, run + 1) { it.contains("deploy", ignoreCase = true) } >= 0)
        if (findVerb(w, i + 1) { same(it, "api") } >= 0) apiFrom = i
    }

    private fun kubectl(w: List<String>, i: Int) {
        add(BuiltInActions.DEPLOY, findVerb(w, i + 1) { it.lowercase() in KUBECTL_VERBS } >= 0)
    }

    private fun packagePublish(w: List<String>, i: Int) {
        val npm = findVerb(w, i + 1) { same(it, "npm") }
        if (findVerb(w, i + 1) { same(it, "publish") } >= 0 || (npm >= 0 && same(w.getOrNull(npm + 1), "publish"))) {
            found += BuiltInActions.DEPLOY
        }
    }

    private fun imagePush(w: List<String>, i: Int) {
        val group = findVerb(w, i + 1) { it.lowercase() in IMAGE_GROUPS }
        val push = findVerb(w, i + 1) { same(it, "push") } >= 0 ||
            (group >= 0 && same(w.getOrNull(group + 1), "push"))
        // `docker buildx build --push` builds and pushes in one go.
        val buildx = findVerb(w, i + 1) { same(it, "buildx") } >= 0 && anyAfter(w, i) { same(it, "--push") }
        add(BuiltInActions.DEPLOY, push || buildx)
    }

    /**
     * A call to the GitHub API (`gh api`, a URL of api.github.com or of a GitHub Enterprise `/api/v3/`): merges, ref
     * updates and file writes on main.
     */
    private fun githubApi(w: List<String>) {
        val writes by lazy { writesWithMethod(w) }
        for (word in w) {
            if (mergesThroughApi(word)) {
                found += BuiltInActions.MERGE
                val target = apiPullRequest(word)
                merges += if (target == null) "api: $word" else target.toString()
                apiPull = apiPull ?: target
            }
            val ref = refAfter(word, "git/refs/heads/")
            add(BuiltInActions.PUSH_MAIN, ref != null && protectedBranches.any { it.equals(ref, ignoreCase = true) })
            // The contents API writes a commit on the default branch unless told otherwise; these mutations write a
            // commit or move a ref on a branch the text may not name.
            if (word.contains("/contents/", ignoreCase = true) && writes) found += BuiltInActions.PUSH_MAIN
            add(BuiltInActions.PUSH_MAIN, GRAPHQL_WRITES.any { containsWord(word, it) })
        }
    }

    /** The pull request of `gh pr merge` arguments: a URL, else a number (and `-R owner/repo`). */
    // One branch per option of gh pr merge; guard clauses that refuse early read better than one nested condition.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun mergeArguments(w: List<String>, gh: Int, pr: Int, merge: Int): PullRequestRef? {
        var url: PullRequestRef? = null
        var repo: Pair<String, String>? = null
        var number: Int? = null
        var j = gh + 1
        while (j < w.size) {
            // Each merge reads its own arguments only: past the bound, the pull request is unknown and refused.
            if (j - merge > MAX_SCAN) {
                mergeUnread = true
                return null
            }
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
}

/** A word of `gh api` that merges: a pull request, a branch, an upstream, by REST or GraphQL. */
private fun mergesThroughApi(word: String): Boolean = hasPullsMerge(word) || hasMerges(word) ||
    GRAPHQL_MERGES.any { containsWord(word, it) } || word.contains("/merge-upstream", ignoreCase = true)

/** Pushes read one by one in a command; more are refused. */
private const val MAX_PUSHES = 16
private val HELM_VERBS = setOf("upgrade", "install", "rollback", "uninstall")
private val TERRAFORM_VERBS = setOf("apply", "destroy")
private val PULUMI_VERBS = setOf("up", "update", "destroy")
private val HELMFILE_VERBS = setOf("apply", "sync", "destroy", "delete")
private val ARGOCD_VERBS = setOf("sync", "rollback", "delete", "set")
private val PUBLISH_VERBS = setOf("deploy", "publish", "redeploy", "rollback", "remove")
private val KUBECTL_VERBS = setOf(
    "apply", "rollout", "delete", "scale", "patch", "replace", "edit", "set", "create", "run", "expose",
    "autoscale", "label", "annotate", "taint", "drain", "cordon",
)
private val IMAGE_GROUPS = setOf("image", "compose", "manifest")

/** git commands that push without being `git push`. */
private val PUSH_PLUMBING = setOf("send-pack", "http-push")

/** GraphQL mutations that merge, and those that write on a branch. */
private val GRAPHQL_MERGES = listOf("mergePullRequest", "enablePullRequestAutoMerge", "mergeBranch")
private val GRAPHQL_WRITES = listOf("createCommitOnBranch", "updateRef", "updateRefs")

/**
 * The commands of git (porcelain and plumbing, `git help -a`): an alias cannot take their name, so only another
 * verb may be an alias that pushes.
 */
private val GIT_COMMANDS = setOf(
    "add", "am", "annotate", "apply", "archive", "bisect", "blame", "branch", "bugreport", "bundle", "cat-file",
    "check-attr", "check-ignore", "check-mailmap", "check-ref-format", "checkout", "checkout-index", "cherry",
    "cherry-pick", "citool", "clean", "clone", "column", "commit", "commit-graph", "commit-tree", "config",
    "count-objects", "credential", "describe", "diagnose", "diff", "diff-files", "diff-index", "diff-tree",
    "difftool", "fast-export", "fast-import", "fetch", "fetch-pack", "filter-branch", "fmt-merge-msg",
    "for-each-ref", "for-each-repo", "format-patch", "fsck", "gc", "get-tar-commit-id", "grep", "gui",
    "hash-object", "help", "hook", "index-pack", "init", "instaweb", "interpret-trailers", "log", "ls-files",
    "ls-remote", "ls-tree", "mailinfo", "mailsplit", "maintenance", "merge", "merge-base", "merge-file",
    "merge-index", "merge-tree", "mergetool", "mktag", "mktree", "multi-pack-index", "mv", "name-rev", "notes",
    "pack-objects", "pack-refs", "patch-id", "prune", "prune-packed", "pull", "range-diff", "read-tree",
    "rebase", "reflog", "remote", "repack", "replace", "replay", "request-pull", "rerere", "reset", "restore",
    "rev-list", "rev-parse", "revert", "rm", "send-email", "shortlog", "show", "show-branch", "show-index",
    "show-ref", "sparse-checkout", "stash", "status", "stripspace", "submodule", "switch", "symbolic-ref",
    "tag", "unpack-file", "unpack-objects", "update-index", "update-ref", "update-server-info", "var",
    "verify-commit", "verify-pack", "verify-tag", "version", "whatchanged", "worktree", "write-tree", "lfs",
)
private val PRODUCTION = setOf("prod", "production")
private val EAS_VERBS = setOf("submit", "build")

/** Options of `gh pr merge` that take a value: the value is never the pull request number. */
private val GH_MERGE_VALUE_OPTIONS = listOf(
    "-R", "--repo", "-b", "--body", "-F", "--body-file", "-t", "--subject", "-A", "--author-email",
    "--match-head-commit",
)
