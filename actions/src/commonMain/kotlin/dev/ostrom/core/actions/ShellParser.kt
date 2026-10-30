package dev.ostrom.core.actions

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
        queue.add(PendingScript(text, 0, SegmentOrigin.SCRIPT))
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
            subshells = subshells || (next.depth == 0 && lexer.subshells)
        }
        return ShellScript(out, malformed, subshells)
    }
}

internal class PendingScript(val text: String, val depth: Int, val origin: SegmentOrigin)

private enum class NextWord { KEEP, DISCARD, HEREDOC, HERESTRING }

/** Characters that make a quoted word worth parsing as a script. */
private const val SCRIPT_CHARS = " \t\n;&|"


/** Lexes one script, one level: nested scripts go to [queue], one level deeper. */
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

    private fun step() {
        val c = s[i]
        val next = if (i + 1 < n) s[i + 1] else ' '
        when {
            c == '\'' -> singleQuoted()
            c == '"' -> doubleQuoted()
            c == '\\' -> escaped()
            c == '`' -> backtick()
            c == '$' && (next == '(' || next == '{') -> substitution(i + 2, next)
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
        if (end < 0) {
            malformed = true
            nested(s.substring(innerStart), SegmentOrigin.EXECUTED)
            addSubstitution(i, n)
            i = n
        } else {
            nested(s.substring(innerStart, end), SegmentOrigin.EXECUTED)
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

    private fun redirection() {
        // A word of digits right before the operator is its file descriptor (`2>`), not an argument.
        if (hasToken && token.isNotEmpty() && token.all { it in '0'..'9' }) {
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
        while (i < n && (s[i] == '&' || s[i] == '|' || s[i] == ';')) i++
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
            out.add(ShellSegment(words, script.depth, script.origin))
            words = ArrayList()
        }
    }

    /** The bodies of the here-documents opened on the line that just ended: they may be scripts (`bash <<EOF`). */
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
            nested(body.toString(), SegmentOrigin.EXECUTED)
        }
        heredocs.clear()
        if (i > n) i = n
    }

    private fun nested(text: String, origin: SegmentOrigin) {
        queue.add(PendingScript(text, script.depth + 1, origin))
    }
}
