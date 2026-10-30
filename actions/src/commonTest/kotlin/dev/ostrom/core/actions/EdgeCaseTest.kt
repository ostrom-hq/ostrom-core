package dev.ostrom.core.actions

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The corners of each reader: every one of them is a way an agent could write a command. */
class EdgeCaseTest {

    private val classifier = ActionClassifierImpl()
    private val onMain = GitState("main", GitConfig.of("remote.origin.url" to "https://github.com/o/core"))

    private fun classify(command: String, state: GitState? = null): Classification =
        classifier.classify(ToolSubject.shell(command), state?.let { s -> GitStateReader { s } })

    private fun classes(command: String): List<String> = classify(command).actions

    private fun pr(command: String): PullRequestRef? = classify(command).pullRequest

    @Test
    fun severalRedirectingOptionsKeepTheFirst() {
        val command = "git --git-dir=/a -c remote.x.url=y --work-tree /b --namespace=n -c url.a.insteadOf=b push origin x"
        assertEquals(Refusal.REDIRECTED, classify(command).refusal)
    }

    @Test
    fun aPushBuiltFromABacktickIsNotPlain() {
        assertEquals(Refusal.NOT_PLAIN, classify("git push origin `cat branch`").refusal)
    }

    @Test
    fun unlistableOptionsTogetherStillCountTags() {
        val classification = classify("git push --tags --mirror origin")
        assertEquals(Refusal.UNLISTABLE, classification.refusal)
        assertEquals(listOf("push-main", "push-tag"), classification.actions)
    }

    @Test
    fun aSubmoduleSettingThatPushesNothingElseIsFine() {
        val state = GitState("main", GitConfig.of("remote.origin.url" to "/r.git", "push.recurseSubmodules" to "check"))
        assertNull(classify("git push origin main", state).refusal)
    }

    @Test
    fun twoSourcesForOneRefAreSortedByCommit() {
        val targets = classify("git push origin b:main a:main", onMain).push?.targets
        assertEquals(
            PushBindResult.Bound(PushBinding("o/core", listOf(BoundRef("main", "1"), BoundRef("main", "2")), false)),
            targets?.bind { if (it == "a") "1" else "2" },
        )
    }

    @Test
    fun aForcedFirstPushForcesTheWhole() {
        val two = classify("git push -f origin topic && git push origin main", onMain)
        assertEquals(true, two.push?.targets?.force)
        assertEquals(true, two.push?.force)
    }

    @Test
    fun aQuotedWrapperWithoutCommandIsNothing() {
        assertEquals(emptyList(), classes("echo 'sudo -u ops'"))
        assertEquals(emptyList(), classes("echo 'a=1 b=2'"))
        assertEquals(emptyList(), classes("echo 'sudo - helm upgrade x'"))
        assertEquals(listOf("deploy"), classes("bash -c 'if true; then helm upgrade x ./c; fi'"))
        assertEquals(listOf("deploy"), classes("bash -c 'sudo --user=ops kubectl apply -f a'"))
        assertEquals(listOf("deploy"), classes("bash -c 'timeout 30s helm upgrade x ./c'"))
        assertEquals(emptyList(), classes("bash -c '-x helm upgrade x'"))
    }

    @Test
    fun aSubshellWithoutCdKeepsAPushPlainAndPushdMoves() {
        assertNull(classify("(git push origin topic)").refusal)
        val seen = ArrayList<List<String>>()
        classifier.classify(ToolSubject.shell("pushd /x && git push origin main")) { dirs ->
            seen += dirs
            onMain
        }
        assertEquals(listOf(listOf("/x")), seen)
    }

    @Test
    fun scannersStopOnALoneDashAndReadAssignedOptions() {
        assertEquals(emptyList(), classes("kubectl - apply"))
        assertEquals(listOf("deploy"), classes("kubectl --context=prod apply -f a"))
        assertEquals(emptyList(), classes("eas"))
        assertEquals(listOf("migration"), classes("flyway premigrate,migrate"))
    }

    @Test
    fun theApiAfterOptionsOfGhIsRead() {
        assertEquals(PullRequestRef(1, "o", "r"), pr("gh api -X PUT https://api.github.com/repos/o/r/pulls/1/merge"))
        assertEquals(PullRequestRef(4, "o", "r"), pr("gh api -X PUT repos/o/r/pulls/4/merge -f merge_method=squash"))
    }

    @Test
    fun mergeArgumentsInTheirOddForms() {
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -R"))
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh pr merge 7 -R o/r -Rx/y"))
        assertEquals(PullRequestRef(9, "o", "r"), pr("gh pr merge https://github.com/o/r/pull/9 --squash"))
        assertNull(pr("gh pr merge '#'"))
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -R '/r'"))
        assertEquals(PullRequestRef(3), pr("gh pr merge 3 && gh pr merge 5 -R o/r"))
    }

    @Test
    fun malformedApiAndUrlReferences() {
        for (command in listOf(
            "gh api -X PUT repos//r/pulls/3/merge",
            "gh api -X PUT repos/o/r/pulls/3/close",
            "gh api -X PUT repos/o/r/pulls/99999999999/merge",
            "gh pr merge https://github.com/o/r!/pull/9",
            "curl https://api.github.com/repos/o/r/pulls/x/merge",
        )) {
            assertNull(pr(command), command)
        }
    }

    @Test
    fun theScreenAcceptsGroupsAfterARepeatOrAnAlternative() {
        for (pattern in listOf("a+(b)", "a|(b)", "[\\]]+")) assertNull(PatternSafety.problem(pattern), pattern)
    }

    @Test
    fun aCommandGivenAsAnObjectIsEmpty() {
        val subject = ToolSubject.of("Bash", buildJsonObject { put("command", buildJsonObject { }) })
        assertEquals(ToolSubject(SubjectKind.SHELL, "Bash", ""), subject)
    }
}
