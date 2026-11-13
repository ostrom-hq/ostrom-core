// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The organization's feed: event types, gaps, filters and malformed frames. */
class EventsClientTest {

    @Test
    fun eventsReachTheCallerWithTheirJsonAndAGapComesBeforeWhatFollows() = runTest {
        val body = "id: 7\nevent: gap\ndata: {\"oldestSeq\":120}\n\n" +
            "id: 121\nevent: approval.created\ndata: {\"id\":\"apr_1\"}\n\n" +
            "event: ping\n\n" +
            "id: 122\nevent: something.new\ndata: 3\n\n" +
            "event: denied\ndata: {}\n\n"
        val server = ScriptedServer { stream(body) }
        val seen = mutableListOf<String>()
        var opened = false
        val end = server.client().use { client ->
            client.events.follow(
                after = 7,
                types = setOf("wait.answered", "approval.created"),
                onGap = { seen += "gap $it" },
                onOpen = { opened = true },
            ) { seen += "${it.type} ${it.data}" }
        }
        assertEquals(StreamEnd.DENIED, end)
        assertEquals(true, opened)
        assertEquals(listOf("gap 120", "approval.created {\"id\":\"apr_1\"}", "something.new 3"), seen)
        val request = server.requests.single()
        assertEquals("approval.created,wait.answered", request.url.parameters["types"])
        assertEquals("7", request.headers["Last-Event-ID"])
    }

    @Test
    fun theDefaultsFollowEverythingFromNowAndIgnoreGaps() = runTest {
        val body = "event: gap\ndata: {\"oldestSeq\":1}\n\n" +
            "event: wait.answered\ndata: {\"a\":1}\n\nevent: denied\ndata: {}\n\n"
        val server = ScriptedServer { stream(body) }
        val seen = mutableListOf<StreamEvent>()
        server.client().use { client -> client.events.follow(after = null) { seen += it } }
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), seen.single().data)
        assertEquals("StreamEvent(wait.answered)", seen.single().toString())
        val request = server.requests.single()
        assertNull(request.url.parameters["types"])
        assertNull(request.headers["Last-Event-ID"])
    }

    @Test
    fun anUnreadableEventOrGapIsMalformed() = runTest {
        for (frame in listOf("event: x\ndata: {nope\n\n", "event: gap\ndata: {\"oldest\":1}\n\n")) {
            val server = ScriptedServer { stream(frame) }
            val error = server.client().use { client ->
                assertFailsWith<OstromException> { client.events.follow(after = 0) { } }
            }
            assertEquals(OstromClient.MALFORMED_EVENT, error.type)
            assertEquals(false, error.message.orEmpty().contains("nope"))
        }
    }

    @Test
    fun aNegativeCursorOrABadTypeIsRefusedBeforeAnyRequest() = runTest {
        val server = ScriptedServer()
        server.client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.events.follow(after = -1) { } }
            val tooMany = (0..64).map { "t$it" }.toSet()
            for (types in listOf(setOf("gap"), setOf("denied"), setOf("a b"), setOf(""), tooMany)) {
                assertFailsWith<IllegalArgumentException> { client.events.follow(after = 0, types = types) { } }
            }
        }
        assertEquals(0, server.requests.size)
    }
}
