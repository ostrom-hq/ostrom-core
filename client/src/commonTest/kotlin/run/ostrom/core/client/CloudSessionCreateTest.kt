// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.Idempotency
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

    private val cloudSession = """{"id":"cs_1","owner":"m1","agent":"claude","status":"creating","createdAt":"t"}"""

    @Test
    fun aCreateCarriesItsIdempotencyKeyAndAReplayReturnsTheSession() = runTest {
        val server = ScriptedServer {
            json(cloudSession, HttpStatusCode.Accepted, listOf(Idempotency.REPLAYED_HEADER to "true"))
        }
        val request = CreateCloudSessionRequest(CloudAgentKind.CLAUDE, prompt = "Fix it")
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
}
