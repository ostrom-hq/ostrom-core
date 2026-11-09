// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pull request a merge command targets, ported from `prOfCommand` of `approval.test.ts`. */
class PullRequestTest {

    private val classifier = ActionClassifierImpl()

    private fun pr(command: String): PullRequestRef? = classifier.classify(ToolSubject.shell(command)).pullRequest

    @Test
    fun theCasesOfThePrototype() {
        assertEquals(PullRequestRef(3), pr("gh pr merge 3 --merge"))
        assertEquals(PullRequestRef(5, "ostrom-hq", "gate-demo"), pr("gh pr merge 5 -R ostrom-hq/gate-demo --merge"))
        assertEquals(
            PullRequestRef(9, "ostrom-hq", "gate-demo"),
            pr("gh pr merge https://github.com/ostrom-hq/gate-demo/pull/9"),
        )
        assertEquals(PullRequestRef(4, "o", "r"), pr("gh api -X PUT repos/o/r/pulls/4/merge"))
        assertNull(pr("gh pr merge --merge"), "no number: the PR of the current branch, unknown here")
        assertEquals(PullRequestRef(6), pr("gh pr merge --body 3 6"), "an option value is not the PR number")
        assertNull(pr("git push origin main"))
    }

    @Test
    fun aHashBeforeTheNumberIsReadAsTheShellReadsIt() {
        // The prototype read `gh pr merge --squash #12` as #12; the shell reads `#12` as a comment, and gh merges the
        // pull request of the current branch. Quoted, `#12` reaches gh.
        assertNull(pr("gh pr merge --squash #12"))
        assertEquals(PullRequestRef(12), pr("gh pr merge --squash '#12'"))
        assertEquals(PullRequestRef(12), pr("gh pr merge --squash a#12 '#12'"))
    }

    @Test
    fun theRepositoryOptionInEveryForm() {
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh pr merge 7 --repo o/r"))
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh pr merge 7 --repo=o/r"))
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh pr merge 7 -Ro/r"))
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh -R github.com/o/r pr merge 7"))
        assertEquals(PullRequestRef(7, "o", "r"), pr("gh pr merge -R o/r -R x/y 7"))
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -R not-a-repo"))
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -R a/b/c/d"))
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -R 'o/r r'"))
        assertEquals(PullRequestRef(7), pr("gh pr merge 7 -t 8 --subject=9 -b"))
    }

    @Test
    fun theUrlWinsOverANumberAndTheApiOverTheCli() {
        assertEquals(PullRequestRef(9, "o", "r"), pr("gh pr merge 3 https://github.com/o/r/pull/9/files"))
        assertEquals(PullRequestRef(4, "o", "r"), pr("gh pr merge 3 && gh api -X PUT repos/o/r/pulls/4/merge"))
        assertEquals(PullRequestRef(3), pr("gh pr merge 3 && gh pr merge 5"))
        assertEquals(PullRequestRef(2, "o", "r"), pr("curl -X PUT https://api.github.com/repos/o/r/pulls/2/merge"))
    }

    @Test
    fun malformedReferencesAreNotPullRequests() {
        for (command in listOf(
            "gh pr merge https://github.com/o/r/issues/9",
            "gh pr merge https://github.com/o/r/pull/x",
            "gh pr merge https://github.com/o r/r/pull/9",
            "gh pr merge https://github.com/o",
            "gh pr merge 99999999999",
            "gh api -X PUT repos/o/r/pulls/x/merge",
            "gh api -X PUT repos/o/r/issues/3/merge",
            "gh api -X PUT repos/o/r/pulls/3/merged",
            "gh api -X PUT repos/o!/r/pulls/3/merge",
            "gh api -X PUT repos/o/r!/pulls/3/merge",
            "gh api -X PUT repos/o/r/pulls",
            "gh api repos/o/r/merges",
        )) {
            assertNull(pr(command), command)
        }
    }
}
