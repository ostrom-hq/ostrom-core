// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

/**
 * Classifies a tool call into frontier action classes, and reads what a yes to it binds. Pure: the CLI gives it the
 * git state through a [GitStateReader]; the server, which never runs git, calls it without one to check what the hook
 * sent.
 */
public interface ActionClassifier {

    /** The built-in classes, then the custom ones, in the order [Classification.actions] uses. */
    public val catalogue: List<ActionClass>

    /** The declared patterns that were refused, so the console can show why. */
    public val rejectedPatterns: List<RejectedPattern>

    /**
     * @param subject The tool call.
     * @param git Reads the git state where a push runs, so a push is judged by the refs it lands on (`git push origin
     *   HEAD` from main is a push to main). Without it, only what the command names is known.
     */
    public fun classify(subject: ToolSubject, git: GitStateReader? = null): Classification

    /** Bounds and defaults. */
    public companion object {
        /** A longer command is not read: it is [Refusal.TOO_LARGE] and falls under every class of its kind. */
        public const val MAX_COMMAND_LENGTH: Int = 1_048_576

        /** Declared patterns read at most this much text; a longer command falls under every custom class. */
        public const val MAX_PATTERN_TEXT: Int = 16_384

        /**
         * Characters declared patterns may read, all patterns of one call together. A pattern that backtracks on a long
         * text stops there, and the text falls under every custom class of its kind ([Refusal.TOO_LARGE]).
         */
        public const val PATTERN_BUDGET: Long = 4_000_000

        /** The branches a push to which is `push-main`. */
        public val DEFAULT_PROTECTED_BRANCHES: Set<String> = setOf("main", "master")
    }
}
