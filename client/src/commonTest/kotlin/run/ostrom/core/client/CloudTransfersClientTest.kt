// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.CloudTransferStates
import run.ostrom.core.api.models.CreateCloudTransferRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The routes of a session's change of owner. */
class CloudTransfersClientTest {

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}" +
        if (url.parameters.isEmpty()) "" else "?" + url.encodedQuery

    private val transfer = """{"id":"tr_1","sessionId":"cs_1","fromMemberId":"ada","toMemberId":"bob",""" +
        """"state":"offered",""" +
        """"initiatedBy":"ada","resume":false,"createdAt":"t","updatedAt":"t","expiresAt":"u","later":1}"""

    @Test
    fun aTransferIsAskedWithItsKeyListedReadAndDecided() = runTest {
        val server = ScriptedServer { request ->
            when {
                request.url.encodedPath.endsWith("/transfer-recipients") ->
                    json("""{"recipients":[{"id":"bob","login":"bob","administrator":true}]}""")
                request.url.encodedPath == "/api/v1/cloud/transfers" && request.method.value == "GET" ->
                    json("""{"items":[$transfer],"next":"tr_0"}""")
                else -> json(transfer)
            }
        }
        server.client().use { client ->
            assertTrue(client.cloudTransfers.recipients().recipients.single().administrator)
            val asked = client.cloudTransfers.request("cs 1", CreateCloudTransferRequest("bob", resume = true), "k-1")
            assertEquals(CloudTransferStates.OFFERED, asked.state)
            assertNull(asked.decidedBy)
            val page = client.cloudTransfers.list("incoming", listOf("offered", "accepted"), "cs_1", "tr_9", 10)
            assertEquals("tr_0", page.next)
            client.cloudTransfers.get("tr_1")
            client.cloudTransfers.accept("tr_1", resume = true)
            client.cloudTransfers.accept("tr_1")
            client.cloudTransfers.decline("tr_1")
            client.cloudTransfers.cancel("tr_1")
        }
        assertEquals(
            listOf(
                "GET /api/v1/cloud/transfer-recipients",
                "POST /api/v1/cloud/sessions/cs%201/transfer",
                "GET /api/v1/cloud/transfers?direction=incoming&session=cs_1&before=tr_9&limit=10" +
                    "&state=offered&state=accepted",
                "GET /api/v1/cloud/transfers/tr_1",
                "POST /api/v1/cloud/transfers/tr_1/accept",
                "POST /api/v1/cloud/transfers/tr_1/accept",
                "POST /api/v1/cloud/transfers/tr_1/decline",
                "POST /api/v1/cloud/transfers/tr_1/cancel",
            ),
            server.requests.map { it.call() },
        )
        val asked = server.requests[1]
        assertEquals("k-1", asked.headers["Idempotency-Key"])
        val body = (asked.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertEquals(Json.parseToJsonElement("""{"toMemberId":"bob","resume":true}"""), Json.parseToJsonElement(body))
    }

    @Test
    fun theStatesAreDistinctAndInTheOrderOfATransfersLife() {
        assertEquals(CloudTransferStates.all.size, CloudTransferStates.all.toSet().size)
        assertEquals("offered", CloudTransferStates.all.first())
    }
}
