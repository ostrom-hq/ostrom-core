// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.models.AnswerCloudPermissionRequest
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventTypes
import run.ostrom.core.api.models.CloudInputStatuses
import run.ostrom.core.api.models.CloudQuestionAnswer
import run.ostrom.core.api.models.CloudSessionModes
import run.ostrom.core.api.models.SendCloudMessageRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A conversation session's transcript, messages, permission answers, tasks, model, effort and files. */
class CloudConversationClientTest {

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}" +
        if (url.parameters.isEmpty()) "" else "?" + url.encodedQuery

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private val input = """{"id":"in_1","kind":"message","status":"queued","authorId":"ada","createdAt":"t"}"""
    private val session = """{"id":"cs_1","owner":"ada","agent":"claude","status":"running","createdAt":"t",""" +
        """"mode":"conversation","effort":"high","permissionBypass":true,""" +
        """"waiting":{"reason":"question","requestId":"r1","since":"t","eventSeq":9}}"""

    @Test
    fun theTranscriptIsPagedWithItsCursorsAndFeedAndTheSubagentsListed() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/cloud/sessions/cs_1/agents" -> json(
                    """{"agents":[{"id":"tu_1","state":"running","startedAt":"t","steps":2,"taskId":"k1"}]}""",
                )
                else -> json(
                    """{"events":[{"seq":3,"type":"message.agent","at":"t","epoch":1,"data":{"text":"Hi"},""" +
                        """"feed":"tu_1"}],"firstSeq":1,"lastSeq":9,"nextAfter":3}""",
                )
            }
        }
        server.client().use { client ->
            val page = client.cloudTranscripts.events("cs_1", after = 2, limit = 1, feed = "tu_1")
            assertEquals(CloudEventTypes.AGENT_MESSAGE, page.events.single().type)
            assertEquals(JsonPrimitive("Hi"), page.events.single().data["text"])
            assertEquals("tu_1", page.events.single().feed)
            assertEquals(9L, page.lastSeq)
            assertNull(page.nextBefore)
            client.cloudTranscripts.events("cs_1", before = 5)
            val agent = client.cloudTranscripts.agents("cs_1").agents.single()
            assertEquals("k1", agent.taskId)
            assertEquals("", agent.description)
        }
        assertEquals(
            listOf(
                "GET /api/v1/cloud/sessions/cs_1/events?after=2&limit=1&feed=tu_1",
                "GET /api/v1/cloud/sessions/cs_1/events?before=5",
                "GET /api/v1/cloud/sessions/cs_1/agents",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun theStreamHandsEachEventOverUntilTheSessionEnds() = runTest {
        val body = ": keep-alive\n\n" +
            "id: 4\nevent: turn\ndata: {\"seq\":4,\"type\":\"turn\",\"at\":\"t\",\"epoch\":1," +
            "\"data\":{\"phase\":\"started\"}}\n\n" +
            "id: 5\nevent: something.new\ndata:\n\n" +
            "event: end\ndata: {}\n\n"
        val server = ScriptedServer { stream(body) }
        val seen = mutableListOf<CloudEvent>()
        var opened = false
        val end = server.client().use { client ->
            client.cloudTranscripts.followEvents("cs 1", after = 3, onOpen = { opened = true }) { seen += it }
        }
        assertEquals(StreamEnd.ENDED, end)
        assertEquals(true, opened)
        assertEquals(listOf(4L), seen.map { it.seq })
        val request = server.requests.single()
        assertEquals("/api/v1/cloud/sessions/cs%201/events/stream", request.url.encodedPath)
        assertEquals("3", request.headers["Last-Event-ID"])
    }

    @Test
    fun anUnreadableStreamEventIsMalformedAndANegativeCursorIsRefused() = runTest {
        val server = ScriptedServer { stream("event: turn\ndata: {\"seq\":\"nope\"}\n\n") }
        server.client().use { client ->
            val error = assertFailsWith<OstromException> {
                client.cloudTranscripts.followEvents("cs_1", after = null) { }
            }
            assertEquals(OstromClient.MALFORMED_EVENT, error.type)
            assertEquals(false, error.message.orEmpty().contains("nope"))
            assertFailsWith<IllegalArgumentException> { client.cloudTranscripts.followEvents("cs_1", after = -1) { } }
        }
        assertEquals(1, server.requests.size)
        assertNull(server.requests.single().headers["Last-Event-ID"])
    }

    @Test
    fun messagesAndInterruptsAreSentWithTheirKeysAndInputsRead() = runTest {
        val server = ScriptedServer { request ->
            if (request.url.encodedPath.endsWith("/messages") && request.method.value == "GET") {
                json("""{"inputs":[$input],"nextBefore":"in_0"}""")
            } else {
                json(input)
            }
        }
        server.client().use { client ->
            val sent = client.cloudMessages.send(
                "cs_1",
                SendCloudMessageRequest("Go on", wake = false, expectedLastEventSeq = 9, fileIds = listOf("f1")),
                idempotencyKey = "k-1",
            )
            assertEquals(CloudInputStatuses.QUEUED, sent.status)
            assertEquals(true, sent.wake)
            client.cloudMessages.send("cs_1", SendCloudMessageRequest("Again", clientMessageId = "c-1"))
            assertEquals("in_0", client.cloudMessages.inputs("cs_1", before = "in_9", limit = 10).nextBefore)
            client.cloudMessages.inputs("cs_1")
            client.cloudMessages.input("cs_1", "in_1")
            client.cloudMessages.interrupt("cs_1")
        }
        assertEquals(
            listOf(
                "POST /api/v1/cloud/sessions/cs_1/messages",
                "POST /api/v1/cloud/sessions/cs_1/messages",
                "GET /api/v1/cloud/sessions/cs_1/messages?before=in_9&limit=10",
                "GET /api/v1/cloud/sessions/cs_1/messages",
                "GET /api/v1/cloud/sessions/cs_1/messages/in_1",
                "POST /api/v1/cloud/sessions/cs_1/interrupt",
            ),
            server.requests.map { it.call() },
        )
        val requests = server.requests
        assertEquals("k-1", requests[0].headers["Idempotency-Key"])
        assertNull(requests[1].headers["Idempotency-Key"])
        assertJson(
            """{"text":"Go on","wake":false,"expectedLastEventSeq":9,"fileIds":["f1"]}""",
            requests[0].bodyText(),
        )
        assertJson("""{"text":"Again","clientMessageId":"c-1"}""", requests[1].bodyText())
    }

    @Test
    fun permissionAnswersAndTaskStopsAreSentAndBothKeysAreRefused() = runTest {
        val server = ScriptedServer { json(input) }
        server.client().use { client ->
            client.cloudMessages.answerPermission(
                "cs_1",
                AnswerCloudPermissionRequest(
                    "r1",
                    "allow",
                    answers = listOf(
                        CloudQuestionAnswer(0, options = listOf(1)),
                        CloudQuestionAnswer(1, text = "Blue"),
                    ),
                ),
                idempotencyKey = "a-1",
            )
            client.cloudMessages.answerPermission("cs_1", AnswerCloudPermissionRequest("r2", "deny", message = "No"))
            client.cloudMessages.stopTask("cs_1", "k1")
            assertFailsWith<IllegalArgumentException> {
                client.cloudMessages.send("cs_1", SendCloudMessageRequest("x", clientMessageId = "c"), "k")
            }
        }
        val requests = server.requests
        assertEquals(
            listOf(
                "POST /api/v1/cloud/sessions/cs_1/permissions",
                "POST /api/v1/cloud/sessions/cs_1/permissions",
                "POST /api/v1/cloud/sessions/cs_1/tasks/stop",
            ),
            requests.map { it.call() },
        )
        assertEquals("a-1", requests[0].headers["Idempotency-Key"])
        assertJson(
            """{"requestId":"r1","behavior":"allow","answers":[{"question":0,"options":[1]},""" +
                """{"question":1,"text":"Blue"}]}""",
            requests[0].bodyText(),
        )
        assertJson("""{"requestId":"r2","behavior":"deny","message":"No"}""", requests[1].bodyText())
        assertJson("""{"taskId":"k1"}""", requests[2].bodyText())
    }

    @Test
    fun bypassModelAndEffortAreSetAndTheSessionReadBack() = runTest {
        val server = ScriptedServer { json(session) }
        server.client().use { client ->
            val read = client.cloudSessions.setPermissionBypass("cs_1", true)
            assertEquals(CloudSessionModes.CONVERSATION, read.mode)
            assertEquals(true, read.permissionBypass)
            assertEquals("r1", read.waiting?.requestId)
            assertEquals("high", client.cloudSessions.setModel("cs_1", "opus").effort)
            client.cloudSessions.setEffort("cs_1", "max")
        }
        assertEquals(
            listOf(
                "PUT /api/v1/cloud/sessions/cs_1/permission-bypass",
                "PUT /api/v1/cloud/sessions/cs_1/model",
                "PUT /api/v1/cloud/sessions/cs_1/effort",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"enabled":true}""", server.requests[0].bodyText())
        assertJson("""{"model":"opus"}""", server.requests[1].bodyText())
        assertJson("""{"effort":"max"}""", server.requests[2].bodyText())
    }

    @Test
    fun aFileIsUploadedAsBytesUnderItsNameAndThePolicyRead() = runTest {
        val file = """{"id":"f1","sessionId":"cs_1","name":"shot.png","size":3,"contentType":"image/png",""" +
            """"sha256":"abc","createdAt":"t","expiresAt":"u"}"""
        val server = ScriptedServer { request ->
            if (request.url.encodedPath == "/api/v1/cloud/files/policy") {
                json(
                    """{"available":false,"reason":"file_storage_not_configured","maxFileBytes":1,""" +
                        """"maxMessageBytes":2,"maxFilesPerMessage":5,"types":["image/png"]}""",
                )
            } else {
                json(file)
            }
        }
        server.client().use { client ->
            assertEquals("f1", client.cloudFiles.upload("cs_1", "shot.png", byteArrayOf(1, 2, 3), "u-1").id)
            client.cloudFiles.upload("cs_1", "a b.txt", "hi".encodeToByteArray())
            assertEquals("file_storage_not_configured", client.cloudFiles.policy().reason)
        }
        val (upload, plain, policy) = server.requests
        assertEquals("POST /api/v1/cloud/sessions/cs_1/files?name=shot.png", upload.call())
        assertEquals("u-1", upload.headers["Idempotency-Key"])
        assertEquals("application/octet-stream", upload.body.contentType.toString())
        assertEquals(listOf<Byte>(1, 2, 3), (upload.body as OutgoingContent.ByteArrayContent).bytes().toList())
        assertEquals("a b.txt", plain.url.parameters["name"])
        assertNull(plain.headers["Idempotency-Key"])
        assertEquals("GET /api/v1/cloud/files/policy", policy.call())
    }
}
