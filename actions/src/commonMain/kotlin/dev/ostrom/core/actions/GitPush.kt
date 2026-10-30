package dev.ostrom.core.actions

/**
 * A `git` word of a segment and what follows it: global options, then the verb.
 *
 * @property directories The `-C` values, in order.
 * @property redirect The first global option that makes git read or send elsewhere (`--git-dir`,
 *   `-c remote.x.url=…`).
 */
internal class GitInvocation(
    val verbIndex: Int,
    val directories: List<String>,
    val redirect: String?,
)

/** Reads the git invocation whose `git` word is at [gitIndex]; null when no verb follows. */
internal fun gitInvocation(words: List<String>, gitIndex: Int): GitInvocation? {
    val directories = ArrayList<String>()
    var redirect: String? = null
    var j = gitIndex + 1
    while (j < words.size && words[j].startsWith("-")) {
        val option = words[j]
        val value = words.getOrNull(j + 1)
        when {
            option == "-C" -> directories.add(value ?: return null)
            option == "-c" -> if (redirectingConfig(value ?: return null)) redirect = redirect ?: "-c $value"
            option in GIT_VALUE_OPTIONS -> {
                if (value == null) return null
                if (option != "--super-prefix") redirect = redirect ?: option
            }
            REDIRECT_OPTIONS.any { option.startsWith("$it=") } -> redirect = redirect ?: option
            else -> {
                j++
                continue
            }
        }
        j += 2
    }
    return if (j < words.size) GitInvocation(j, directories, redirect) else null
}

/** Global options of git that take their value as the next word. */
private val GIT_VALUE_OPTIONS = setOf("--git-dir", "--work-tree", "--namespace", "--config-env", "--super-prefix")

/** Global options that make git read another repository, configuration or program. */
private val REDIRECT_OPTIONS = listOf("--git-dir", "--work-tree", "--namespace", "--config-env", "--exec-path")

/** `-c` keys that change where a push goes or what it sends. */
private val REDIRECT_CONFIG_PREFIXES = listOf(
    "remote.", "url.", "branch.", "push.", "core.", "include.", "includeif.", "alias.",
)

private fun redirectingConfig(assignment: String): Boolean {
    val key = assignment.substringBefore('=').lowercase()
    return REDIRECT_CONFIG_PREFIXES.any { key.startsWith(it) }
}

/** Environment variables that make git read another repository or configuration, or connect elsewhere. */
private val REDIRECT_VARIABLES = setOf(
    "GIT_DIR", "GIT_WORK_TREE", "GIT_NAMESPACE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_EXEC_PATH", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_PROXY_COMMAND",
    "HOME", "XDG_CONFIG_HOME",
)

