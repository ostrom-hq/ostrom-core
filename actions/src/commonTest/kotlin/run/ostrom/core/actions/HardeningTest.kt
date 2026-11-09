// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ways around the gate a correctness review found, each closed. */
class HardeningTest {

    private val classifier = ActionClassifierImpl()
    private val onMain = GitState("main", GitConfig.of("remote.origin.url" to "https://github.com/o/core"))
    private val everyShellClass = listOf("push-main", "push-tag", "merge", "deploy", "migration", "mobile-release")

    private fun classify(command: String, state: GitState? = null): Classification =
        classifier.classify(ToolSubject.shell(command), state?.let { s -> GitStateReader { s } })

    private fun classes(command: String): List<String> = classify(command).actions

    @Test
    fun abbreviatedPushOptionsReadAsGitReadsThem() {
        assertEquals(Refusal.UNLISTABLE, classify("git push --al origin").refusal)
        assertEquals(Refusal.UNLISTABLE, classify("git push --mir origin").refusal)
        assertEquals(listOf("push-main", "push-tag"), classes("git push --tag origin"))
        assertEquals(listOf("push-main"), classes("git push --del origin main"))
        assertEquals(true, classify("git push --force-w origin topic").push?.force)
        // `--forc` is `--force`, `--force-with-lease` or `--force-if-includes`: git refuses it, Ostrom too
        assertEquals(Refusal.NOT_PLAIN, classify("git push --forc origin topic").refusal)
        assertEquals(Refusal.NOT_PLAIN, classify("git push --bogus origin topic").refusal)
        assertEquals(listOf("origin"), listOfNotNull(pushArguments(listOf("--rep", "origin")).remote))
        assertEquals("origin", pushArguments(listOf("--rep=origin")).remote)
        assertEquals(listOf("x"), pushArguments(listOf("--push-option", "skip", "origin", "x")).refspecs)
        assertEquals(listOf("x"), pushArguments(listOf("--push-option=skip", "origin", "x")).refspecs)
        assertNull(pushArguments(listOf("--recurse-submodules=check", "--dry-run", "origin")).refusal)
        assertEquals(Refusal.UNLISTABLE, pushArguments(listOf("--recurse-submod=only", "o")).refusal)
    }

    @Test
    fun shortOptionClustersAreReadLetterByLetter() {
        assertEquals(true, pushArguments(listOf("-fu", "origin", "x")).force)
        assertEquals(true, pushArguments(listOf("-ud", "origin", "main")).delete)
        assertEquals(listOf("x"), pushArguments(listOf("-o", "ci.skip", "origin", "x")).refspecs)
        assertEquals(listOf("x"), pushArguments(listOf("-oci.f", "origin", "x")).refspecs)
        assertFalse(pushArguments(listOf("-oci.f", "origin", "x")).force)
        assertEquals(true, pushArguments(listOf("-fo", "x", "origin", "y")).force)
        assertFalse(pushArguments(listOf("-u", "origin", "x")).force)
    }

    @Test
    fun theOptionTableResolvesPrefixes() {
        assertEquals("--force", pushOption("--force"))
        assertEquals("--all", pushOption("--al"))
        assertNull(pushOption("--f"))
        assertNull(pushOption("--zzz"))
    }

    @Test
    fun aDestinationNamedAsGitCompletesItIsMain() {
        assertEquals(listOf("push-main"), classes("git push origin HEAD:heads/main"))
        assertEquals(listOf("push-main"), classes("git push origin feature:refs/heads/master"))
        assertTrue(tagLike("tags/release"))
        assertEquals("heads", shortRef("heads"))
    }

