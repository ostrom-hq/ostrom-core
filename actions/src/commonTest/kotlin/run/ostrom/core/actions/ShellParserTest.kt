// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellParserTest {

    private fun top(text: String): List<List<String>> =
        ShellParser.parse(text).segments.filter { it.depth == 0 }.map { it.words }

    private fun nested(text: String): List<Triple<List<String>, Int, SegmentOrigin>> =
        ShellParser.parse(text).segments.filter { it.depth > 0 }.map { Triple(it.words, it.depth, it.origin) }

    @Test
    fun segmentsCutAtSeparatorsButNotInQuotesOrRedirectionsAsTheRustLauncher() {
        assertEquals(
            listOf(listOf("git", "push", "origin", "main"), listOf("tail", "-15")),
            top("git push origin main 2>&1 | tail -15"),
        )
        assertEquals(
            listOf(listOf("git", "commit", "-m", "a && b"), listOf("git", "push")),
            top("git commit -m 'a && b' && git push"),
        )
        assertEquals(listOf(listOf("cd", "/x"), listOf("git", "push")), top("cd /x && git push # current branch: main"))
    }

    @Test
    fun everySeparatorEndsACommand() {
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"), listOf("e"), listOf("f"), listOf("g")),
            top("a; b | c & d || e && (f) |& g ;;"),
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), top("a\nb\r\nc\t"))
    }

    @Test
    fun quotesAndEscapesJoinIntoOneWord() {
        assertEquals(listOf(listOf("git", "push")), top("\"g\"'i't push"))
        assertEquals(listOf(listOf("echo", "a b", "a\"b", "\$HOME", "x")), top("echo a\\ b \"a\\\"b\" \"\$HOME\" x\\"))
        assertEquals(listOf(listOf("git", "push", "origin", "main")), top("git push \\\norigin main"))
        assertEquals(listOf(listOf("echo", "a#b", "\$x")), top("echo a#b \$x # a comment"))
        assertEquals(emptyList(), top("# only a comment"))
    }

    @Test
    fun redirectionsAndTheirTargetsAreNotWords() {
        assertEquals(
            listOf(listOf("echo", "hi"), listOf("cat")),
            top("echo hi>out.txt; cat < in.txt >> log 2> err &> all"),
        )
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"), listOf("e")),
            top("a >| f; b <> g; c <&-; d >&out; e 2>&1"),
        )
        assertEquals(listOf(listOf("cat")), top("cat <"))
        assertEquals(listOf(listOf("a")), top("a >&"))
        assertEquals(listOf(listOf("echo", "")), top("echo ''>f"))
    }

    @Test
    fun hereDocumentBodiesAreParsedAsScripts() {
        val text = "cat > f.sh <<'EOF'\ngit push origin main\nEOF\necho done"
        assertEquals(listOf(listOf("cat"), listOf("echo", "done")), top(text))
        assertEquals(listOf(Triple(listOf("git", "push", "origin", "main"), 1, SegmentOrigin.EXECUTED)), nested(text))
        assertFalse(ShellParser.parse(text).malformed)
        assertEquals(
            listOf(Triple(listOf("git", "push"), 1, SegmentOrigin.EXECUTED)),
            nested("bash <<-EOF\n\tgit push\n\tEOF\n"),
        )
        assertEquals(
            listOf(Triple(listOf("a"), 1, SegmentOrigin.EXECUTED), Triple(listOf("b"), 1, SegmentOrigin.EXECUTED)),
            nested("cat <<A <<B\na\nA\nb\nB\n"),
        )
        assertEquals(listOf(Triple(listOf("x"), 1, SegmentOrigin.EXECUTED)), nested("cat <<EOF\nx\nEOF"))
        assertEquals(listOf(listOf("cat")), top("cat <<"))
    }

    @Test
    fun aHereStringIsParsedAsAQuotedScript() {
        assertEquals(listOf(listOf("bash")), top("bash <<< \"git push origin main\""))
        assertEquals(
            listOf(Triple(listOf("git", "push", "origin", "main"), 1, SegmentOrigin.QUOTED)),
            nested("bash <<< \"git push origin main\""),
        )
    }

    @Test
    fun substitutionsAreParsedOneLevelDeeper() {
        assertEquals(listOf(listOf("echo", "\$(git rev-parse HEAD)")), top("echo \"\$(git rev-parse HEAD)\""))
        val revParse = Triple(listOf("git", "rev-parse", "HEAD"), 1, SegmentOrigin.EXECUTED)
        assertTrue(revParse in nested("echo \"\$(git rev-parse HEAD)\""))
        assertEquals(
            listOf(Triple(listOf("git", "push"), 1, SegmentOrigin.EXECUTED)),
            nested("echo `git push`").take(1),
        )
        assertEquals(listOf(Triple(listOf("a`b"), 1, SegmentOrigin.EXECUTED)), nested("x `a\\`b`"))
        val show = Triple(listOf("git", "show", "a"), 1, SegmentOrigin.EXECUTED)
        assertTrue(show in nested("diff <(git show a) >(tee b)"))
        assertTrue(Triple(listOf("x:-\$(git push)"), 1, SegmentOrigin.EXECUTED) in nested("echo \${x:-\$(git push)}"))
        assertTrue(Triple(listOf("git", "push"), 2, SegmentOrigin.EXECUTED) in nested("echo \${x:-\$(git push)}"))
        assertTrue(Triple(listOf("echo", "a b"), 1, SegmentOrigin.EXECUTED) in nested("x \"`echo 'a b'`\" \"\${y}\""))
        val inner = nested("echo \$(echo ')' \"x)y\" \\) (a) \"q\\\"r\")")
        assertEquals(Triple(listOf("echo", ")", "x)y", ")"), 1, SegmentOrigin.EXECUTED), inner[0])
        assertFalse(ShellParser.parse("echo \$(echo ')' \"x)y\" \\) (a))").malformed)
    }

    @Test
    fun aQuotedWordWithSpacesIsParsedAsAQuotedScript() {
        assertEquals(
            listOf(Triple(listOf("a"), 1, SegmentOrigin.QUOTED), Triple(listOf("b"), 1, SegmentOrigin.QUOTED)),
            nested("git commit -m 'a && b'"),
        )
        assertEquals(
            listOf(Triple(listOf("git", "push", "origin", "main"), 1, SegmentOrigin.QUOTED)),
            nested("bash -c 'git push origin main'"),
        )
        assertEquals(listOf(Triple(listOf("a", "b"), 1, SegmentOrigin.QUOTED)), nested("'a b'"))
        // a substitution inside a quoted script is parsed once, as itself: the script view holds `$_` in its place
        assertEquals(
            listOf(
                Triple(listOf("git", "push"), 1, SegmentOrigin.EXECUTED),
                Triple(listOf("echo", "\$_"), 1, SegmentOrigin.QUOTED),
            ),
            nested("x \"echo \$(git push)\""),
        )
    }

    @Test
    fun anUnterminatedQuoteSubstitutionOrHereDocumentIsMalformed() {
        for (text in listOf(
            "echo 'abc", "echo \"abc", "echo \"abc\\", "echo \$(git push", "echo \${x", "echo `git push",
            "cat <<EOF\nline",
            "cat <<EOF", "echo \$(echo 'abc)",
        )) {
            assertTrue(ShellParser.parse(text).malformed, text)
        }
        assertEquals(listOf(listOf("echo", "abc")), top("echo 'abc"))
        assertTrue(Triple(listOf("git", "push"), 1, SegmentOrigin.EXECUTED) in nested("echo \$(git push"))
        assertTrue(Triple(listOf("git", "push"), 1, SegmentOrigin.EXECUTED) in nested("echo `git push"))
    }

    @Test
    fun nestingDeeperThanTheLimitIsMalformed() {
        assertFalse(ShellParser.parse("a \$(b \$(c \$(d \$(e))))").malformed)
        assertTrue(ShellParser.parse("a \$(b \$(c \$(d \$(e \$(f)))))").malformed)
        assertEquals(4, ShellParser.MAX_DEPTH)
    }

    @Test
    fun parenthesesOfTheCommandItselfAreSubshells() {
        assertTrue(ShellParser.parse("(cd x && git push)").subshells)
        assertTrue(ShellParser.parse("a) b").subshells)
        assertFalse(ShellParser.parse("bash -c '(cd x; ls)'").subshells)
        assertFalse(ShellParser.parse("cd x && git push").subshells)
    }
}