/** The variable a `NAME=value` word assigns; null when the word is not an assignment. */
internal fun assignedVariable(word: String): String? {
    val eq = word.indexOf('=')
    if (eq <= 0) return null
    val name = word.substring(0, eq)
    val valid = (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' }
    return if (valid) name else null
}

/** Whether a word assigns a variable that redirects git. */
internal fun redirectingAssignment(word: String): Boolean {
    val name = assignedVariable(word)?.uppercase() ?: return false
    return name in REDIRECT_VARIABLES || name.startsWith("GIT_CONFIG")
}

/** Words allowed before `git` in a plain push: assignments and these. */
private val PLAIN_PREFIXES = setOf(
    "!", "{", "if", "then", "do", "else", "elif", "while", "until", "time", "command", "exec", "env",
)

/** Whether `git` at [gitIndex] runs as the command of the segment (only assignments and keywords before it). */
internal fun atCommandPosition(words: List<String>, gitIndex: Int): Boolean =
    (0 until gitIndex).all { assignedVariable(words[it]) != null || words[it] in PLAIN_PREFIXES }

/**
 * Whether a word is built by the shell at run time (`$x`, `$(…)`, backticks, `{a,b}`, a glob): its value is unknown
 * here.
 */
internal fun dynamic(word: String): Boolean = dynamicCommand(word) || '?' in word || '[' in word || '*' in word

/** Whether a command name is built at run time: a substitution, a variable or a brace expansion. */
internal fun dynamicCommand(word: String): Boolean = '$' in word || '`' in word || braceExpansion(word)

/** Whether [word] holds a brace expansion (`{a,b}`, `{1..3}`), which the shell turns into several words. */
internal fun braceExpansion(word: String): Boolean {
    var open = word.indexOf('{')
    while (open >= 0) {
        val close = word.indexOf('}', open + 1)
        if (close < 0) return false
        val inside = word.substring(open + 1, close)
        if (',' in inside || ".." in inside) return true
        open = word.indexOf('{', open + 1)
    }
    return false
}

/**
 * The arguments of one `git push`, read from the command alone.
 *
 * @property remote The repository argument (first positional, or `--repo`).
 * @property refspecs The refspecs, as written.
 * @property refusal What makes the arguments unreadable, if anything.
 */
internal class PushArguments(
    val remote: String?,
    val refspecs: List<String>,
    val delete: Boolean,
    val force: Boolean,
    val tags: Boolean,
    val refusal: Refusal?,
)

private val UNLISTABLE_OPTIONS = setOf("--all", "--mirror", "--branches", "--tags", "--follow-tags")
private val PUSH_VALUE_OPTIONS = setOf("--push-option", "--receive-pack", "--exec")

/** The long options of `git push`: git accepts any unambiguous prefix of them (`--al` is `--all`). */
private val PUSH_LONG_OPTIONS = listOf(
    "--all", "--branches", "--mirror", "--delete", "--tags", "--follow-tags", "--dry-run", "--porcelain", "--force",
    "--force-with-lease", "--force-if-includes", "--recurse-submodules", "--thin", "--receive-pack", "--exec",
    "--set-upstream", "--progress", "--prune", "--verify", "--signed", "--atomic", "--push-option", "--ipv4", "--ipv6",
    "--quiet", "--verbose", "--repo", "--no-verify", "--no-thin", "--no-signed", "--no-force-with-lease",
    "--no-force-if-includes", "--no-recurse-submodules", "--no-atomic", "--no-progress", "--no-follow-tags",
)

/**
 * The full name of a long option of `git push` as git reads it: itself, or the one option it abbreviates; null when
 * it abbreviates several (git then refuses to run) or none.
 */
internal fun pushOption(name: String): String? {
    if (name in PUSH_LONG_OPTIONS) return name
    return PUSH_LONG_OPTIONS.filter { it.startsWith(name) }.singleOrNull()
}

/** Reads the words after `push`. */
internal fun pushArguments(args: List<String>): PushArguments {
    val positionals = ArrayList<String>()
    var repoOption: String? = null
    var delete = false
    var force = false
    var tags = false
    var refusal: Refusal? = null
    var j = 0
    var optionsEnded = false
    while (j < args.size) {
        val a = args[j]
        if (optionsEnded || !a.startsWith("-") || a == "-") {
            positionals.add(a)
            j++
            continue
        }
        if (a == "--") {
            optionsEnded = true
            j++
            continue
        }
        if (!a.startsWith("--")) {
            // A cluster of short options (`-fu`, `-d`, `-oci.skip`): `-o` takes the rest, or the next word.
            val o = a.indexOf('o')
            val flags = if (o < 0) a else a.substring(0, o)
            if ('f' in flags) force = true
            if ('d' in flags) delete = true
            if (o == a.length - 1) j++
            j++
            continue
        }
        val name = pushOption(a.substringBefore('='))
        val value = if ('=' in a) a.substringAfter('=') else null
        when {
            // An abbreviation of several options, or of none: git refuses it, Ostrom does not read further.
            name == null -> refusal = Refusal.NOT_PLAIN
            name in UNLISTABLE_OPTIONS -> {
                tags = tags || name == "--tags" || name == "--follow-tags"
                refusal = Refusal.UNLISTABLE
            }
            name == "--recurse-submodules" -> if (value in SUBMODULE_PUSHES) refusal = Refusal.UNLISTABLE
            name == "--delete" -> delete = true
            name == "--repo" -> repoOption = value ?: args.getOrNull(++j)
            name in PUSH_VALUE_OPTIONS -> if (value == null) j++
            name.startsWith("--force") -> force = true
        }
        j++
    }
    val remote = positionals.firstOrNull() ?: repoOption
    val refspecs = positionals.drop(1)
    force = force || refspecs.any { it.startsWith("+") }
    if (refspecs.any { '*' in it }) refusal = Refusal.UNLISTABLE
    if (refspecs.any(::dynamic) || remote?.let(::dynamic) == true) refusal = Refusal.NOT_PLAIN
    return PushArguments(remote, refspecs, delete, force, tags, refusal)
}

private val SUBMODULE_PUSHES = setOf("on-demand", "only")

/** `git <verb>` with a verb git does not know: an alias, maybe of `push`, that only the git configuration tells. */
internal class GitAliasCandidate(val verb: String, val directories: List<String>)

/** Whether the alias [definition] may push: it names `push`, or runs a shell command (`!…`). */
internal fun aliasMayPush(definition: String?): Boolean =
    definition != null && (definition.trimStart().startsWith("!") || containsWord(definition, "push"))

/** One `git push` of a command. */
internal class PushInvocation(
    val directories: List<String>,
    val arguments: PushArguments,
    val plain: Boolean,
    val redirect: Boolean,
    val standalone: Boolean,
) {
    /** The destinations the command names itself; a bare `HEAD`/`@` or a bare push names none. */
    fun explicitDestinations(): List<String> = arguments.refspecs.mapNotNull { spec ->
        val (src, dst) = splitRefspec(spec)
        when {
            dst.isNotEmpty() -> shortRef(dst)
            src == "HEAD" || src == "@" -> null
            else -> shortRef(src)
        }
    }

    /** Where this push lands in [state], or why that cannot be told. */
    fun resolve(state: GitState): Resolution {
        val config = state.config
        val branch = state.currentBranch
        val remote = arguments.remote ?: defaultRemote(state)
        val configuredPush = config.getAll("remote.$remote.push")
        if (config.get("push.recurseSubmodules")?.lowercase() in SUBMODULE_PUSHES) {
            return Resolution.Refused(Refusal.UNLISTABLE, "push.recurseSubmodules")
        }
        val refs = ArrayList<PushTarget>()
        if (arguments.refspecs.isEmpty()) {
            val mode = config.get("push.default")?.lowercase()
            val refused = when {
                configuredPush.isNotEmpty() -> Resolution.Refused(Refusal.CONFIG_DRIVEN, "remote.$remote.push")
                config.getBoolean("remote.$remote.mirror") ->
                    Resolution.Refused(Refusal.CONFIG_DRIVEN, "remote.$remote.mirror")
                mode == "matching" -> Resolution.Refused(Refusal.CONFIG_DRIVEN, "push.default=matching")
                else -> null
            }
            if (refused != null) return refused
            if (branch == null) return Resolution.Refused(Refusal.DETACHED_HEAD, null)
            val upstream = config.get("branch.$branch.merge")
            when {
                mode == "nothing" -> Unit
                mode == "current" || upstream == null -> refs.add(PushTarget("HEAD", branch))
                else -> refs.add(PushTarget("HEAD", shortRef(upstream)))
            }
        }
        for (spec in arguments.refspecs) {
            val (src, dst) = splitRefspec(spec)
            refs.add(
                when {
                    arguments.delete || src.isEmpty() -> PushTarget(null, shortRef(dst.ifEmpty { src }))
                    dst.isNotEmpty() -> PushTarget(src, shortRef(dst))
                    configuredPush.isNotEmpty() ->
                        return Resolution.Refused(Refusal.CONFIG_DRIVEN, "remote.$remote.push")
                    src != "HEAD" && src != "@" -> PushTarget(src, shortRef(src))
                    branch == null -> return Resolution.Refused(Refusal.DETACHED_HEAD, null)
                    else -> PushTarget(src, branch)
                },
            )
        }
        val pushUrls = config.getAll("remote.$remote.pushurl")
        val urls = config.getAll("remote.$remote.url")
        val url = when {
            pushUrls.size > 1 || (pushUrls.isEmpty() && urls.size > 1) -> {
                return Resolution.Refused(Refusal.SEVERAL_DESTINATIONS, remote)
            }
            pushUrls.isNotEmpty() -> RemoteUrls.rewriteForPush(pushUrls[0], config, explicitPushUrl = true)
            else -> RemoteUrls.rewriteForPush(urls.firstOrNull() ?: remote, config, explicitPushUrl = false)
        }
        return Resolution.Landed(PushTargets(remote, url, RemoteUrls.slugOf(url), refs, arguments.force))
    }

    /** The remote of a push without repository argument: git's order of precedence. */
    private fun defaultRemote(state: GitState): String {
        val config = state.config
        val branch = state.currentBranch
        val fromBranch = if (branch == null) null else config.get("branch.$branch.pushRemote")
        return fromBranch ?: config.get("remote.pushDefault")
            ?: (if (branch == null) null else config.get("branch.$branch.remote"))
            ?: "origin"
    }
}

/** Where a push lands, or why that cannot be told. */
internal sealed interface Resolution {
    class Landed(val targets: PushTargets) : Resolution
    class Refused(val refusal: Refusal, val detail: String?) : Resolution
}

/** `+src:dst` → `src` to `dst` (the `+` dropped, `dst` empty when absent). */
internal fun splitRefspec(spec: String): Pair<String, String> {
    val s = spec.removePrefix("+")
    val colon = s.indexOf(':')
    return if (colon < 0) s to "" else s.substring(0, colon) to s.substring(colon + 1)
}

/** A branch by its short name; any other ref in full. */
internal fun shortRef(ref: String): String = when {
    ref.startsWith("refs/heads/") -> ref.removePrefix("refs/heads/")
    // git completes a destination as it completes a revision: `heads/main` is `refs/heads/main`.
    ref.startsWith("heads/") -> ref.removePrefix("heads/")
    else -> ref
}

/** Whether a ref looks like a release tag: `refs/tags/…`, or a part that starts like a version (`v1.2`, `1.2.3`). */
internal fun tagLike(ref: String): Boolean =
    ref.startsWith("refs/tags/") || ref.startsWith("tags/") || ref.split('/').any { part ->
        val p = part.removePrefix("v").removePrefix("V")
        val dot = p.indexOf('.')
        dot > 0 && p.substring(0, dot).all { it.isDigit() } && p.getOrNull(dot + 1)?.isDigit() == true
    }
