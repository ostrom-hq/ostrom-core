package dev.ostrom.core.actions

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The actions an organization declares (`custom-<slug>`), and the screen their patterns pass. */
class DeclaredActionTest {

    @Test
    fun customActionsMatchTheirPatternsWithoutCaseAfterTheBuiltInOnes() {
        val classifier = ActionClassifierImpl(
            listOf(
                DeclaredAction(
                    "custom-release-notes",
                    "Publish release notes",
                    patterns = listOf("\\bnpm run release\\b"),
                ),
                DeclaredAction("custom-seed", "Seed the database", listOf("Bash"), listOf("seed\\.sh\\s+prod")),
            ),
        )
        assertEquals(
            listOf("push-main", "custom-release-notes", "custom-seed"),
            classifier.classify(ToolSubject.shell("NPM RUN RELEASE && ./seed.sh prod && git push origin main")).actions,
        )
        assertEquals(emptyList(), classifier.classify(ToolSubject.shell("npm run released")).actions)
        assertEquals(
            listOf(
                "push-main",
                "push-tag",
                "merge",
                "deploy",
                "migration",
                "mobile-release",
                "client-message",
                "custom-release-notes",
                "custom-seed",
            ),
            classifier.catalogue.map { it.id },
        )
        assertEquals(
            ActionClass("custom-seed", "Seed the database", setOf(SubjectKind.SHELL), emptyList()),
            classifier.catalogue.last(),
        )
    }

    @Test
    fun customActionsApplyToTheKindsOfTheirTools() {
        val classifier = ActionClassifierImpl(
            listOf(
                DeclaredAction(
                    "custom-infra",
                    "Infra files",
                    listOf("Write", "Edit", "MultiEdit", "Bash"),
                    listOf("^/r/infra/"),
                ),
                DeclaredAction("custom-stripe", "Stripe", listOf("mcp"), listOf("^mcp__stripe__create_")),
                DeclaredAction("custom-nothing", "Unknown tools", listOf("Notebook"), listOf("x")),
            ),
        )
        val write = ToolSubject.of("Edit", buildJsonObject { put("file_path", JsonPrimitive("/r/infra/main.tf")) })
        assertEquals(listOf("custom-infra"), classifier.classify(write).actions)
        assertEquals(
            listOf("custom-stripe"),
            classifier.classify(ToolSubject.of("mcp__Stripe__create_refund", buildJsonObject { })).actions,
        )
        assertEquals(
            emptyList(),
            classifier.classify(ToolSubject.of("mcp__stripe__list_refunds", buildJsonObject { })).actions,
        )
        assertEquals(emptyList(), classifier.classify(ToolSubject.shell("x")).actions)
        assertEquals(
            setOf(SubjectKind.WRITE, SubjectKind.SHELL),
            classifier.catalogue.first { it.id == "custom-infra" }.kinds,
        )
        assertEquals(emptySet(), classifier.catalogue.first { it.id == "custom-nothing" }.kinds)
    }

    @Test
    fun patternsDeclaredForABuiltInClassAddToItsDetection() {
        val classifier = ActionClassifierImpl(
            listOf(
                DeclaredAction("deploy", patterns = listOf("\\bmake prod\\b")),
                DeclaredAction("deploy", patterns = listOf("\\bansible-playbook\\b.*site\\.yml")),
            ),
        )
        assertEquals(listOf("deploy"), classifier.classify(ToolSubject.shell("make prod")).actions)
        assertEquals(
            listOf("deploy"),
            classifier.classify(ToolSubject.shell("ansible-playbook -i hosts site.yml")).actions,
        )
        // the parser still sees what no pattern names
        assertEquals(
            listOf("deploy"),
            classifier.classify(ToolSubject.shell("kubectl -n prod apply -f a.yaml")).actions,
        )
        assertEquals(BuiltInActions.all, classifier.catalogue)
    }

    @Test
    fun aDuplicateCustomDeclarationMergesItsPatterns() {
        val classifier = ActionClassifierImpl(
            listOf(
                DeclaredAction("custom-a", "First label", patterns = listOf("alpha")),
                DeclaredAction("custom-a", "Second label", listOf("mcp"), listOf("beta")),
            ),
        )
        assertEquals("First label", classifier.catalogue.last().label)
        assertEquals(listOf("custom-a"), classifier.classify(ToolSubject.shell("echo beta")).actions)
        assertEquals(1, classifier.catalogue.count { it.id == "custom-a" })
    }

    @Test
    fun unsafeOrInvalidPatternsAreRejectedWithTheirReason() {
        val tooMany = (1..PatternSafety.MAX_PER_CLASS + 1).map { "p$it" }
        val classifier = ActionClassifierImpl(
            listOf(
                DeclaredAction("custom-bad", patterns = listOf("(a+)+$", "(", "[", "ok")),
                DeclaredAction("custom-many", patterns = tooMany),
                DeclaredAction("custom-empty", patterns = listOf("")),
            ),
        )
        assertEquals(
            listOf(
                RejectedPattern(
                    "custom-bad",
                    "(a+)+$",
                    "a repeated group that repeats or alternates inside can backtrack without end",
                ),
                RejectedPattern("custom-bad", "(", "unbalanced parenthesis"),
                RejectedPattern("custom-bad", "[", "not a valid regular expression"),
                RejectedPattern("custom-many", "p33", "more than 32 patterns for one class"),
                RejectedPattern("custom-empty", "", "a pattern has 1 to 512 characters"),
            ),
            classifier.rejectedPatterns,
        )
        assertEquals(listOf("custom-bad"), classifier.classify(ToolSubject.shell("ok")).actions)
        assertEquals(listOf("custom-many"), classifier.classify(ToolSubject.shell("p32")).actions)
        // a class whose patterns were all refused never matches, and stays in the catalogue for the console
        assertEquals(emptyList(), classifier.classify(ToolSubject.shell("anything")).actions)
        assertEquals("custom-empty", classifier.catalogue.last().id)
    }

    @Test
    fun theScreenRefusesTheShapesThatBacktrackWithoutEnd() {
        for (pattern in listOf(
            "(a+)+", "(a*)*", "(a|ab)*", "(x*){2,}", "(?:a|b)+c", "((a+))*", "(a{2,})+", "(.*a){3,5}", "(a|b){0,10}",
        )) {
            assertEquals(
                "a repeated group that repeats or alternates inside can backtrack without end",
                PatternSafety.problem(pattern),
                pattern,
            )
        }
        for (pattern in listOf("(a)\\1", "(?<n>a)\\k<n>", "\\9")) {
            assertEquals("back-references are not allowed", PatternSafety.problem(pattern), pattern)
        }
        assertEquals("unbalanced parenthesis", PatternSafety.problem(")"))
        assertEquals("unbalanced parenthesis", PatternSafety.problem("(a"))
        assertEquals("unbalanced brace", PatternSafety.problem("a{2"))
        assertEquals("a pattern has 1 to 512 characters", PatternSafety.problem("a".repeat(513)))
        for (pattern in listOf(
            "\\bgit\\s+push\\b[^\\n;&|]*\\b(main|master)\\b", "(ab)+", "(a|b)", "(a|b)?", "(a|b){1}", "(a+){0,1}",
            "(a+){1,1}", "[(a+)+]", "[]()+]", "[^]a]+", "\\(a+\\)+", "a.*b.*c", "(?i)x", "\\w+\\s\\d", "a".repeat(512),
            "x\\",
        )) {
            assertNull(PatternSafety.problem(pattern), pattern)
        }
    }
}
