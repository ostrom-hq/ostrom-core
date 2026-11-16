// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.OnBehalfOf
import run.ostrom.core.api.models.CloudAgentKind
import run.ostrom.core.api.models.CreateCloudSessionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A cloud session's creation under an idempotency key (`runners.cloud.idempotency.v1`). */
class CloudSessionCreateTest {
    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private val cloudSession = """{"id":"cs_1",""" +
        """"name":"n","desiredStatus":"running","epoch":1,"updatedAt":"t",""" +
        """"limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},""" +
        """"owner":"m1","agent":"claude","status":"creating","createdAt":"t"}"""

    @Test
    fun aCreateCarriesItsIdempotencyKeyAndAReplayReturnsTheSession() = runTest {
        val server = ScriptedServer {
            json(cloudSession, HttpStatusCode.Accepted, listOf(Idempotency.REPLAYED_HEADER to "true"))
        }
        val request = CreateCloudSessionRequest(CloudAgentKind.CLAUDE.value, prompt = "Fix it")
        server.client().use { client ->
            val key = Idempotency.newKey()
            assertEquals("cs_1", client.cloudSessions.create(request, idempotencyKey = key).id)
            assertEquals(key, server.requests.single().headers[Idempotency.KEY_HEADER])
            assertFailsWith<IllegalArgumentException> { client.cloudSessions.create(request, idempotencyKey = "a b") }
            client.cloudSessions.create(request)
        }
        assertEquals(2, server.requests.size)
        assertNull(server.requests[1].headers[Idempotency.KEY_HEADER])
        assertJson("""{"agent":"claude","prompt":"Fix it"}""", server.requests[1].bodyText())
    }

    @Test
    fun aWriteOnBehalfOfAMemberNamesThemAndAMalformedNameIsRefusedBeforeAnyRequest() = runTest {
        val server = ScriptedServer { json(cloudSession, HttpStatusCode.Created) }
        val request = CreateCloudSessionRequest(CloudAgentKind.CLAUDE.value)
        server.client().use { client ->
            client.cloudSessions.create(request, onBehalfOf = "ada")
            client.cloudSessions.pause("cs_1", onBehalfOf = "ada")
            client.cloudSessions.resume("cs_1", onBehalfOf = "ada")
            client.cloudSessions.stop("cs_1", onBehalfOf = "ada")
            client.cloudSessions.pause("cs_1")
            assertFailsWith<IllegalArgumentException> { client.cloudSessions.create(request, onBehalfOf = "a b") }
            assertFailsWith<IllegalArgumentException> { client.cloudSessions.stop("cs_1", onBehalfOf = "") }
        }
        assertEquals(5, server.requests.size)
        assertEquals(List(4) { "ada" }, server.requests.take(4).map { it.headers[OnBehalfOf.HEADER] })
        assertNull(server.requests[4].headers[OnBehalfOf.HEADER])
    }

    @Test
    fun theModelAndTheEffortAreSetOnBehalfOfAMemberAndTheListNamesItsOwner() = runTest {
        val server = ScriptedServer { request ->
            if (request.method.value == "GET") json("""{"items":[],"next":null}""") else json(cloudSession)
        }
        server.client().use { client ->
            client.cloudSessions.setModel("cs_1", "opus", onBehalfOf = "ada")
            client.cloudSessions.setEffort("cs_1", "high", onBehalfOf = "ada")
            client.cloudSessions.setModel("cs_1", "opus")
            client.cloudSessions.list(owner = "m1")
            assertFailsWith<IllegalArgumentException> {
                client.cloudSessions.setEffort("cs_1", "high", onBehalfOf = "a b")
            }
        }
        assertEquals(4, server.requests.size)
        assertEquals(listOf("ada", "ada", null), server.requests.take(3).map { it.headers[OnBehalfOf.HEADER] })
        assertJson("""{"model":"opus"}""", server.requests[0].bodyText())
        assertJson("""{"effort":"high"}""", server.requests[1].bodyText())
        assertEquals("m1", server.requests[3].url.parameters["owner"])
    }

    @Test
    fun aSessionReadsWhoCreatedIt() {
        val session = Json.decodeFromString<run.ostrom.core.api.models.CloudSession>(
            """{"id":"cs_1",""" +
                """"name":"n","desiredStatus":"running","epoch":1,"updatedAt":"t",""" +
                """"limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},""" +
                """"owner":"m1","agent":"claude","status":"creating","createdAt":"t",""" +
                """"createdBy":{"initiatorId":"m0","credentialId":null,"effectiveMemberId":"m1"}}""",
        )
        assertEquals("m0", session.createdBy?.initiatorId)
        assertNull(session.createdBy?.credentialId)
        assertEquals("m1", session.createdBy?.effectiveMemberId)
    }
}