    @Test
    fun braceExpansionAndGlobsAreNotPlainNames() {
        for (command in listOf(
            "git push origin {main,dev}", "git push origin ma{in,x}", "git push origin v{1..3}",
            "git push origin ma?n", "git push origin ma[i]n", "git push {origin,x} topic",
        )) {
            assertEquals(Refusal.NOT_PLAIN, classify(command).refusal, command)
        }
        assertTrue(braceExpansion("a{b}c{d,e}"))
        assertTrue(braceExpansion("{a,{b}}"))
        assertTrue(braceExpansion("{a,b,c}"))
        assertTrue(braceExpansion("{a,b..c}"))
        assertFalse(braceExpansion("HEAD@{1}"))
        assertFalse(braceExpansion("a{b,c"))
        assertFalse(braceExpansion("plain"))
        assertFalse(braceExpansion("a.b,c}"))
        assertFalse(braceExpansion("{a.b}"))
        // linear: a long run of braces is read in one pass
        assertFalse(braceExpansion("{".repeat(1_000_000) + "}"))
        assertNull(classify("git push origin HEAD@{1}:topic").refusal)
    }

    @Test
    fun aProgramNamedAtRunTimeFallsUnderEveryClass() {
        for (command in listOf(
            "G=git; \$G push origin main", "\$(which git) push origin main", "k=kubectl; \$k apply -f prod.yaml",
            "`echo helm` upgrade api ./c", "{git,x} push origin main", "git\${IFS}push\${IFS}origin\${IFS}main",
            "sudo \$K apply -f a", "echo \$(\$G push)",
        )) {
            val classification = classify(command)
            assertEquals(everyShellClass, classification.actions, command)
            assertEquals(Refusal.DYNAMIC_COMMAND, classification.refusal, command)
        }
        val custom = ActionClassifierImpl(listOf(DeclaredAction("custom-x", patterns = listOf("^never$"))))
        assertEquals(
            everyShellClass + "custom-x",
            custom.classify(ToolSubject.shell("\$CMD run")).actions,
        )
    }

    @Test
    fun aVariableThatIsNoCommandIsFine() {
        for (command in listOf(
            "echo \$HOME", "cat <<EOF\n\$X is set\nEOF", "echo \${x:-\$y}", "a=\$(date)", "ls \"\$DIR\"",
            "git commit -m \"\$(cat msg) fix\"", "bash -c 'echo \$x'",
        )) {
            assertNull(classify(command).refusal, command)
            assertEquals(emptyList(), classes(command), command)
        }
    }

    @Test
    fun aDecodedVerbIsReadAndABuiltOneIsRefused() {
        assertEquals(listOf("push-main"), classes("git \$'push' origin main"))
        assertNull(classify("git \$'\\x70ush' origin main").refusal)
        assertEquals(listOf("push-main"), classes("git \$'\\x70ush' origin \$\"main\""))
        for (command in listOf(
            "p=push; git \$p origin main", "git send-pack origin main", "git subtree push --prefix=x origin main",
            "git `echo push` origin main",
        )) {
            assertEquals(Refusal.NOT_PLAIN, classify(command).refusal, command)
            assertEquals(listOf("push-main"), classes(command), command)
        }
        assertEquals(Refusal.REDIRECTED, classify("git -c alias.p=push p origin main").refusal)
        assertEquals(Refusal.REDIRECTED, classify("git --config-env=alias.p=P p origin main").refusal)
        assertEquals(emptyList(), classes("git subtree split --prefix=x"))
        assertEquals(emptyList(), classes("git -c core.pager=cat log"))
    }

    @Test
    fun anAliasOfTheGitConfigurationIsReadWhereItRuns() {
        fun state(alias: String?) = GitState(
            "main",
            GitConfig.of(
                *listOfNotNull("remote.origin.url" to "/r.git", alias?.let { "alias.p" to it }).toTypedArray(),
            ),
        )
        for (definition in listOf("push", "push origin HEAD:main", "!git push", "-c x=y push")) {
            val classification = classify("git p origin main", state(definition))
            assertEquals(Refusal.NOT_PLAIN, classification.refusal, definition)
            assertEquals(listOf("push-main"), classification.actions, definition)
            assertEquals(false, classification.push?.standalone, definition)
        }
        assertEquals(emptyList(), classes("git p"))
        assertNull(classify("git p origin main", state("log --oneline")).push)
        assertNull(classify("git p origin main", state(null)).push)
        val unreadable = classifier.classify(ToolSubject.shell("git p origin main")) { null }
        assertEquals(emptyList(), unreadable.actions)
        // a command of git is never an alias: its state is not read
        val reads = ArrayList<List<String>>()
        classifier.classify(ToolSubject.shell("git status && git lfs push origin main")) { reads += it; onMain }
        assertEquals(emptyList(), reads)
        val many = (1..20).joinToString("; ") { "git x$it" }
        classifier.classify(ToolSubject.shell(many)) { reads += it; onMain }
        assertEquals(16, reads.size)
    }

