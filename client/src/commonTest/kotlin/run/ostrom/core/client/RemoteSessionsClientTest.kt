// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DaemonPollRequest
import run.ostrom.core.api.models.DeviceFolder
import kotlin.test.Test
import kotlin.test.assertEquals

/** Remote sessions (`runners.cloud.remote.v1`): listing by kind, hosting, and a device's daemon. */
class RemoteSessionsClientTest {

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}" +
        if (url.parameters.isEmpty()) "" else "?" + url.encodedQuery

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private val cloudSession = """{"id":"cs_1","owner":"m1","agent":"claude","status":"creating",""" +
        """"phase":"cloning_repositories",""" +
        """"epoch":1,"failure":{"code":"preparation_failed","message":"Clone failed.","retryable":true},""" +
        """"limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},""" +
        """"profile":{"id":"p1","version":3},"environment":{"repositories":[{"url":"https://github.com/o/r"}],""" +
        """"setup":{"create":["make"]}},"createdAt":"t"}"""

    @Test
    fun aDevicesDaemonPollsPublishesRefusesAnswersAndStops() = runTest {
        val order = """{"sessionId":"cs_1","folderId":"f1","directoryName":"ostrom","agent":"claude",""" +
            """"permissionMode":"ask","status":"creating","requestedAt":"t"}"""
        val listing = """{"requestId":"l1","folderId":"f1","limit":20}"""
        val server = ScriptedServer { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Delete || path.contains("/listings/") -> json("", HttpStatusCode.NoContent)
                path.endsWith("/poll") ->
                    json("""{"orders":[$order],"onlineTtlMillis":60000,"maxWaitMillis":20000,"listings":[$listing]}""")
                path.endsWith("/refuse") -> json(cloudSession)
                path.endsWith("/current/folders") -> json("""[{"id":"f1","name":"ostrom","label":"~/git/ostrom"}]""")
                path.endsWith("/resumable") ->
                    json("""{"items":[{"id":"s1","title":"Fix","lastActiveAt":"t","turns":3}],"next":"c2"}""")
                else -> json(
                    """{"deviceId":"d1","daemon":{"online":true,"lastSeenAt":"t","features":["resume.v1"]},""" +
                        """"folders":[{"id":"f1","name":"ostrom"}]}""",
                )
            }
        }
        server.client().use { client ->
            val request = DaemonPollRequest(waitMillis = 20_000, version = "0.1.0", features = listOf("resume.v1"))
            val poll = client.deviceDaemon.poll(request)
            assertEquals("cs_1", poll.orders.single().sessionId)
            assertEquals("l1", poll.listings.single().requestId)
            val published = client.deviceDaemon.publishFolders(listOf(DeviceFolder("f1", "ostrom", "~/git/ostrom")))
            assertEquals("~/git/ostrom", published.single().label)
            assertEquals("cs_1", client.deviceDaemon.refuse("cs_1", "folder_not_configured").id)
            client.deviceDaemon.answerListing("l1", DaemonListingAnswer(refused = "folder_not_configured"))
            client.deviceDaemon.stop()
            val folders = client.deviceDaemon.folders("d1")
            assertEquals(true, folders.daemon?.online)
            assertEquals("ostrom", folders.folders.single().name)
            assertEquals(3, client.deviceDaemon.resumable("d1", "f1", limit = 10, before = "c1").items.single().turns)
            assertEquals("c2", client.deviceDaemon.resumable("d1", "f1").next)
        }
        assertEquals(
            listOf(
                "POST /api/v1/devices/current/daemon/poll",
                "PUT /api/v1/devices/current/folders",
                "POST /api/v1/devices/current/daemon/sessions/cs_1/refuse",
                "POST /api/v1/devices/current/daemon/listings/l1",
                "DELETE /api/v1/devices/current/daemon",
                "GET /api/v1/devices/d1/folders",
                "GET /api/v1/devices/d1/folders/f1/resumable?limit=10&before=c1",
                "GET /api/v1/devices/d1/folders/f1/resumable",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"waitMillis":20000,"version":"0.1.0","features":["resume.v1"]}""", server.requests[0].bodyText())
        val folders = """{"folders":[{"id":"f1","name":"ostrom","label":"~/git/ostrom"}]}"""
        assertJson(folders, server.requests[1].bodyText())
        assertJson("""{"reason":"folder_not_configured"}""", server.requests[2].bodyText())
        assertJson("""{"refused":"folder_not_configured"}""", server.requests[3].bodyText())
    }

    @Test
    fun remoteSessionsAreListedByKindAndHosted() = runTest {
        val remote = cloudSession.dropLast(1) + ""","kind":"remote","host":{"deviceId":"d1","deviceName":"Laptop",""" +
            """"directoryName":"ostrom","online":true,"permissionMode":"ask"}}"""
        val server = ScriptedServer { request ->
            when {
                request.url.encodedPath.endsWith("/host") -> json("""{"orgId":"o1","epoch":4,"session":$remote}""")
                else -> json("""{"items":[$remote]}""")
            }
        }
        server.client().use { client ->
            val listed = client.cloudSessions.list(limit = 5, kinds = listOf("cloud", "remote")).items.single()
            assertEquals("remote", listed.kind)
            assertEquals("ostrom", listed.host?.directoryName)
            val host = client.cloudSessions.host("cs_1")
            assertEquals("o1", host.orgId)
            assertEquals(4L, host.epoch)
            assertEquals(true, host.session.host?.online)
        }
        assertEquals(
            listOf(
                "GET /api/v1/cloud/sessions?kind=cloud&kind=remote&limit=5",
                "POST /api/v1/cloud/sessions/cs_1/host",
            ),
            server.requests.map { it.call() },
        )
    }
}
