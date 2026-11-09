// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pushes read with the git state, ported from `audit-push.test.mjs`, `audit_push_refs.rs` (A9), `push-parity.test.mjs`
 * and P2 of `audit-gate.test.ts`. The repositories of those tests become [GitState] values: the CLI reads them with
 * git.
 */
class PushClassificationTest {

    private val classifier = ActionClassifierImpl()
    private val remote = "/tmp/ostrom-audit-push/core-remote.git"
    private val origin = arrayOf(
        "remote.origin.url" to remote,
        "remote.origin.fetch" to "+refs/heads/*:refs/remotes/origin/*",
    )
    private val onMain = GitState("main", GitConfig.of(*origin))
    private val head = "3f1c2a9e4b7d8c6f5a0e1d2c3b4a5f6e7d8c9b0a"

    private fun fixed(state: GitState?) = GitStateReader { state }

    private fun classify(command: String, state: GitState? = onMain): Classification =
        classifier.classify(ToolSubject.shell(command), fixed(state))

    private fun refs(command: String, state: GitState = onMain): List<PushTarget>? =
        classify(command, state).push?.targets?.refs

    private fun repository(command: String, state: GitState): String? =
        classify(command, state).push?.targets?.repository

    @Test
    fun aPushFromMainThatNeverNamesMainIsPushMain() {
        val classification = classify("git push origin HEAD")
        assertEquals(listOf("push-main"), classification.actions)
        assertNull(classification.refusal)
        assertEquals(listOf(PushTarget("HEAD", "main")), classification.push?.targets?.refs)
        assertEquals(
            PushBindResult.Bound(PushBinding("core-remote", listOf(BoundRef("main", head)), force = false)),
            classification.push?.targets?.bind { if (it == "HEAD") head else null },
        )
    }

    @Test
    fun aBarePushFromABranchWhoseUpstreamIsMainIsPushMain() {
        val topic = GitState(
            "topic",
            GitConfig.of(
                *origin,
                "branch.topic.remote" to "origin",
                "branch.topic.merge" to "refs/heads/main",
                "push.default" to "upstream",
            ),
        )
        val classification = classify("git push", topic)
        assertEquals(listOf("push-main"), classification.actions)
        assertEquals(listOf(PushTarget("HEAD", "main")), classification.push?.targets?.refs)
        assertEquals("origin", classification.push?.targets?.remote)
    }

    @Test
    fun aBarePushThatFollowsTheConfigIsRefused() {
        val matching = classify(
            "git push origin",
            GitState("topic", GitConfig.of(*origin, "push.default" to "matching")),
        )
        assertEquals(listOf("push-main"), matching.actions)
        assertEquals(Refusal.CONFIG_DRIVEN, matching.refusal)
        assertEquals("push.default=matching", matching.refusalDetail)
        assertNull(matching.push?.targets)
        val configured = GitState(
            "topic",
            GitConfig.of(*origin, "remote.origin.push" to "+refs/heads/topic:refs/heads/main"),
        )
        val refspec = classify("git push origin", configured)
        assertEquals(Refusal.CONFIG_DRIVEN, refspec.refusal)
        assertEquals("remote.origin.push", refspec.refusalDetail)
        // the audit's hole: a refspec without destination follows remote.<name>.push too
        assertEquals(Refusal.CONFIG_DRIVEN, classify("git push origin topic", configured).refusal)
        val mirror = classify(
            "git push",
            GitState("topic", GitConfig.parseListZ("remote.origin.url\n$remote\u0000remote.origin.mirror\u0000")),
        )
        assertEquals(Refusal.CONFIG_DRIVEN, mirror.refusal)
        assertEquals("remote.origin.mirror", mirror.refusalDetail)
    }

    @Test
    fun anOrdinaryFeaturePushPassesUnasked() {
        val classification = classify("git push origin topic", GitState("topic", GitConfig.of(*origin)))
        assertEquals(emptyList(), classification.actions)
        assertNull(classification.refusal)
        assertEquals(listOf(PushTarget("topic", "topic")), classification.push?.targets?.refs)
    }

