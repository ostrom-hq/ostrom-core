package dev.ostrom.core.actions

/**
 * A text that a regular expression may read only so many times: each character read spends one unit of [budget], and
 * reading past it throws [Exhausted]. Declared patterns run on what the agent writes, in the CLI hook and on the
 * server: whatever the pattern, its work stays bounded.
 */
internal class BudgetedText(private val text: String, private var budget: Long) : CharSequence {

    /** The budget ran out while a pattern read the text. */
    class Exhausted : RuntimeException("the pattern read the text too many times")

    /** Whether the budget ran out. */
    val exhausted: Boolean get() = budget < 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        budget--
        if (budget < 0) throw Exhausted()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text
}
