// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitStateTest {

    @Test
    fun theListOfGitConfigIsReadAsGitReadsIt() {
        val config = GitConfig.parseListZ(
            "Remote.origin.URL\nhttps://a\u0000remote.origin.url\nhttps://b\u0000" +
                "Branch.Topic.Merge\nrefs/heads/main\u0000" +
                "remote.origin.mirror\u0000core.multi\nline 1\nline 2\u0000\u0000",
        )
        assertEquals("https://b", config.get("remote.origin.url"))
        assertEquals(listOf("https://a", "https://b"), config.getAll("REMOTE.origin.Url"))
        assertEquals("refs/heads/main", config.get("branch.Topic.merge"))
        assertNull(config.get("branch.topic.merge"), "a subsection keeps its case")
        assertEquals("", config.get("remote.origin.mirror"))
        assertEquals("line 1\nline 2", config.get("core.multi"))
        assertNull(config.get("core.absent"))
        assertEquals(emptyList(), config.getAll("core.absent"))
        assertEquals("x", GitConfig.of("Core" to "x").get("core"))
    }

    @Test
    fun booleansFollowGit() {
        val config = GitConfig.parseListZ(
            "a.t\u0000a.yes\nYes\u0000a.on\non\u0000a.one\n1\u0000a.true\nTRUE\u0000a.no\nfalse\u0000a.zero\n0\u0000",
        )
        for (key in listOf("a.t", "a.yes", "a.on", "a.one", "a.true")) assertTrue(config.getBoolean(key), key)
        for (key in listOf("a.no", "a.zero", "a.absent")) assertFalse(config.getBoolean(key), key)
        assertEquals(GitConfig.EMPTY.get("a.b"), null)
    }

    @Test
    fun slugsAreOwnerAndNameOrTheLastPart() {
        assertEquals("o/core", RemoteUrls.slugOf("git@github.com:o/core.git"))
        assertEquals("o/core", RemoteUrls.slugOf(" https://github.com/o/core.git/ "))
        assertEquals("o/core", RemoteUrls.slugOf("ssh://git@host:22/srv/o/core.git"))
        assertEquals("core", RemoteUrls.slugOf("https://host/core"))
        assertEquals("", RemoteUrls.slugOf("https://host"))
        assertEquals("core-remote", RemoteUrls.slugOf("/tmp/x/core-remote.git"))
        assertEquals("core", RemoteUrls.slugOf("../core"))
        assertEquals("x", RemoteUrls.slugOf("./a:b/x"))
        assertEquals(":x", RemoteUrls.slugOf(":x"))
        assertEquals("core", RemoteUrls.slugOf("host:core"))
    }

    @Test
    fun pushUrlsAreRewrittenAsGitDoes() {
        val config = GitConfig.of(
            "url.https://mirror/.insteadOf" to "https://github.com/",
            "url.https://mirror/o-special/.insteadOf" to "https://github.com/o/",
            "url.ssh://push/.pushInsteadOf" to "https://github.com/",
            "url.empty.insteadOf" to "",
        )
        assertEquals(
            "ssh://push/o/r",
            RemoteUrls.rewriteForPush("https://github.com/o/r", config, explicitPushUrl = false),
        )
        assertEquals(
            "https://mirror/o-special/r",
            RemoteUrls.rewriteForPush("https://github.com/o/r", config, explicitPushUrl = true),
        )
        assertEquals(
            "https://gitlab.com/o/r",
            RemoteUrls.rewriteForPush("https://gitlab.com/o/r", config, explicitPushUrl = false),
        )
        assertEquals("https://mirror/o/r", RemoteUrls.rewriteForPush("https://github.com/o/r", GitConfig.of(
            "url.https://mirror/.insteadOf" to "https://github.com/",
            "url.https://short/.insteadOf" to "https://git",
        ), explicitPushUrl = false))
    }

    @Test
    fun refsAreSplitShortenedAndRecognized() {
        assertEquals("a" to "b", splitRefspec("+a:b"))
        assertEquals("a" to "", splitRefspec("a"))
        assertEquals("" to "old", splitRefspec(":old"))
        assertEquals("main", shortRef("refs/heads/main"))
        assertEquals("refs/tags/v1", shortRef("refs/tags/v1"))
        for (ref in listOf("refs/tags/x", "v1.2", "V10.0.1", "1.2.3", "release/v2.0")) assertTrue(tagLike(ref), ref)
        for (ref in listOf("main", "v1", "v.1", "1.", "1.x", "va.1", "feature/x.1")) assertFalse(tagLike(ref), ref)
    }

    @Test
    fun pushArgumentsReadOptionsValuesAndTheEndOfOptions() {
        val args = pushArguments(listOf("-o", "ci.skip", "--receive-pack", "rp", "--exec=x", "--", "-weird", "+a:b"))
        assertEquals("-weird", args.remote)
        assertEquals(listOf("+a:b"), args.refspecs)
        assertTrue(args.force)
        assertNull(args.refusal)
        assertEquals("r", pushArguments(listOf("--repo")).remote ?: "r")
        assertEquals("-", pushArguments(listOf("-")).remote)
        assertFalse(pushArguments(listOf("-v", "--quiet", "--porcelain", "origin")).force)
        assertTrue(pushArguments(listOf("-qf", "origin")).force)
        assertFalse(pushArguments(listOf("-1f", "origin")).force)
    }

    @Test
    fun assignmentsAreRecognized() {
        assertEquals("GIT_DIR", assignedVariable("GIT_DIR=/x"))
        assertEquals("_a1", assignedVariable("_a1="))
        assertNull(assignedVariable("=x"))
        assertNull(assignedVariable("1A=x"))
        assertNull(assignedVariable("A-B=x"))
        assertNull(assignedVariable("plain"))
        assertTrue(redirectingAssignment("git_config_global=/x"))
        assertTrue(redirectingAssignment("HOME=/tmp"))
        assertFalse(redirectingAssignment("LANG=C"))
        assertFalse(redirectingAssignment("--x=y"))
    }
}