    @Test
    fun theYesNamesWhereThePushReallyGoes() {
        val pushUrl = GitState(
            "main",
            GitConfig.of(*origin, "remote.origin.pushurl" to "/tmp/ostrom-audit-push/elsewhere.git"),
        )
        val targets = classify("git push origin main", pushUrl).push?.targets
        assertEquals("elsewhere", targets?.repository)
        assertEquals("/tmp/ostrom-audit-push/elsewhere.git", targets?.url)
        val rewritten = GitState(
            "main",
            GitConfig.of(
                "remote.origin.url" to "https://github.com/o/core.git",
                "url.git@github.com:evil/.pushInsteadOf" to "https://github.com/o/",
            ),
        )
        assertEquals("evil/core", classify("git push origin main", rewritten).push?.targets?.repository)
        assertEquals("o/core", repository("git push git@github.com:o/core.git main", GitState("main", GitConfig.EMPTY)))
    }

    @Test
    fun parityWithTheLauncherEveryWayToPushMainReadsTheSameRefs() {
        for (command in listOf(
            "git push origin main",
            "git push",
            "git push origin HEAD",
            "git push origin HEAD:main",
            "cd . && git push -u origin main",
            "git push origin main # current branch: main",
            "git -C . push origin +main:refs/heads/main",
        )) {
            val targets = classify(command).push?.targets
            assertEquals(1, targets?.refs?.size, command)
            assertEquals("main", targets?.refs?.single()?.destination, command)
            val bound = targets?.bind { head } as PushBindResult.Bound
            assertEquals(listOf(BoundRef("main", head)), bound.binding.refs, command)
            assertEquals("core-remote", bound.binding.repository, command)
        }
        assertEquals(true, classify("git -C . push origin +main:refs/heads/main").push?.force)
        assertEquals(listOf(PushTarget(null, "old")), refs("git push origin :old"))
        assertEquals(
            PushBindResult.Bound(PushBinding("core-remote", listOf(BoundRef("old", "")), force = false)),
            classify("git push origin :old").push?.targets?.bind { null },
        )
        assertEquals(listOf(PushTarget(null, "old")), refs("git push -d origin old"))
        assertEquals(listOf(PushTarget(null, "old")), refs("git push --delete origin refs/heads/old"))
        assertNull(classify("git status").push)
        assertEquals(Refusal.UNLISTABLE, classify("git push --all").refusal)
        assertEquals(
            PushBindResult.UnknownCommit("nope"),
            classify("git push origin nope").push?.targets?.bind { null },
        )
        assertEquals(Refusal.NOT_PLAIN, classify("bash -c 'git push origin main'").refusal)
        assertEquals(listOf(PushTarget("feature", "main")), refs("git push origin feature:main"))
    }

    @Test
    fun p2AForcePushIsBoundApartFromAPlainPushOfTheSameCommit() {
        val plain = classify("git push origin main").push?.targets?.bind { head }
        assertEquals(plain, classify("git push origin main 2>&1 | tail -f").push?.targets?.bind { head })
        for (command in listOf(
            "git push --force origin main",
            "git push -f origin main",
            "git push origin +main",
            "git push --force-with-lease origin main",
            "git push -uf origin main",
            "git push --force-if-includes origin main",
        )) {
            val classification = classify(command)
            assertEquals(true, classification.push?.force, command)
            val forced = classification.push?.targets?.bind { head }
            assertEquals(
                PushBindResult.Bound(PushBinding("core-remote", listOf(BoundRef("main", head)), force = true)),
                forced,
                command,
            )
            assertTrue(forced != plain, command)
        }
        assertEquals(false, classify("git push -u origin main").push?.force)
        assertEquals(false, classify("git push --follow-tags=false origin main").push?.force)
    }

    @Test
    fun aPushAfterAnotherCommandIsNotStandalone() {
        assertEquals(false, classify("git commit --allow-empty -m wip && git push origin main").push?.standalone)
        assertEquals(true, classify("cd /x && git push origin main").push?.standalone)
        assertEquals(true, classify("git push origin main && git push origin v1.0.0").push?.standalone)
        assertEquals(true, classify("git push origin main | tail -3").push?.standalone)
    }

    @Test
    fun theCommandNamesItsOwnDestinationsWithoutTheGitState() {
        val plan = classifier.classify(ToolSubject.shell("git push origin HEAD x:refs/heads/main y v1.0")).push
        assertEquals(listOf("main", "y", "v1.0"), plan?.explicitDestinations)
        assertNull(plan?.targets)
        assertEquals(
            listOf("push-main", "push-tag"),
            classifier.classify(ToolSubject.shell("git push origin HEAD x:refs/heads/main y v1.0")).actions,
        )
        // without the git state, a push that names no protected branch is not push-main: the CLI always gives the state
        assertEquals(emptyList(), classifier.classify(ToolSubject.shell("git push origin HEAD")).actions)
    }

