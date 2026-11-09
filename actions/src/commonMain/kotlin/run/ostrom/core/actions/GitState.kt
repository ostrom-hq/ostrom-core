// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

/**
 * The git configuration of a repository, as `git config --list -z` prints it: every entry, in order, keys normalized
 * as git does (section and variable names lower-cased, the subsection kept as written).
 *
 * Reading it is the caller's job (one `git config --list -z` process in the directory the push runs in); this class
 * only answers what git would read from it.
 */
public class GitConfig private constructor(private val entries: List<Pair<String, String?>>) {

    /** The last value of [key] (git's rule for a single-valued key); null when absent; "" for a key without value. */
    public fun get(key: String): String? {
        val k = normalize(key)
        val last = entries.lastOrNull { it.first == k } ?: return null
        return last.second.orEmpty()
    }

    /** Every value of [key], in order. */
    public fun getAll(key: String): List<String> {
        val k = normalize(key)
        return entries.filter { it.first == k }.map { it.second.orEmpty() }
    }

    /** [key] read as a git boolean: absent is false, a key without value is true. */
    public fun getBoolean(key: String): Boolean {
        val k = normalize(key)
        val last = entries.lastOrNull { it.first == k } ?: return false
        val value = last.second
        return value == null || value.lowercase() in TRUE_VALUES
    }

    /** The entries whose key starts with [prefix] (normalized) and ends with [suffix] (lower-case), in order. */
    internal fun matching(prefix: String, suffix: String): List<Pair<String, String>> =
        entries.filter { it.first.startsWith(prefix) && it.first.endsWith(suffix) }
            .map { it.first to it.second.orEmpty() }

    /** Readers of the configuration text. */
    public companion object {
        private val TRUE_VALUES = setOf("true", "yes", "on", "1")

        /** No configuration at all. */
        public val EMPTY: GitConfig = GitConfig(emptyList())

        /**
         * Reads the output of `git config --list -z`: entries end with NUL, the key ends at the first new line, an
         * entry without new line is a key without value.
         */
        public fun parseListZ(output: String): GitConfig = GitConfig(
            output.split('\u0000').filter { it.isNotEmpty() }.map { entry ->
                val newline = entry.indexOf('\n')
                if (newline < 0) {
                    normalize(entry) to null
                } else {
                    normalize(entry.substring(0, newline)) to entry.substring(newline + 1)
                }
            },
        )

        /** A configuration from `key to value` pairs, for tests and for callers that read it another way. */
        public fun of(vararg entries: Pair<String, String>): GitConfig =
            GitConfig(entries.map { normalize(it.first) to it.second })

        /** `Section.Sub.Section.Name` → `section.Sub.Section.name`: git compares sections and names without case. */
        internal fun normalize(key: String): String {
            val first = key.indexOf('.')
            val last = key.lastIndexOf('.')
            if (first < 0) return key.lowercase()
            return key.substring(0, first).lowercase() + key.substring(first, last) + key.substring(last).lowercase()
        }
    }
}

/**
 * What git would read, in the directory a push runs in, to decide where the push lands.
 *
 * @property currentBranch The branch `HEAD` points to (`git symbolic-ref --quiet --short HEAD`), null when detached.
 * @property config The repository's configuration, every level merged (`git config --list -z`).
 */
public data class GitState(
    val currentBranch: String?,
    val config: GitConfig,
)

/** Reads the [GitState] of the directory a push runs in. Implemented by the CLI (it runs git); never by the server. */
public fun interface GitStateReader {

    /**
     * @param directories The directories the command moves into before the push, in order, as written (`cd` arguments,
     *   then `git -C` values): relative ones apply to the previous one, the first to the session's directory.
     *
     * @return The state, or null when it cannot be read (not a repository): the push is then refused.
     */
    public fun read(directories: List<String>): GitState?
}