    @Test
    fun ansiQuotedWordsAreDecoded() {
        val words = ShellParser.parse(
            "x \$'a\\nb\\t\\r\\a\\b\\e\\E\\f\\v\\\\\\'\\\"\\?' \$'\\cA\\101\\x41\\u0041\\U00000041\\U0001F600' " +
                "\$'\\x\\q\\8' \$\"d q\"",
        ).segments.first().words
        assertEquals(
            listOf(
                "x", "a\nb\t\r\u0007\b\u001b\u001b\u000c\u000b\\'\"?", "\u0001AAAA�", "\\x\\q\\8", "d q",
            ),
            words,
        )
        assertTrue(ShellParser.parse("echo \$'abc").malformed)
        assertTrue(ShellParser.parse("echo \$'ab\\").malformed)
        assertEquals(listOf("echo", "\\c"), ShellParser.parse("echo \$'\\c").segments.first().words)
    }

    @Test
    fun severalMergesInOneCommandAreRefused() {
        assertEquals(Refusal.SEVERAL_MERGES, classify("gh pr merge 1 && gh pr merge 2").refusal)
        assertEquals(PullRequestRef(1), classify("gh pr merge 1 && gh pr merge 2").pullRequest)
        assertEquals(Refusal.SEVERAL_MERGES, classify("gh pr merge && gh pr merge 3").refusal)
        assertEquals(
            Refusal.SEVERAL_MERGES,
            classify("gh pr merge 3; gh api -X PUT repos/o/r/pulls/4/merge").refusal,
        )
        assertNull(classify("gh pr merge 3 && gh pr merge 3").refusal)
        val hidden = "gh pr merge " + (1..MAX_SCAN + 1).joinToString(" ") { "--auto" } + " 7"
        assertEquals(Refusal.SEVERAL_MERGES, classify(hidden).refusal)
        assertNull(classify(hidden).pullRequest)
        assertNull(classify("gh pr merge 3 '").refusal, "the second, quote-blind reading counts on its own")
        assertEquals(Refusal.NOT_PLAIN, classify("gh pr merge 1 && gh pr merge 2 && git push x \$y").refusal)
    }

    @Test
    fun mergesThroughTheApiInEveryForm() {
        for (command in listOf(
            "gh api -X PUT repos/o/r/pulls/\$PR/merge", "gh api -X PUT repos/o/r/pulls/:number/merge",
            "gh api graphql -f query='mutation{enablePullRequestAutoMerge(input:{})}'",
            "gh api graphql -f query='mutation{mergeBranch(input:{})}'",
            "curl -d '{\"query\":\"mutation{mergePullRequest}\"}' https://api.github.com/graphql",
            "curl -X POST https://ghe.corp/api/v3/repos/o/r/merge-upstream",
            "curl -d '{\"query\":\"mutation{mergePullRequest}\"}' https://ghe.corp/api/graphql",
        )) {
            assertEquals(listOf("merge"), classes(command), command)
        }
        assertEquals(PullRequestRef(12), classify("gh api -X PUT repos/{owner}/{repo}/pulls/12/merge").pullRequest)
        assertNull(classify("gh api -X PUT repos/{owner}/r/pulls/12/merge").pullRequest)
        assertEquals(
            PullRequestRef(1, "o", "r"),
            classify("curl -X PUT https://ghe.corp/api/v3/repos/o/r/pulls/1/merge").pullRequest,
        )
        assertEquals(
            listOf("push-main"),
            classes("gh api graphql -f query='mutation{createCommitOnBranch(input:{})}'"),
        )
        assertEquals(emptyList(), classes("gh api graphql -f query='query{viewer{login}}'"))
        assertEquals(emptyList(), classes("gh api repos/o/r/pulls//merge"))
    }

