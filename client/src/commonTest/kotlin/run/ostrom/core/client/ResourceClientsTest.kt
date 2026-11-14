// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.models.ApprovalAnswer
import run.ostrom.core.api.models.NotificationPreferences
import run.ostrom.core.api.models.QuietHours
import run.ostrom.core.api.models.WaitStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Each resource client: the method, path, query and body of its calls, and what it reads back. */
class ResourceClientsTest {

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}" +
        if (url.parameters.isEmpty()) "" else "?" + url.encodedQuery

    private val approval = """{"id":"apr_1","title":"Push to main","requestedFrom":"ada","channel":"app",""" +
        """"status":"pending","shownDigest":"sha256:1"}"""
    private val approvalView = """{"approval":$approval,"you":{"login":"ada","self":false,"canApprove":true}}"""
    private val receipt = """{"id":"rcp_1","subject":{"kind":"task","id":"t1","digest":"d"},"initiator":"ada",""" +
        """"rules":[],"decisions":[],"createdAt":"2026-11-13T00:00:00Z","signature":"sig"}"""
    private val wait = """{"id":"w/1","kind":"question","status":"open","assignee":"ada",""" +
        """"question":{"title":"Ship?"},"createdAt":"2026-11-13T00:00:00Z"}"""
    private val artifact = """{"id":"a1","title":"Report","owner":"ada","access":"team","latestVersion":2,""" +
        """"url":"https://ostrom.example/artifacts/a1","createdAt":"t","updatedAt":"t"}"""

    @Test
    fun theApprovalQueueAViewAndAnAnswerAreSentToTheirRoutes() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/approvals/queue" -> json(
                    """{"operator":"ada","you":{"login":"ada","via":"app"},"pending":[$approvalView],"recent":[]}""",
                )
                else -> json(approvalView)
            }
        }
        server.client().use { client ->
            val queue = client.approvals.queue()
            assertEquals("apr_1", queue.pending.single().approval.id)
            assertEquals("app", queue.you.via)
            assertEquals(true, client.approvals.view("apr_1").you.canApprove)
            client.approvals.answer("apr 1", ApprovalAnswer.APPROVE, "sha256:1")
            client.approvals.answer("apr_1", ApprovalAnswer.REJECT, "sha256:1", note = "Not on Friday.")
        }
        assertEquals(
            listOf(
                "GET /api/v1/approvals/queue",
                "GET /api/v1/approvals/apr_1/view",
                "POST /api/v1/approvals/apr%201/answer",
                "POST /api/v1/approvals/apr_1/answer",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"answer":"approve","shownDigest":"sha256:1"}""", server.requests[2].bodyText())
        assertJson(
            """{"answer":"reject","shownDigest":"sha256:1","note":"Not on Friday."}""",
            server.requests[3].bodyText(),
        )
    }

    @Test
    fun aRefusalWithoutAMessageKeepsItsTypeAndRequestBound() = runTest {
        val server = ScriptedServer {
            json(
                """{"error":{"type":"step_up_required","requestBound":true}}""",
                HttpStatusCode.Forbidden,
            )
        }
        val error = server.client().use { client ->
            assertFailsWith<OstromException> { client.approvals.answer("apr_1", ApprovalAnswer.APPROVE, "d") }
        }
        assertEquals(403 to "step_up_required", error.status to error.type)
        assertEquals(true, error.requestBound)
        assertEquals("Ostrom answered HTTP 403.", error.message)
    }

    @Test
    fun receiptsArePagedReadOneByOneAndTheirKeysListed() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/receipts" -> json("""{"items":[$receipt],"next":"c2"}""")
                "/api/v1/receipts/keys" -> json(
                    """{"keys":[{"kid":"k1","alg":"Ed25519","publicKeyPem":"PEM",""" +
                        """"rotation":{"kid":"k0","signature":"s"}}]}""",
                )
                else -> json(receipt)
            }
        }
        server.client().use { client ->
            assertEquals("c2", client.receipts.list().next)
            client.receipts.list(before = "c2", limit = 50)
            assertEquals("rcp_1", client.receipts.get("rcp_1").id)
            assertEquals("k0", client.receipts.keys().single().rotation?.kid)
        }
        assertEquals(
            listOf(
                "GET /api/v1/receipts",
                "GET /api/v1/receipts?before=c2&limit=50",
                "GET /api/v1/receipts/rcp_1",
                "GET /api/v1/receipts/keys",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun rawReceiptsKeepTheFieldsThisVersionDoesNotKnow() = runTest {
        val newer = receipt.trimEnd().removeSuffix("}") + ""","futureField":{"x":1}}"""
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/receipts" -> json("""{"items":[$newer],"next":"c2"}""")
                else -> json(newer)
            }
        }
        server.client().use { client ->
            val page = client.receipts.listRaw(before = "c1", limit = 10)
            assertEquals("c2", page.next)
            assertEquals("""{"x":1}""", page.items.single()["futureField"].toString())
            assertEquals("""{"x":1}""", client.receipts.getRaw("rcp_1")["futureField"].toString())
            assertEquals(null, client.receipts.listRaw().items.single()["absent"])
        }
        assertEquals(
            listOf("GET /api/v1/receipts?before=c1&limit=10", "GET /api/v1/receipts/rcp_1", "GET /api/v1/receipts"),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun spendingAndActivityCarryTheirFilters() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/spending" -> json(
                    """{"period":"week","from":"a","to":"b","totalUsd":12.5,"byAgent":[],"byRepo":[],""" +
                        """"byPerson":[{"key":"m1","label":"ada","usd":12.5,"sessions":3}],""" +
                        """"daily":[{"date":"d","usd":1.0}]}""",
                )
                else -> json(
                    """{"items":[{"id":"e1","at":"t","kind":"gate.decided","actor":{"kind":"person","name":"ada"},""" +
                        """"summary":"Yes."}],"next":"c"}""",
                )
            }
        }
        server.client().use { client ->
            val spending = client.spending.report("month")
            assertEquals(3, spending.byPerson.single().sessions)
            assertEquals(emptyList(), spending.exceeded)
            client.spending.report()
            assertEquals("gate.decided", client.activity.page().items.single().kind)
            client.activity.page(before = "c", repo = "acme/api")
        }
        assertEquals(
            listOf(
                "GET /api/v1/spending?period=month",
                "GET /api/v1/spending",
                "GET /api/v1/activity",
                "GET /api/v1/activity?before=c&repo=acme%2Fapi",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun aPhoneIsRegisteredThenDeleted() = runTest {
        val server = ScriptedServer { request ->
            if (request.method == HttpMethod.Delete) {
                json("", HttpStatusCode.NoContent)
            } else {
                json("""{"id":"pd_1","provider":"fcm","platform":"android","createdAt":"t"}""", HttpStatusCode.Created)
            }
        }
        server.client().use { client ->
            assertEquals("pd_1", client.pushDevices.register("tok", "fcm", "android").id)
            client.pushDevices.register("tok", "fcm", "ios", locale = "fr", publicKey = "MFkw")
            client.pushDevices.delete("pd_1")
        }
        assertJson("""{"pushToken":"tok","provider":"fcm","platform":"android"}""", server.requests[0].bodyText())
        assertJson(
            """{"pushToken":"tok","provider":"fcm","platform":"ios","locale":"fr","publicKey":"MFkw"}""",
            server.requests[1].bodyText(),
        )
        assertEquals("DELETE /api/v1/push-devices/pd_1", server.requests[2].call())
    }

    @Test
    fun notificationPreferencesAreReadAndReplaced() = runTest {
        val body = """{"requests":true,"decisions":false,"spending":true,""" +
            """"quietHours":{"start":"22:00","end":"07:00","timeZone":"Europe/Paris"}}"""
        val server = ScriptedServer { json(body) }
        server.client().use { client ->
            val preferences = client.notifications.preferences()
            assertEquals("Europe/Paris", preferences.quietHours?.timeZone)
            val quietHours = QuietHours("22:00", "07:00", "Europe/Paris")
            client.notifications.save(NotificationPreferences(true, false, true, quietHours))
        }
        assertEquals(
            listOf("GET /api/v1/me/notifications", "PUT /api/v1/me/notifications"),
            server.requests.map { it.call() },
        )
        assertJson(body, server.requests[1].bodyText())
    }

    @Test
    fun waitsAreListedReadAndAnsweredWithTheirValues() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/waits" -> json("""{"items":[$wait]}""")
                else -> json(wait)
            }
        }
        server.client().use { client ->
            val page = client.waits.list()
            assertNull(page.next)
            assertEquals(WaitStatus.OPEN, page.items.single().status)
            client.waits.list(status = "open", mine = true, before = "w0", limit = 10, resolver = "human")
            assertEquals("Ship?", client.waits.get("w/1").question.title)
            client.waits.answer("w/1", "approve")
            client.waits.answer("w/1", "approve", mapOf("reason" to JsonPrimitive("ok")))
        }
        assertEquals(
            listOf(
                "GET /api/v1/waits",
                "GET /api/v1/waits?status=open&mine=true&before=w0&limit=10&resolver=human",
                "GET /api/v1/waits/w%2F1",
                "POST /api/v1/waits/w%2F1/answer",
                "POST /api/v1/waits/w%2F1/answer",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"action":"approve"}""", server.requests[3].bodyText())
        assertJson("""{"action":"approve","values":{"reason":"ok"}}""", server.requests[4].bodyText())
    }

    @Test
    fun artifactsAreListedReadVersionedAndOpenedWithATicket() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/artifacts" -> json("""{"items":[$artifact],"next":"n"}""")
                "/api/v1/artifacts/a1/versions" -> json(
                    """{"items":[{"number":2,"files":["index.html"],"entry":"index.html",""" +
                        """"createdAt":"t","createdBy":"ada"}]}""",
                )
                "/api/v1/artifacts/a1/tickets" -> json(
                    """{"artifactId":"a1","version":2,"url":"https://c.example/x",""" +
                        """"contentUrl":"https://c.example/x","expiresAt":"t"}""",
                    HttpStatusCode.Created,
                )
                else -> json(artifact)
            }
        }
        server.client().use { client ->
            assertEquals("n", client.artifacts.list().next)
            client.artifacts.list(before = "n", limit = 20)
            assertEquals(2, client.artifacts.get("a1").latestVersion)
            assertEquals("index.html", client.artifacts.versions("a1").items.single().entry)
            assertEquals("https://c.example/x", client.artifacts.ticket("a1").url)
            client.artifacts.ticket("a1", version = 1)
        }
        assertEquals(
            listOf(
                "GET /api/v1/artifacts",
                "GET /api/v1/artifacts?before=n&limit=20",
                "GET /api/v1/artifacts/a1",
                "GET /api/v1/artifacts/a1/versions",
                "POST /api/v1/artifacts/a1/tickets",
                "POST /api/v1/artifacts/a1/tickets",
            ),
            server.requests.map { it.call() },
        )
        assertJson("{}", server.requests[4].bodyText())
        assertJson("""{"version":1}""", server.requests[5].bodyText())
    }

    @Test
    fun anIdThatIsNotAPathSegmentIsRefusedBeforeAnyRequest() = runTest {
        val server = ScriptedServer()
        server.client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.approvals.view("..") }
            assertFailsWith<IllegalArgumentException> { client.waits.answer(".", "approve") }
            assertFailsWith<IllegalArgumentException> { client.pushDevices.delete("") }
        }
        assertEquals(emptyList(), server.requests.map { it.call() })
    }
}
