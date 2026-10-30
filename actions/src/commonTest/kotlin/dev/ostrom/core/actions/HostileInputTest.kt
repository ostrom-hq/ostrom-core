// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Commands written to stall the classifier or to slip past it. */
class HostileInputTest {

    private val classifier = ActionClassifierImpl()

    @Test
    fun aCommandLongerThanTheBoundIsNotReadAndFallsUnderEveryClassOfItsKind() {
        val huge = "echo " + "a".repeat(ActionClassifier.MAX_COMMAND_LENGTH)
        val classification = classifier.classify(ToolSubject.shell(huge))
        assertEquals(
            listOf("push-main", "push-tag", "merge", "deploy", "migration", "mobile-release"),
            classification.actions,
        )
        assertEquals(Refusal.TOO_LARGE, classification.refusal)
        val path = "/" + "a".repeat(ActionClassifier.MAX_COMMAND_LENGTH)
        val write = classifier.classify(ToolSubject(SubjectKind.WRITE, "Write", path))
        assertEquals(listOf("migration"), write.actions)
    }

    @Test
    fun aCommandAtTheBoundIsReadToTheEnd() {
        val filler = "echo x; "
        val padding = filler.repeat((ActionClassifier.MAX_COMMAND_LENGTH - 40) / filler.length)
        val classification = classifier.classify(ToolSubject.shell(padding + "git push origin main"))
        assertEquals(listOf("push-main"), classification.actions)
        assertTrue(padding.length + 20 <= ActionClassifier.MAX_COMMAND_LENGTH)
    }

    @Test
    fun repetitiveCommandsStayLinear() {
        val bound = ActionClassifier.MAX_COMMAND_LENGTH
        for (unit in listOf(
            "vercel ", "gh -a ", "helm -x ", "gh pr merge ", "gh api ", "git push; ", "git git ", "\$(", "`", "\${",
            "'a b' ",
            "\"\$(x) y\" ", "<<A ", "cat <<A\n", "kubectl -n ", "\\", "((", "))", "#", "\n", "a=1 ", "sudo ",
        )) {
            val command = unit.repeat(bound / unit.length)
            val classification = classifier.classify(ToolSubject.shell(command))
            assertTrue(classification.actions.size <= 7, unit)
        }
    }

    @Test
    fun aVerbHiddenBehindManyOptionsIsAssumed() {
        val options = (1..MAX_SCAN + 1).joinToString(" ") { "-o$it" }
        assertEquals(listOf("deploy"), classifier.classify(ToolSubject.shell("helm $options list")).actions)
        assertEquals(listOf("deploy"), classifier.classify(ToolSubject.shell("vercel $options")).actions)
        assertEquals(emptyList(), classifier.classify(ToolSubject.shell("helm -o1 list")).actions)
    }

    @Test
    fun tooManyPushesInOneCommandAreRefused() {
        val command = (1..20).joinToString(" && ") { "git push origin topic$it" }
        val classification = classifier.classify(ToolSubject.shell(command), GitStateReader { error("not read") })
        assertEquals(Refusal.NOT_PLAIN, classification.refusal)
        assertEquals(listOf("push-main"), classification.actions)
        assertEquals(16, classification.push?.explicitDestinations?.size)
    }

    @Test
    fun deepNestingIsMalformedAndPushesInsideAreStillFound() {
        val deep = "echo " + "\$(".repeat(5_000) + "git push origin main" + ")".repeat(5_000)
        val script = ShellParser.parse(deep)
        assertTrue(script.malformed)
        val shallow = "echo \$(echo \$(echo \$(git push origin main)))"
        assertEquals(listOf("push-main"), classifier.classify(ToolSubject.shell(shallow)).actions)
        assertEquals(Refusal.NOT_PLAIN, classifier.classify(ToolSubject.shell(shallow)).refusal)
        // deeper than the parser reads: the quote-blind reading still sees the push; `$($(…))` runs what the inner
        // command prints, a program named at run time, so every class of the shell applies
        val classification = classifier.classify(ToolSubject.shell(deep))
        assertEquals(
            listOf("push-main", "push-tag", "merge", "deploy", "migration", "mobile-release"),
            classification.actions,
        )
        assertEquals(Refusal.NOT_PLAIN, classification.refusal)
    }

    @Test
    fun aDeclaredPatternThatBacktracksStopsAtItsBudget() {
        val slow = ActionClassifierImpl(
            listOf(
                DeclaredAction("custom-slow", patterns = listOf(".*a.*a.*a.*b")),
                DeclaredAction("custom-next", patterns = listOf("never-there")),
                DeclaredAction("custom-mcp", tools = listOf("mcp"), patterns = listOf("never-there")),
            ),
        )
        val classification = slow.classify(ToolSubject.shell("echo " + "a".repeat(10_000)))
        assertEquals(listOf("custom-slow", "custom-next"), classification.actions)
        assertEquals(Refusal.TOO_LARGE, classification.refusal)
        val fine = slow.classify(ToolSubject.shell("echo aaab"))
        assertEquals(listOf("custom-slow"), fine.actions)
        assertNull(fine.refusal)
    }

    @Test
    fun aCommandTooLongForDeclaredPatternsFallsUnderEveryCustomClassOfItsKind() {
        val custom = ActionClassifierImpl(
            listOf(
                DeclaredAction("custom-a", patterns = listOf("never-there")),
                DeclaredAction("custom-b", tools = listOf("Write"), patterns = listOf("never-there")),
            ),
        )
        val long = "echo " + "x".repeat(ActionClassifier.MAX_PATTERN_TEXT)
        val classification = custom.classify(ToolSubject.shell(long))
        assertEquals(listOf("custom-a"), classification.actions)
        assertEquals(Refusal.TOO_LARGE, classification.refusal)
        // a refusal of the push comes first
        val pushCommand = "sudo git push origin main; echo " + "x".repeat(ActionClassifier.MAX_PATTERN_TEXT)
        val push = custom.classify(ToolSubject.shell(pushCommand))
        assertEquals(Refusal.NOT_PLAIN, push.refusal)
        assertEquals(listOf("push-main", "custom-a"), push.actions)
    }

    @Test
    fun theBudgetedTextReadsLikeItsText() {
        val text = BudgetedText("abc", 2)
        assertEquals(3, text.length)
        assertEquals("bc", text.subSequence(1, 3).toString())
        assertEquals("abc", text.toString())
        assertEquals('a', text[0])
        assertEquals('b', text[1])
        assertEquals(false, text.exhausted)
        assertEquals("the pattern read the text too many times", runCatching { text[2] }.exceptionOrNull()?.message)
        assertEquals(true, text.exhausted)
    }
}
