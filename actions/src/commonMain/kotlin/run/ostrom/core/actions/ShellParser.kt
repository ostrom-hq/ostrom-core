// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

/** Where a piece of shell text was found, which says how sure we are that it runs. */
public enum class SegmentOrigin {
    /** The command itself. */
    SCRIPT,

    /** Text the shell runs for sure: `$(…)`, backticks, `<(…)`, `${…}`, a here-document body. */
    EXECUTED,

    /** A quoted word with spaces in it, which may be a script (`sh -c '…'`, `ssh host '…'`) or plain text. */
    QUOTED,
}

/**
 * One simple command of a shell script: its words once quotes and escapes are removed, redirections left out.
 *
 * @property words The words, in order (`git -C "my dir" push` gives `git`, `-C`, `my dir`, `push`).
 * @property depth 0 for a command of the script itself, 1 and more inside a substitution or a quoted word.
 * @property origin How sure we are that it runs.
 */
public class ShellSegment internal constructor(
    public val words: List<String>,
    public val depth: Int,
    public val origin: SegmentOrigin,
    /** Whether the shell runs the segment as a command line: not a here-document body nor a `${…}` expansion. */
    internal val commandLine: Boolean = true,
)

/**
 * A shell command cut into its simple commands, nested scripts included.
 *
 * @property segments Every simple command: those of the command first, in order, then the nested ones.
 * @property malformed An unterminated quote, substitution or here-document, or nesting deeper than
 *   [ShellParser.MAX_DEPTH]: a real shell would read it differently, so a caller must not trust what is missing.
 * @property subshells The command groups commands with `(…)`: a `cd` inside one does not move the next commands.
 */
public class ShellScript internal constructor(
    public val segments: List<ShellSegment>,
    public val malformed: Boolean,
    public val subshells: Boolean,
)

/**
 * A small shell lexer, written for one purpose: find every command a shell line may run, without running anything.
 *
 * It honours quotes, escapes, comments, separators (`;`, `&&`, `||`, `|`, `&`, new lines, parentheses), redirections
 * (dropped from the words, `2>&1` included), here-documents and here-strings, and it parses again, one level deeper,
 * everything the shell may execute: `$(…)`, backticks, process substitutions, here-document bodies, and every quoted
 * word that contains a space (so `bash -c 'git push origin main'` is seen). It is linear in the input for each level,
 * and never uses a regular expression.
 */
public object ShellParser {

    /** Nesting deeper than this is not parsed, and the script is [ShellScript.malformed]. */
    public const val MAX_DEPTH: Int = 4

    /**
     * Parses a shell command.
     *
     * @param text The command, as the agent would give it to `sh -c`.
     *
     * @return Its simple commands.
     */
    public fun parse(text: String): ShellScript {
        val out = ArrayList<ShellSegment>()
        val queue = ArrayDeque<PendingScript>()
        queue.add(PendingScript(text, 0, SegmentOrigin.SCRIPT, commandLine = true))
        var malformed = false
        var subshells = false
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next.depth > MAX_DEPTH) {
                malformed = true
                continue
            }
            val lexer = Lexer(next, out, queue)
            lexer.run()
            malformed = malformed || lexer.malformed
            // Only the command itself moves the shell; a subshell inside a substitution does not.
            if (next.depth == 0) subshells = lexer.subshells
        }
        return ShellScript(out, malformed, subshells)
    }
}

internal class PendingScript(val text: String, val depth: Int, val origin: SegmentOrigin, val commandLine: Boolean)

private enum class NextWord { KEEP, DISCARD, HEREDOC, HERESTRING }

/** Characters that make a quoted word worth parsing as a script. */
private const val SCRIPT_CHARS = " \t\n;&|"

/** The one-letter escapes of `$'…'`, and what they stand for. */
private const val ANSI_LETTERS = "ntrabeEfv\\'\"?"
private const val ANSI_VALUES = "\n\t\r\u0007\b\u001b\u001b\u000c\u000b\\'\"?"
private const val CONTROL_MASK = 0x1f
private const val OCTAL = 8
private const val OCTAL_DIGITS = 3
private const val HEX = 16

