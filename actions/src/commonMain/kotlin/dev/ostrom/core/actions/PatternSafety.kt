// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

/**
 * Screens the regular expressions an organization declares for its custom actions. They run on text the agent writes,
 * in the CLI hook and on the server: a pattern open to catastrophic backtracking would let an agent stall the server.
 * Refused: back-references, and a repeated group that itself repeats or alternates (`(a+)+`, `(a|ab)*`, `(x*){2,}`),
 * the shapes that backtrack exponentially. The text a pattern reads is bounded too
 * ([ActionClassifier.MAX_PATTERN_TEXT]), which keeps the polynomial cases small.
 */
internal object PatternSafety {
    const val MAX_LENGTH: Int = 512
    const val MAX_PER_CLASS: Int = 32

    /** What follows `\` in a back-reference. */
    private const val BACK_REFERENCES = "k123456789"

    /** Bounds that never repeat. */
    private val SINGLE = setOf("0", "1", "0,1", "1,1")

    private class Group {
        var repeats = false
        var alternates = false
    }

    /** Null when [pattern] is acceptable, else why not. */
    // One pass over the pattern, one branch per regular-expression token.
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    fun problem(pattern: String): String? {
        if (pattern.isEmpty() || pattern.length > MAX_LENGTH) return "a pattern has 1 to $MAX_LENGTH characters"
        val open = ArrayDeque<Group>()
        open.addLast(Group())
        var closed: Group? = null
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            var nextClosed: Group? = null
            when (c) {
                '\\' -> {
                    if (pattern.getOrElse(i + 1) { ' ' } in BACK_REFERENCES) return "back-references are not allowed"
                    i++
                }
                '[' -> i = classEnd(pattern, i)
                '(' -> {
                    open.addLast(Group())
                    if (pattern.getOrNull(i + 1) == '?') i++
                }
                ')' -> {
                    if (open.size < 2) return "unbalanced parenthesis"
                    val group = open.removeLast()
                    open.last().repeats = open.last().repeats || group.repeats
                    open.last().alternates = open.last().alternates || group.alternates
                    nextClosed = group
                }
                '|' -> open.last().alternates = true
                '*', '+', '{' -> {
                    var repeats = true
                    if (c == '{') {
                        val end = pattern.indexOf('}', i)
                        if (end < 0) return "unbalanced brace"
                        repeats = pattern.substring(i + 1, end) !in SINGLE
                        i = end
                    }
                    val backtracks = closed != null && (closed.repeats || closed.alternates)
                    if (repeats && backtracks) {
                        return "a repeated group that repeats or alternates inside can backtrack without end"
                    }
                    if (repeats) open.last().repeats = true
                }
            }
            closed = nextClosed
            i++
        }
        return if (open.size == 1) null else "unbalanced parenthesis"
    }

    /** The index of the `]` that closes the class opened at [start] (a leading `]` or `^]` is literal). */
    private fun classEnd(pattern: String, start: Int): Int {
        var j = start + 1
        if (pattern.getOrNull(j) == '^') j++
        if (pattern.getOrNull(j) == ']') j++
        while (j < pattern.length && pattern[j] != ']') j += if (pattern[j] == '\\') 2 else 1
        return j
    }
}
