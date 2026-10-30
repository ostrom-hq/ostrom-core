// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

/** The program a word names: its file name, lower-cased, without `.exe` (`/usr/bin/GIT` → `git`). */
internal fun toolName(word: String): String =
    word.substring(maxOf(word.lastIndexOf('/'), word.lastIndexOf('\\')) + 1).lowercase().removeSuffix(".exe")

internal fun same(word: String?, expected: String): Boolean = word.equals(expected, ignoreCase = true)

/**
 * The index of the first word from [from] that [isVerb] accepts, when only options stand before it; -1 otherwise.
 *
 * Options may take their value as the next word (`kubectl -n prod apply`), and a word right after an option may be that
 * value or the verb: both readings are tried, as the prototype's patterns did (`kubectl --insecure apply`).
 */
internal fun findVerb(words: List<String>, from: Int, isVerb: (String) -> Boolean): Int {
    var valueAllowed = false
    for (j in from until words.size) {
        // Past the bound, the verb is taken as found: a hostile command cannot hide one behind options, nor cost more.
        if (j - from >= MAX_SCAN) return j
        val w = words[j]
        if (w.length > 1 && w[0] == '-') {
            valueAllowed = '=' !in w
            continue
        }
        if (isVerb(w)) return j
        if (!valueAllowed) return -1
        valueAllowed = false
    }
    return -1
}

/** How many words a scanner reads after a tool's name before it stops and assumes the worst. */
internal const val MAX_SCAN: Int = 64

/** Whether a word after [from] satisfies [test]; true too past [MAX_SCAN] words (assume the worst). */
internal fun anyAfter(words: List<String>, from: Int, test: (String) -> Boolean): Boolean {
    for (j in from + 1 until words.size) {
        if (j - from > MAX_SCAN || test(words[j])) return true
    }
    return false
}

internal fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** Whether [word] contains [part] as a whole word (`\bpart\b`), without case. */
internal fun containsWord(word: String, part: String): Boolean {
    var k = word.indexOf(part, ignoreCase = true)
    while (k >= 0) {
        if (!isWordChar(word.getOrNull(k - 1)) && !isWordChar(word.getOrNull(k + part.length))) return true
        k = word.indexOf(part, k + 1, ignoreCase = true)
    }
    return false
}

/** A word of a call to the GitHub API: api.github.com, or the `/api/v3/` and `/api/graphql` of GitHub Enterprise. */
internal fun githubApiWord(word: String): Boolean = word.contains("api.github.com/", ignoreCase = true) ||
    word.contains("/api/v3/", ignoreCase = true) || word.contains("/api/graphql", ignoreCase = true)

/** `pulls/<n>/merge` in a GitHub API path, whatever stands for the number (`$PR`, `{n}`, `:number`). */
internal fun hasPullsMerge(word: String): Boolean {
    var k = word.indexOf("pulls/", ignoreCase = true)
    while (k >= 0) {
        var end = k + "pulls/".length
        val digits = end
        while (end < word.length && word[end] != '/') end++
        if (end > digits && word.regionMatches(end, "/merge", 0, "/merge".length, ignoreCase = true) &&
            !isWordChar(word.getOrNull(end + "/merge".length))
        ) {
            return true
        }
        k = word.indexOf("pulls/", k + 1, ignoreCase = true)
    }
    return false
}

/** `/merges` (the merge endpoint of a repository) in a GitHub API path. */
internal fun hasMerges(word: String): Boolean {
    var k = word.indexOf("/merges", ignoreCase = true)
    while (k >= 0) {
        if (!isWordChar(word.getOrNull(k + "/merges".length))) return true
        k = word.indexOf("/merges", k + 1, ignoreCase = true)
    }
    return false
}

/** The ref name that follows [marker] in [word], up to a query, a fragment or a quote. */
internal fun refAfter(word: String, marker: String): String? {
    val k = word.indexOf(marker, ignoreCase = true)
    if (k < 0) return null
    return word.substring(k + marker.length).takeWhile { it !in REF_END }
}

private const val REF_END = "?#\"',;{}&"

/** A GitHub owner or repository name. */
internal fun validName(name: String): Boolean = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in "_.-" }

