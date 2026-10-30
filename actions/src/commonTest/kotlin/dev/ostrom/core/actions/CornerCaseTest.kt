// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The last corners of the readers: values left out, options given twice, words at the very end of a command. */
class CornerCaseTest {

    private val classifier = ActionClassifierImpl()

    private fun classify(command: String): Classification = classifier.classify(ToolSubject.shell(command))

    private fun words(command: String): List<String> = ShellParser.parse(command).segments.first().words

    @Test
    fun keysWithoutValueReadAsEmpty() {
        val config = GitConfig.parseListZ("remote.o.push\u0000url.x.insteadof\u0000")
        assertEquals(listOf(""), config.getAll("remote.o.push"))
        assertEquals(listOf("url.x.insteadof" to ""), config.matching("url.", ".insteadof"))
    }

    @Test
    fun globalOptionsOfGitWithoutValueOrTwice() {
        for (command in listOf("git -c", "git --git-dir", "git -C")) {
            assertEquals(emptyList(), classify(command).actions, command)
        }
        for (command in listOf(
            "git -c alias.a=x -c alias.b=y p origin main", "git --git-dir /a --work-tree /b push origin main",
            "git --exec-path=/x --git-dir=/y push origin main",
        )) {
            assertEquals(Refusal.REDIRECTED, classify(command).refusal, command)
        }
        assertNull(classify("git --super-prefix x/ push origin topic").refusal)
    }

    @Test
    fun theLexerAtTheEdgesOfItsInput() {
        assertFalse(ShellParser.parse("echo \$( (cd x) )").subshells, "a subshell inside a substitution moves nothing")
        assertEquals(listOf("\\!", "\u0004"), words("\$'\\!' \$'\\x4"))
        assertEquals(listOf("echo", "x"), words("echo x # a comment\necho y"))
        assertEquals(listOf("echo", ""), words("echo ''>f"))
    }

    @Test
    fun mergePathsThatOnlyLookLikeAPullRequest() {
        for (command in listOf(
            "gh api -X PUT repos/o/r/issues/3/pulls/1/merge", "gh api -X PUT 'repos/o/r/pulls/3/close?x=/merges'",
            "gh api -X PUT repos/o/merges", "gh api -X PUT repos/o/r/pulls/3/mergedx/pulls/4/merge",
            "gh pr merge https://github.com/o!/r/pull/9",
        )) {
            assertNull(classify(command).pullRequest, command)
        }
        val two = classify("gh api -X PUT repos/o/r/pulls/1/merge; gh api -X PUT repos/o/r/pulls/2/merge")
        assertEquals(PullRequestRef(1, "o", "r"), two.pullRequest)
        assertEquals(Refusal.SEVERAL_MERGES, two.refusal)
        assertEquals(Refusal.SEVERAL_MERGES, classify("gh pr merge 1 && gh pr merge 2 '").refusal)
    }

    @Test
    fun wrappersAndToolsWithNothingAfterThem() {
        assertEquals(emptyList(), classify("sudo -u ops").actions)
        assertNull(classify("sudo -u ops").refusal)
        assertEquals(emptyList(), classify("rails").actions)
        assertEquals(emptyList(), classify("bash -c 'ssh - host kubectl apply'").actions)
        assertFalse(braceExpansion("{a."))
        assertTrue(braceExpansion("{a,b.c}"))
    }

    @Test
    fun aPatternEndingWithABackslashIsRejected() {
        val classifier = ActionClassifierImpl(listOf(DeclaredAction("custom-e", patterns = listOf("a\\"))))
        assertEquals(listOf("a\\"), classifier.rejectedPatterns.map { it.pattern })
    }
}