    @Test
    fun theRemoteOfABarePushFollowsGitsPrecedence() {
        val config = arrayOf(
            "remote.a.url" to "https://github.com/o/a", "remote.b.url" to "https://github.com/o/b",
            "remote.c.url" to "https://github.com/o/c", "remote.origin.url" to "https://github.com/o/origin",
        )
        assertEquals("o/origin", classify("git push", GitState("t", GitConfig.of(*config))).push?.targets?.repository)
        assertEquals("o/c", repository("git push", GitState("t", GitConfig.of(*config, "branch.t.remote" to "c"))))
        assertEquals(
            "o/b",
            classify(
                "git push",
                GitState("t", GitConfig.of(*config, "branch.t.remote" to "c", "remote.pushDefault" to "b")),
            )
                .push?.targets?.repository,
        )
        assertEquals(
            "o/a",
            classify(
                "git push",
                GitState(
                    "t",
                    GitConfig.of(
                        *config, "branch.t.remote" to "c", "remote.pushDefault" to "b", "branch.t.pushRemote" to "a",
                    ),
                ),
            ).push?.targets?.repository,
        )
        assertEquals(
            "o/a",
            classify("git push --repo=a", GitState("t", GitConfig.of(*config))).push?.targets?.repository,
        )
        assertEquals(
            "o/b",
            classify("git push --repo b", GitState("t", GitConfig.of(*config))).push?.targets?.repository,
        )
        assertEquals(
            Refusal.DETACHED_HEAD,
            classify("git push", GitState(null, GitConfig.of(*config, "remote.pushDefault" to "b"))).refusal,
        )
    }

    @Test
    fun aBarePushLandsWhereItsModeSays() {
        val upstream = arrayOf(*origin, "branch.t.merge" to "refs/heads/release")
        assertEquals(listOf(PushTarget("HEAD", "release")), refs("git push", GitState("t", GitConfig.of(*upstream))))
        assertEquals(
            listOf(PushTarget("HEAD", "t")),
            refs("git push", GitState("t", GitConfig.of(*upstream, "push.default" to "current"))),
        )
        assertEquals(emptyList(), refs("git push", GitState("t", GitConfig.of(*upstream, "push.default" to "nothing"))))
        assertEquals(listOf(PushTarget("HEAD", "t")), refs("git push", GitState("t", GitConfig.of(*origin))))
    }

    @Test
    fun aPushThatCannotBeToldIsRefused() {
        val detached = GitState(null, GitConfig.of(*origin))
        assertEquals(Refusal.DETACHED_HEAD, classify("git push", detached).refusal)
        assertEquals(Refusal.DETACHED_HEAD, classify("git push origin HEAD", detached).refusal)
        assertEquals(Refusal.DETACHED_HEAD, classify("git push origin @", detached).refusal)
        assertEquals(listOf(PushTarget("@", "main")), refs("git push origin @"))
        assertEquals(listOf(PushTarget("main", "main")), refs("git push origin main", detached))
        assertEquals(Refusal.GIT_STATE_UNREADABLE, classify("git push origin main", null).refusal)
        val submodules = classify(
            "git push origin main",
            GitState("main", GitConfig.of(*origin, "push.recurseSubmodules" to "on-demand")),
        )
        assertEquals(Refusal.UNLISTABLE, submodules.refusal)
        assertEquals("push.recurseSubmodules", submodules.refusalDetail)
        assertEquals(Refusal.UNLISTABLE, classify("git push --recurse-submodules=only origin main").refusal)
        assertNull(classify("git push --recurse-submodules=check origin main").refusal)
        assertEquals(Refusal.UNLISTABLE, classify("git push origin 'refs/heads/*:refs/heads/*'").refusal)
        for (option in listOf("--mirror", "--branches", "--tags")) {
            assertEquals(Refusal.UNLISTABLE, classify("git push $option origin").refusal, option)
        }
        val several = GitState(
            "main",
            GitConfig.of(*origin, "remote.origin.pushurl" to "/a.git", "remote.origin.pushurl" to "/b.git"),
        )
        assertEquals(Refusal.SEVERAL_DESTINATIONS, classify("git push origin main", several).refusal)
        assertEquals("origin", classify("git push origin main", several).refusalDetail)
        val twoUrls = GitState("main", GitConfig.of("remote.origin.url" to "/a.git", "remote.origin.url" to "/b.git"))
        assertEquals(Refusal.SEVERAL_DESTINATIONS, classify("git push origin main", twoUrls).refusal)
    }