/** `repos/<owner>/<repo>/pulls/<n>/merge` → the pull request. */
internal fun apiPullRequest(word: String): PullRequestRef? {
    val k = word.indexOf("repos/", ignoreCase = true)
    if (k < 0) return null
    val parts = word.substring(k + "repos/".length).split('/')
    if (parts.size < 5 || !same(parts[2], "pulls") || !parts[4].startsWith("merge", ignoreCase = true)) return null
    if (isWordChar(parts[4].getOrNull("merge".length))) return null
    val number = parts[3].takeIf { it.all(Char::isDigit) }?.toIntOrNull() ?: return null
    // `gh api` fills `{owner}` and `{repo}` with the repository of the current directory.
    if (parts[0] == "{owner}" && parts[1] == "{repo}") return PullRequestRef(number)
    if (!validName(parts[0]) || !validName(parts[1])) return null
    return PullRequestRef(number, parts[0], parts[1])
}

/** `github.com/<owner>/<repo>/pull/<n>` → the pull request. */
internal fun pullRequestUrl(word: String): PullRequestRef? {
    val k = word.indexOf("github.com/", ignoreCase = true)
    if (k < 0) return null
    val parts = word.substring(k + "github.com/".length).split('/')
    if (parts.size < 4 || !same(parts[2], "pull") || !validName(parts[0]) || !validName(parts[1])) return null
    val number = parts[3].takeWhile(Char::isDigit).toIntOrNull() ?: return null
    return PullRequestRef(number, parts[0], parts[1])
}

/** `OWNER/REPO` or `HOST/OWNER/REPO` (the `-R` of gh) → owner to repo. */
internal fun repoSpec(value: String): Pair<String, String>? {
    val parts = value.split('/')
    if (parts.size !in 2..3 || !validName(parts[parts.size - 2]) || !validName(parts.last())) return null
    return parts[parts.size - 2] to parts.last()
}

private val WRITE_METHODS = setOf("PUT", "DELETE")
private val METHOD_OPTIONS = setOf("-X", "--method", "--request")

/** Whether an HTTP call of the segment writes (`-X PUT`, `--method DELETE`, `-XPUT`, `--request=PUT`). */
internal fun writesWithMethod(words: List<String>): Boolean = words.indices.any { j ->
    val w = words[j]
    val method = when {
        w in METHOD_OPTIONS -> words.getOrNull(j + 1)
        w.startsWith("-X") -> w.substring(2)
        w.startsWith("--method=") || w.startsWith("--request=") -> w.substringAfter('=')
        else -> null
    }
    method?.uppercase() in WRITE_METHODS
}

/** Programs that run the next words as a command (`sudo kubectl apply`). */
private val WRAPPERS = setOf(
    "sudo", "doas", "env", "nohup", "time", "nice", "ionice", "timeout", "command", "exec", "xargs", "watch", "stdbuf",
    "caffeinate", "unbuffer", "npx", "bunx", "bundle",
)

/** Options of ssh that take a value. */
private const val SSH_VALUE_OPTIONS = "BbcDEeFIiJLlmOoPpQRSWw"

/** Where the remote command of `ssh [options] host command` starts. */
internal fun sshCommand(words: List<String>, ssh: Int): Int {
    var j = ssh + 1
    while (j < words.size && words[j].length > 1 && words[j][0] == '-') {
        // `-p 22` takes the next word; `-p22` and flag clusters (`-tt`) do not.
        j += if (words[j].length == 2 && words[j][1] in SSH_VALUE_OPTIONS) 2 else 1
    }
    return j + 1
}

/** Shell keywords that may come before a command. */
private val KEYWORDS = setOf("!", "{", "if", "then", "do", "else", "elif", "while", "until")

/**
 * Where the command of a segment may stand: after assignments, keywords and wrappers (with their options). A word right
 * after a wrapper's option may be its value (`sudo -u deploy kubectl`), so the word after it is a candidate too.
 */
internal fun commandPositions(words: List<String>): List<Int> {
    var afterWrapper = false
    var optionBefore = false
    for (j in words.indices) {
        val w = words[j]
        when {
            assignedVariable(w) != null -> Unit
            toolName(w) == "ssh" -> return listOf(j, sshCommand(words, j))
            toolName(w) in WRAPPERS || w in KEYWORDS -> {
                afterWrapper = true
                optionBefore = false
            }
            afterWrapper && w.length > 1 && w[0] == '-' -> optionBefore = '=' !in w
            afterWrapper && w.all { it.isDigit() || it in "smhd." } -> optionBefore = false
            else -> return if (optionBefore) listOf(j, j + 1) else listOf(j)
        }
    }
    return emptyList()
}
