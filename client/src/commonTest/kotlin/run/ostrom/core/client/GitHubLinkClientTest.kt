// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import run.ostrom.core.api.models.GitHubDeviceLinkStatus
import run.ostrom.core.api.models.GitHubLinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The GitHub link of the caller and of the members, the device flow, and a cloud session's GitHub access. */
class GitHubLinkClientTest {
    private val linked = """{"memberId":"m1","state":"linked","appConfigured":true,"login":"ada","userId":7,""" +
        """"linkedAt":"2026-01-01T00:00:00Z","refreshedAt":"2026-01-02T00:00:00Z"}"""

    @Test
    fun theOwnLinkIsReadAndAStateOfANewerServerReadsAsUnknown() = runTest {
        val server = ScriptedServer { json(linked) }
        server.client().use { client ->
            val link = client.githubLink.get()
            assertEquals(GitHubLinkState.LINKED, link.state)
            assertEquals("ada", link.login)
            assertNull(link.accessExpiresAt)
        }
        val request = server.requests.single()
        assertEquals("GET /api/v1/github/link", "${request.method.value} ${request.url.encodedPath}")
        val newer = ScriptedServer { json("""{"memberId":"m1","state":"suspended","appConfigured":false}""") }
        newer.client().use { assertEquals(GitHubLinkState.UNKNOWN, it.githubLink.get().state) }
    }

    @Test
    fun theDeviceFlowStartsThenPollsEachOutcome() = runTest {
        val server = ScriptedServer { request ->
            when {
                request.url.encodedPath.endsWith("/device") -> json(
                    """{"id":"a1","userCode":"WDJB-MJHT","verificationUri":"https://github.com/login/device",""" +
                        """"expiresAt":"2026-01-01T00:15:00Z","interval":5}""",
                )
                request.url.encodedPath.endsWith("/pending") -> json("""{"status":"pending","interval":10}""")
                request.url.encodedPath.endsWith("/done") -> json("""{"status":"linked","link":$linked}""")
                request.url.encodedPath.endsWith("/gone") -> json("""{"status":"expired"}""")
                else -> json("""{"status":"denied"}""")
            }
        }
        server.client().use { client ->
            val started = client.githubLink.startDevice()
            assertEquals("WDJB-MJHT", started.userCode)
            assertEquals(5, started.interval)
            assertEquals(10, client.githubLink.pollDevice("pending").interval)
            assertEquals("ada", client.githubLink.pollDevice("done").link?.login)
            assertEquals(GitHubDeviceLinkStatus.EXPIRED, client.githubLink.pollDevice("gone").status)
            assertEquals(GitHubDeviceLinkStatus.DENIED, client.githubLink.pollDevice("no").status)
        }
        assertEquals(
            listOf("POST", "POST", "POST", "POST", "POST"),
            server.requests.map { it.method.value },
        )
        assertEquals("/api/v1/github/link/device/pending", server.requests[1].url.encodedPath)
    }

    @Test
    fun theMembersLinksAreListedReadAndUnlinkedAndARefusalKeepsItsCode() = runTest {
        val server = ScriptedServer { request ->
            when {
                request.method.value == "DELETE" -> json("", HttpStatusCode.NoContent)
                request.url.encodedPath == "/api/v1/github/links" -> json("""{"items":[$linked]}""")
                request.url.encodedPath.endsWith("/m9") -> json(
                    """{"error":{"type":"not_found","message":"No such member."}}""",
                    HttpStatusCode.NotFound,
                )
                else -> json(linked)
            }
        }
        server.client().use { client ->
            assertEquals("m1", client.githubLink.list().items.single().memberId)
            assertEquals("ada", client.githubLink.member("m1").login)
            client.githubLink.unlink()
            client.githubLink.unlinkMember("m1")
            assertFailsWith<OstromException> { client.githubLink.member("m9") }
        }
        assertEquals(
            listOf(
                "GET /api/v1/github/links",
                "GET /api/v1/github/links/m1",
                "DELETE /api/v1/github/link",
                "DELETE /api/v1/github/links/m1",
                "GET /api/v1/github/links/m9",
            ),
            server.requests.map { "${it.method.value} ${it.url.encodedPath}" },
        )
    }

    @Test
    fun aCloudSessionsGitHubAccessIsSetWithItsBody() = runTest {
        val server = ScriptedServer {
            json(
                """{"id":"cs_1","owner":"ada","agent":"claude","status":"running","createdAt":"t",""" +
                    """"githubAccess":"shared"}""",
            )
        }
        server.client().use { client ->
            assertEquals("shared", client.cloudSessions.setGitHubAccess("cs_1", "shared").githubAccess)
        }
        val (request, body) = server.single()
        assertEquals("PUT /api/v1/cloud/sessions/cs_1/github", "${request.method.value} ${request.url.encodedPath}")
        assertEquals("""{"access":"shared"}""", body)
    }
}