    @Test
    fun aCommandRunOverSshIsSeen() {
        assertEquals(listOf("deploy"), classes("bash -c 'ssh -i k -p 22 host kubectl apply -f x'"))
        assertEquals(listOf("deploy"), classes("bash -c 'ssh -tt -p22 host helm upgrade x ./c'"))
        assertEquals(emptyList(), classes("bash -c 'ssh -i k host'"))
        assertEquals(emptyList(), classes("bash -c 'ssh -v'"))
    }

    @Test
    fun anyAgentsShellOrWriteToolIsKnownByItsInput() {
        val command = buildJsonObject { put("command", JsonPrimitive("git push origin main")) }
        assertEquals(
            ToolSubject(SubjectKind.SHELL, "shell_command", "git push origin main"),
            ToolSubject.of("shell_command", command),
        )
        assertEquals(SubjectKind.SHELL, ToolSubject.of("brand_new_tool", command).kind)
        val argv = buildJsonObject { put("cmd", JsonArray(listOf(JsonPrimitive("git"), JsonPrimitive("push")))) }
        assertEquals(ToolSubject(SubjectKind.SHELL, "Runner", "git push"), ToolSubject.of("Runner", argv))
        val write = buildJsonObject {
            put("path", JsonPrimitive("db/migrate/2026_x.rb"))
            put("content", JsonPrimitive("x"))
        }
        assertEquals(
            ToolSubject(SubjectKind.WRITE, "create_file", "db/migrate/2026_x.rb"),
            ToolSubject.of("create_file", write),
        )
        assertEquals(listOf("migration"), classifier.classify(ToolSubject.of("create_file", write)).actions)
        val read = buildJsonObject { put("path", JsonPrimitive("/a")) }
        assertEquals(SubjectKind.OTHER, ToolSubject.of("view", read).kind)
        assertEquals(SubjectKind.MCP, ToolSubject.of("mcp__x__run", command).kind)
        val numeric = buildJsonObject { put("command", JsonPrimitive(1)) }
        assertEquals(SubjectKind.OTHER, ToolSubject.of("Task", numeric).kind)
    }

    @Test
    fun aPatchIsReadWholeForTheFilesItTouches() {
        val patch = buildJsonObject {
            put("patch", JsonPrimitive("x".repeat(3000) + "\n*** Add File: db/migration/V9__x.sql\n"))
        }
        assertEquals(listOf("migration"), classifier.classify(ToolSubject.of("apply_patch", patch)).actions)
        for (command in listOf(
            "./gradlew flywayMigrate", "mvn flyway:migrate", "cat > prisma/migrations/a.sql", "gradle flywayMigrate",
            "./mvnw -q flyway:migrate",
        )) {
            assertEquals(listOf("migration"), classes(command), command)
        }
        assertEquals(emptyList(), classes("./gradlew build"))
    }

    @Test
    fun declaredToolsAreTheToolsOfEveryAgent() {
        val declared = ActionClassifierImpl(
            listOf(
                DeclaredAction("custom-a", tools = listOf("exec_command", "Notebook"), patterns = listOf("alpha")),
                DeclaredAction("custom-a", tools = listOf("apply_patch", "mcp__stripe"), patterns = listOf("beta")),
                DeclaredAction("custom-a", tools = listOf("bash"), patterns = listOf("gamma")),
                DeclaredAction("deploy", tools = listOf("mcp"), patterns = listOf("deploy_now")),
            ),
        )
        assertEquals(
            setOf(SubjectKind.SHELL, SubjectKind.WRITE, SubjectKind.MCP),
            declared.catalogue.first { it.id == "custom-a" }.kinds,
        )
        assertEquals(BuiltInActions.all.first { it.id == "deploy" }, declared.catalogue.first { it.id == "deploy" })
        assertEquals(
            listOf(RejectedPattern("custom-a", "Notebook", "not a tool Ostrom reads: Notebook")),
            declared.rejectedPatterns,
        )
        assertEquals(listOf("custom-a"), declared.classify(ToolSubject.of("mcp__x__beta", buildJsonObject { })).actions)
    }
}