    @Test
    fun severalPushesInOneCommandMustGoToOnePlace() {
        val two = GitState("main", GitConfig.of(*origin, "remote.fork.url" to "https://github.com/me/core"))
        assertEquals(
            Refusal.SEVERAL_DESTINATIONS,
            classify("git push origin topic && git push fork topic", two).refusal,
        )
        val together = classify("git push origin topic && git push -f origin main", two)
        assertEquals(listOf(PushTarget("topic", "topic"), PushTarget("main", "main")), together.push?.targets?.refs)
        assertEquals(true, together.push?.targets?.force)
        assertEquals(listOf("push-main"), together.actions)
        assertEquals(
            Refusal.DETACHED_HEAD,
            classify("git push origin main; git push", GitState(null, GitConfig.of(*origin))).refusal,
        )
        val mirrored = GitState("main", GitConfig.of(*origin, "remote.x.mirror" to "true"))
        assertEquals(Refusal.CONFIG_DRIVEN, classify("git push origin main; git push --repo x", mirrored).refusal)
    }

    @Test
    fun theGitStateIsReadWhereThePushRuns() {
        val seen = ArrayList<List<String>>()
        val reader = GitStateReader { directories ->
            seen += directories
            onMain
        }
        classifier.classify(ToolSubject.shell("cd /work && cd core && git -C sub push origin main"), reader)
        classifier.classify(ToolSubject.shell("cd && git push origin main"), reader)
        assertEquals(listOf(listOf("/work", "core", "sub"), listOf("~")), seen)
    }

    @Test
    fun aPushThatIsNotPlainIsRefusedBeforeTheGitStateIsRead() {
        val never = GitStateReader { error("the git state is not read for a refused push") }
        for (command in listOf(
            "sudo git push origin main",
            "echo \$(git push origin main)",
            "git push origin \$BRANCH",
            "git push \$REMOTE main",
            "(cd /x && git push origin main)",
            "cd \$DIR && git push origin main",
            "cd - && git push origin main",
            "git -C \$(pwd) push origin main",
            "bash <<EOF\ngit push origin main\nEOF",
            "nohup git push origin main",
        )) {
            val classification = classifier.classify(ToolSubject.shell(command), never)
            assertEquals(Refusal.NOT_PLAIN, classification.refusal, command)
            assertEquals(listOf("push-main"), classification.actions, command)
        }
        assertNull(classifier.classify(ToolSubject.shell("x=1 env git push origin topic | cat")).refusal)
        // assignments and keywords before git keep a push plain
        assertNull(classifier.classify(ToolSubject.shell("LANG=C git push origin topic")).refusal)
        assertNull(classifier.classify(ToolSubject.shell("if true; then git push origin topic; fi")).refusal)
    }

    @Test
    fun aMalformedCommandIsAlsoReadWithoutItsQuotesAndItsPushesAreNotPlain() {
        val unterminated = classify("echo 'x && git push origin main")
        assertEquals(listOf("push-main"), unterminated.actions)
        assertEquals(Refusal.NOT_PLAIN, unterminated.refusal)
        assertEquals(listOf("deploy"), classify("echo \"\$(kubectl apply -f a.yaml\"").actions)
        assertEquals(listOf("deploy"), classify("echo \"a\" 'b && helm upgrade x ./c").actions)
    }

    @Test
    fun theNoteOfWherePushesLandIsAddedToTheTextOfDeclaredPatterns() {
        val ship = DeclaredAction("custom-ship", "Ship", patterns = listOf("git push to: .*\\bmain\\b"))
        val custom = ActionClassifierImpl(listOf(ship))
        assertEquals(
            listOf("push-main", "custom-ship"),
            custom.classify(ToolSubject.shell("git push origin HEAD"), fixed(onMain)).actions,
        )
        assertEquals(emptyList(), custom.classify(ToolSubject.shell("git push origin HEAD")).actions)
        assertEquals(
            listOf("custom-ship"),
            ActionClassifierImpl(listOf(DeclaredAction("custom-ship", patterns = listOf("git push to: topic, other"))))
                .classify(ToolSubject.shell("git push origin topic other"), fixed(onMain)).actions,
        )
    }
}
