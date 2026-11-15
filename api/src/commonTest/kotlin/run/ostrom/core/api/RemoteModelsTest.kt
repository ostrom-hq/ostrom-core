// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.CloudAgentKind
import run.ostrom.core.api.models.CloudSession
import run.ostrom.core.api.models.CloudSessionKinds
import run.ostrom.core.api.models.CreateCloudSessionRequest
import run.ostrom.core.api.models.DaemonFeatures
import run.ostrom.core.api.models.DaemonLimits
import run.ostrom.core.api.models.Device
import run.ostrom.core.api.models.LocalKnownFolderLimits
import run.ostrom.core.api.models.LocalResumeReasons
import run.ostrom.core.api.models.RemotePermissionModes
import run.ostrom.core.api.models.RemoteSessionLimits
import run.ostrom.core.api.models.ResumableSessionLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteModelsTest {
    @Test
    fun theKindsAndPermissionModesAreTeamCodesInContractOrder() {
        assertEquals(listOf("cloud", "remote", "local"), CloudSessionKinds.ALL)
        assertEquals(listOf("ask", "bypass"), RemotePermissionModes.ALL)
        assertEquals("resume.v1", DaemonFeatures.RESUME)
        val reasons = listOf(
            LocalResumeReasons.SERVER_UNSUPPORTED,
            LocalResumeReasons.PLATFORM_UNSUPPORTED,
            LocalResumeReasons.DAEMON_OFFLINE,
            LocalResumeReasons.DAEMON_TOO_OLD,
        )
        assertEquals(listOf("server_unsupported", "platform_unsupported", "daemon_offline", "daemon_too_old"), reasons)
    }

    @Test
    fun aDirectoryNameIsOneSegment() {
        assertTrue(RemoteSessionLimits.isDirectoryName("ostrom"))
        assertTrue(RemoteSessionLimits.isDirectoryName("a".repeat(RemoteSessionLimits.MAX_DIRECTORY_NAME)))
        assertFalse(RemoteSessionLimits.isDirectoryName(""))
        assertFalse(RemoteSessionLimits.isDirectoryName("a".repeat(RemoteSessionLimits.MAX_DIRECTORY_NAME + 1)))
        assertFalse(RemoteSessionLimits.isDirectoryName("."))
        assertFalse(RemoteSessionLimits.isDirectoryName(".."))
        assertFalse(RemoteSessionLimits.isDirectoryName("git/ostrom"))
        assertFalse(RemoteSessionLimits.isDirectoryName("git\\ostrom"))
        assertFalse(RemoteSessionLimits.isDirectoryName("ost\u0007rom"))
        assertTrue(RemoteSessionLimits.isDirectoryName("...hidden"))
        assertEquals(60, RemoteSessionLimits.DEFAULT_IDLE_TIMEOUT_MINUTES)
        assertEquals(1440, RemoteSessionLimits.MAX_IDLE_TIMEOUT_MINUTES)
    }

    @Test
    fun theDaemonsBoundsAreTeamCodes() {
        val waits = listOf(DaemonLimits.ONLINE_TTL_MILLIS, DaemonLimits.MAX_WAIT_MILLIS)
        assertEquals(listOf(60_000L, 20_000L, 10_000L), waits + DaemonLimits.LISTING_WAIT_MILLIS)
        assertEquals(30_000L, DaemonLimits.LISTING_TTL_MILLIS)
        val counts = listOf(DaemonLimits.ORDER_BATCH, DaemonLimits.MAX_FOLDERS, DaemonLimits.MAX_FEATURES)
        assertEquals(listOf(16, 64, 8), counts)
        assertTrue(DaemonLimits.FOLDER_ID.matches("f_1-A"))
        assertFalse(DaemonLimits.FOLDER_ID.matches("a/b"))
        assertTrue(DaemonLimits.FEATURE.matches(DaemonFeatures.RESUME))
        assertTrue(DaemonLimits.VERSION.matches("0.1.42+dev"))
        assertFalse(DaemonLimits.REASON.matches("Folder not configured"))
        assertTrue(ResumableSessionLimits.ID.matches("0f8a3c6e-2b1d-4e5f-9a7b-1c2d3e4f5a6b"))
        val pages = listOf(
            ResumableSessionLimits.TITLE_LENGTH,
            ResumableSessionLimits.DEFAULT_LIMIT,
            ResumableSessionLimits.MAX_LIMIT,
        )
        assertEquals(listOf(80, 20, 50), pages)
        assertEquals(listOf(20, 50), listOf(LocalKnownFolderLimits.DEFAULT_LIMIT, LocalKnownFolderLimits.MAX_LIMIT))
    }

    @Test
    fun aCloudOnlyPayloadDecodesWithoutKindOrHostAndACreateWithoutRemoteFieldsEncodesAsBefore() {
        val session = OstromJson.decodeFromString(
            CloudSession.serializer(),
            """{"id":"cs_1","owner":"m1","agent":"claude","status":"running","createdAt":"t"}""",
        )
        assertNull(session.kind)
        assertNull(session.host)
        val device = OstromJson.decodeFromString(Device.serializer(), """{"id":"d1","name":"Laptop"}""")
        assertNull(device.daemon)
        val request = CreateCloudSessionRequest(CloudAgentKind.CLAUDE)
        val create = OstromJson.encodeToString(CreateCloudSessionRequest.serializer(), request)
        assertEquals("""{"agent":"claude"}""", create)
    }

    @Test
    fun aLocalSessionCarriesItsLaunch() {
        val request = CreateCloudSessionRequest(CloudAgentKind.CODEX, kind = CloudSessionKinds.LOCAL, launchId = "l1")
        val create = OstromJson.encodeToString(CreateCloudSessionRequest.serializer(), request)
        assertEquals("""{"agent":"codex","kind":"local","launchId":"l1"}""", create)
        val session = OstromJson.decodeFromString(
            CloudSession.serializer(),
            """{"id":"cs_1","owner":"m1","agent":"codex","status":"creating","createdAt":"t",""" +
                """"kind":"local","launchId":"l1"}""",
        )
        assertEquals(CloudSessionKinds.LOCAL, session.kind)
        assertEquals("l1", session.launchId)
        assertNull(session.host)
    }
}