/** The hex digits of `\u` and of `\U` in `$'…'`. */
private const val UNICODE_DIGITS = 4
private const val LONG_UNICODE_DIGITS = 8

/** Whether a word right before a redirection operator is its file descriptor: ASCII digits only, as in `2>`. */
internal fun isFileDescriptor(word: CharSequence): Boolean = word.isNotEmpty() && word.all { it in '0'..'9' }

/** Characters that end a segment, run together (`&&`, `||`, `;;`). */
private const val SEPARATORS = "&|;"

/** Lexes one script, one level: nested scripts go to [queue], one level deeper. */
@Suppress("TooManyFunctions") // The lexer's states, one function each.
private class Lexer(
    private val script: PendingScript,
    private val out: MutableList<ShellSegment>,
    private val queue: ArrayDeque<PendingScript>,
) {
    private val s = script.text
    private val n = s.length
    private var i = 0
    private val token = StringBuilder()

    /** The word with each substitution replaced by `$_`: what is parsed again when the word may be a script. */
    private val plain = StringBuilder()
    private var hasToken = false
    private var words = ArrayList<String>()
    private var nextWord = NextWord.KEEP
    private var stripTabs = false
    private val heredocs = ArrayList<Pair<String, Boolean>>()
    var malformed = false
        private set
    var subshells = false
        private set

    fun run() {
        while (i < n) step()
        endSegment()
        // A here-document whose command is on the last line has no body: the shell warns, we stay suspicious.
        if (heredocs.isNotEmpty()) malformed = true
    }

    @Suppress("CyclomaticComplexMethod") // One branch per shell character class, in the order the shell reads them.
    private fun step() {
        val c = s[i]
        val next = if (i + 1 < n) s[i + 1] else ' '
        when {
            c == '\'' -> singleQuoted()
            c == '"' -> doubleQuoted()
            c == '\\' -> escaped()
            c == '`' -> backtick()
            c == '$' && (next == '(' || next == '{') -> substitution(i + 2, next)
            c == '$' && next == '\'' -> ansiQuoted()
            // `$"…"` is a double-quoted word (translated by the locale, which changes no command name).
            c == '$' && next == '"' -> i++
            c == '#' && !hasToken -> comment()
            c == ' ' || c == '\t' || c == '\r' -> {
                flush()
                i++
            }
            c == '\n' -> {
                endSegment()
                i++
                hereDocumentBodies()
            }
            (c == '<' || c == '>') && next == '(' -> substitution(i + 2, '(')
            c == '<' || c == '>' || (c == '&' && next == '>') -> redirection()
            c == ';' || c == '|' || c == '&' || c == '(' || c == ')' -> separator(c)
            else -> {
                add(c)
                hasToken = true
                i++
            }
        }
    }

    private fun add(c: Char) {
        token.append(c)
        plain.append(c)
    }

    private fun addRange(from: Int, to: Int) {
        token.append(s, from, to)
        plain.append(s, from, to)
    }

    /** A substitution: the word keeps its raw text, the script view a `$_` (its value is unknown here). */
    private fun addSubstitution(from: Int, to: Int) {
        token.append(s, from, to)
        plain.append("\$_")
    }

    private fun singleQuoted() {
        hasToken = true
        val end = s.indexOf('\'', i + 1)
        if (end < 0) {
            malformed = true
            addRange(i + 1, n)
            i = n
        } else {
            addRange(i + 1, end)
            i = end + 1
        }
    }

    /** `$'…'`: a quoted word whose escapes the shell decodes (`$'\x70ush'` is `push`). */
    private fun ansiQuoted() {
        hasToken = true
        var j = i + 2
        while (j < n && s[j] != '\'') {
            if (s[j] == '\\' && j + 1 < n) {
                j = ansiEscape(j + 1)
            } else {
                add(s[j])
                j++
            }
        }
        if (j >= n) malformed = true
        i = minOf(j + 1, n)
    }

    /** Decodes the escape whose letter is at [k]; returns where the word goes on. */
    private fun ansiEscape(k: Int): Int {
        val c = s[k]
        val simple = ANSI_LETTERS.indexOf(c)
        return when {
            simple >= 0 -> {
                add(ANSI_VALUES[simple])
                k + 1
            }
            c == 'c' && k + 1 < n -> {
                add((s[k + 1].code and CONTROL_MASK).toChar())
                k + 2
            }
            c in '0'..'7' -> codePoint(k, OCTAL, OCTAL_DIGITS)
            c == 'x' -> codePoint(k + 1, HEX, 2)
            c == 'u' -> codePoint(k + 1, HEX, UNICODE_DIGITS)
            c == 'U' -> codePoint(k + 1, HEX, LONG_UNICODE_DIGITS)
            else -> {
                add('\\')
                add(c)
                k + 1
            }
        }
    }

    /** Up to [max] digits in [radix] from [from], as one character; `\x` without digits stays as written. */
    private fun codePoint(from: Int, radix: Int, max: Int): Int {
        var j = from
        var value = 0L
        while (j < n && j - from < max) {
            val digit = s[j].digitToIntOrNull(radix) ?: break
            value = value * radix + digit
            j++
        }
        if (j == from) {
            add('\\')
            add(s[from - 1])
        } else {
            // A command name is plain ASCII: a character outside the basic plane only needs to stay unknown.
            add(if (value <= Char.MAX_VALUE.code) value.toInt().toChar() else '\uFFFD')
        }
        return j
    }

    private fun doubleQuoted() {
        hasToken = true
        i++
        while (i < n) {
            val c = s[i]
            val next = if (i + 1 < n) s[i + 1] else ' '
            when {
                c == '"' -> {
                    i++
                    return
                }
                c == '\\' && i + 1 < n -> {
                    add(next)
                    i += 2
                }
                c == '`' -> backtick()
                c == '$' && (next == '(' || next == '{') -> substitution(i + 2, next)
                else -> {
                    add(c)
                    i++
                }
            }
        }
        malformed = true
    }

    private fun escaped() {
        // `\` + new line continues the line; any other escaped character is literal.
        if (i + 1 < n && s[i + 1] != '\n') {
            add(s[i + 1])
            hasToken = true
        }
        i += 2
    }

    private fun comment() {
        val end = s.indexOf('\n', i)
        i = if (end < 0) n else end
    }

    /** `$(…)`, `${…}`, `<(…)`, `>(…)`: the inside runs, so it is parsed one level deeper; the word keeps its text. */
    private fun substitution(innerStart: Int, open: Char) {
        val end = closing(innerStart, open, if (open == '(') ')' else '}')
        hasToken = true
        // `${…}` expands a parameter: its words are not a command line, though a `$(…)` in it runs.
        val commandLine = open == '('
        if (end < 0) {
            malformed = true
            nested(s.substring(innerStart), SegmentOrigin.EXECUTED, commandLine)
            addSubstitution(i, n)
            i = n
        } else {
            nested(s.substring(innerStart, end), SegmentOrigin.EXECUTED, commandLine)
            addSubstitution(i, end + 1)
            i = end + 1
        }
    }

    private fun backtick() {
        hasToken = true
        var j = i + 1
        while (j < n && s[j] != '`') j += if (s[j] == '\\') 2 else 1
        if (j >= n) {
            malformed = true
            nested(s.substring(i + 1), SegmentOrigin.EXECUTED)
            addSubstitution(i, n)
            i = n
        } else {
            nested(s.substring(i + 1, j), SegmentOrigin.EXECUTED)
            addSubstitution(i, j + 1)
            i = j + 1
        }
    }

    /** The index of the [close] that matches an [open] already read before [from], quotes skipped; -1 if none. */
    @Suppress("NestedBlockDepth") // Nested quotes and substitutions, read in one pass.
    private fun closing(from: Int, open: Char, close: Char): Int {
        var depth = 1
        var j = from
        while (j < n) {
            val c = s[j]
            when (c) {
                '\\' -> j++
                '\'' -> j = s.indexOf('\'', j + 1).let { if (it < 0) n else it }
                '"' -> j = closingDoubleQuote(j + 1)
                open -> depth++
                close -> if (--depth == 0) return j
            }
            j++
        }
        return -1
    }

    private fun closingDoubleQuote(from: Int): Int {
        var j = from
        while (j < n && s[j] != '"') j += if (s[j] == '\\') 2 else 1
        return j
    }

    @Suppress("CyclomaticComplexMethod") // One branch per redirection operator.
    private fun redirection() {
        // A word of digits right before the operator is its file descriptor (`2>`), not an argument.
        if (hasToken && isFileDescriptor(token)) {
            token.setLength(0)
            plain.setLength(0)
            hasToken = false
        } else {
            flush()
        }
        if (s[i] == '&') i++
        val first = s[i++]
        if (first == '<' && i < n && s[i] == '<') {
            i++
            if (i < n && s[i] == '<') {
                i++
                nextWord = NextWord.HERESTRING
            } else {
                stripTabs = i < n && s[i] == '-'
                if (stripTabs) i++
                nextWord = NextWord.HEREDOC
            }
            return
        }
        if (i < n && (s[i] == '>' || s[i] == '|')) i++
        if (i < n && s[i] == '&') {
            i++
            val start = i
            while (i < n && (s[i] in '0'..'9' || s[i] == '-')) i++
            // `>&2`, `2>&1`, `<&-`: a duplication, no target word.
            if (i > start) return
        }
        nextWord = NextWord.DISCARD
    }

    private fun separator(c: Char) {
        if (c == '(' || c == ')') subshells = true
        endSegment()
        i++
        while (i < n && s[i] in SEPARATORS) i++
    }

    private fun flush() {
        if (!hasToken) return
        val word = token.toString()
        val script = plain.toString()
        token.setLength(0)
        plain.setLength(0)
        hasToken = false
        when (nextWord) {
            NextWord.KEEP -> {
                words.add(word)
                // A word that may be a script is parsed as one (its substitutions are already parsed, as `$_` here).
                if (script.any { it in SCRIPT_CHARS }) nested(script, SegmentOrigin.QUOTED)
            }
            NextWord.DISCARD -> Unit
            NextWord.HEREDOC -> heredocs.add(word to stripTabs)
            NextWord.HERESTRING -> nested(script, SegmentOrigin.QUOTED)
        }
        nextWord = NextWord.KEEP
    }

    private fun endSegment() {
        flush()
        nextWord = NextWord.KEEP
        if (words.isNotEmpty()) {
            out.add(ShellSegment(words, script.depth, script.origin, script.commandLine))
            words = ArrayList()
        }
    }

    /** The bodies of the here-documents opened on the line that just ended: they may be scripts (`bash <<EOF`). */
    @Suppress("NestedBlockDepth") // Here-documents of a line, each read to its delimiter in one pass.
    private fun hereDocumentBodies() {
        for ((delimiter, tabs) in heredocs) {
            val body = StringBuilder()
            var found = false
            while (i < n && !found) {
                val lineEnd = s.indexOf('\n', i).let { if (it < 0) n else it }
                val line = s.substring(i, lineEnd)
                i = lineEnd + 1
                val end = if (tabs) line.trimStart('\t') else line
                if (end == delimiter) found = true else body.append(line).append('\n')
            }
            if (!found) malformed = true
            // A here-document is often a file's text (`cat > a.sh <<EOF`): its lines are not taken as command lines.
            nested(body.toString(), SegmentOrigin.EXECUTED, commandLine = false)
        }
        heredocs.clear()
        if (i > n) i = n
    }

    private fun nested(text: String, origin: SegmentOrigin, commandLine: Boolean = true) {
        queue.add(PendingScript(text, script.depth + 1, origin, commandLine))
    }
}
