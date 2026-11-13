// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0):
// client/src/commonTest/kotlin/me/nathanfallet/teamcode/client/EventStreamTest.kt.
package run.ostrom.core.client

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.fail

/** The shared SSE reader and the stream request every followed feed uses, against a scripted server. */
class EventStreamTest {

    private fun event(seq: Long, type: String = "approval.created") =
        "id: $seq\nevent: $type\ndata: {\"seq\":$seq}\n\n"

    /** Follows [path] like a feed of the client would, collecting the data of each frame. */
    private suspend fun OstromClient.follow(
        after: Long?,
        endEvent: String? = null,
        query: List<Pair<String, String>> = emptyList(),
        onOpen: suspend () -> Unit = {},
        onFrame: suspend (ServerSentEvent) -> Unit,
    ): StreamEnd = transport.follow(StreamRoute("/api/v1/events", query, endEvent), after, onOpen) { event, data ->
        onFrame(ServerSentEvent(event, data))
    }

    @Test
    fun theFeedDeliversFramesInOrderSkipsKeepAlivesAndSendsItsCursor() = runTest {
        val body = "retry: 2000\n\n" + event(4) + ": keep-alive\n\n" + event(5, "wait.answered") +
            "event: denied\ndata: {\"code\":\"unauthorized\"}\n\n"
        val server = ScriptedServer { stream(body) }
        val seen = mutableListOf<String>()
        val end = server.client(organization = "org-1").use { client ->
            client.follow(after = 3, query = listOf("types" to "a,b")) { seen += "${it.event} ${it.data}" }
        }
        assertEquals(StreamEnd.DENIED, end)
        assertEquals(listOf("approval.created {\"seq\":4}", "wait.answered {\"seq\":5}"), seen)
        val request = server.requests.single()
        assertEquals("/api/v1/events", request.url.encodedPath)
        assertEquals("a,b", request.url.parameters["types"])
        assertEquals("3", request.headers["Last-Event-ID"])
        assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        assertEquals("org-1", request.headers["Ostrom-Org"])
    }

    @Test
    fun theFeedStartsFromNowWithoutACursor() = runTest {
        val server = ScriptedServer { stream("event: denied\ndata: {}\n\n") }
        server.client().use { client -> client.follow(after = null) { } }
        val request = server.requests.single()
        assertNull(request.headers["Last-Event-ID"])
        assertNull(request.url.parameters["types"])
    }

    @Test
    fun onOpenRunsOnceAcceptedBeforeAnyFrameAndNeverForARefusal() = runTest {
        val order = mutableListOf<String>()
        val server = ScriptedServer { stream("retry: 2000\n\n" + event(3) + "event: denied\ndata: {}\n\n") }
        server.client().use { client ->
            client.follow(after = 1, onOpen = { order += "open" }) { order += "frame ${it.event}" }
        }
        assertEquals(listOf("open", "frame approval.created"), order)
        val refused = ScriptedServer {
            json("""{"error":{"type":"invalid_cursor","message":"Ahead."}}""", HttpStatusCode.BadRequest)
        }
        val error = refused.client().use { client ->
            assertFailsWith<OstromException> {
                client.follow(after = 999, onOpen = { fail("A refused feed never opens.") }) { fail("No frame.") }
            }
        }
        assertEquals(400, error.status)
        assertEquals("invalid_cursor", error.type)
    }

    @Test
    fun aCutFeedResumesAfterTheLastHandledEventWithoutLossOrDuplicate() = runTest {
        // The first connection dies in the middle of event 3; the server replays from the cursor it is sent.
        val cut = event(1) + event(2) + event(3).take(20)
        val bodies = ArrayDeque(listOf(cut, event(3) + event(4) + "event: denied\n\n"))
        val server = ScriptedServer { stream(bodies.removeFirst()) }
        val seen = mutableListOf<Long>()
        var cursor = 0L
        var attempts = 0
        server.client().use { client ->
            var end: StreamEnd? = null
            while (end == null) {
                attempts++
                end = try {
                    client.follow(after = cursor) { frame ->
                        cursor = frame.data.substringAfter(":").trimEnd('}').toLong()
                        seen += cursor
                    }
                } catch (error: OstromException) {
                    assertEquals(0, error.status)
                    assertNull(error.type)
                    null
                }
            }
            assertEquals(StreamEnd.DENIED, end)
        }
        assertEquals(2, attempts)
        assertEquals(listOf(1L, 2L, 3L, 4L), seen)
        assertEquals(listOf("0", "2"), server.requests.map { it.headers["Last-Event-ID"] })
    }

    @Test
    fun aStreamWithAnEndEventEndsOnItAndUnnamedFramesAreIgnored() = runTest {
        val texts = mutableListOf<String>()
        val body = "data: {\"ignored\":true}\n\n" +
            "id: 9\nevent: message\ndata: {\"seq\":9,\n" + "data:\"text\":\"two lines\"}\n\n" +
            "event: end\ndata: {}\n\n" + event(10)
        val server = ScriptedServer { stream(body) }
        val end = server.client().use { client -> client.follow(after = 0, endEvent = "end") { texts += it.data } }
        assertEquals(StreamEnd.ENDED, end)
        assertEquals(listOf("{\"seq\":9,\n\"text\":\"two lines\"}"), texts)
    }

    @Test
    fun aFeedWithoutAnEndEventTreatsEndAsAnOrdinaryFrame() = runTest {
        val server = ScriptedServer { stream("event: end\ndata: \n\n" + event(2) + "event: denied\ndata: {}\n\n") }
        val names = mutableListOf<String?>()
        val end = server.client().use { client -> client.follow(after = 1) { names += it.event } }
        assertEquals(StreamEnd.DENIED, end)
        assertEquals(listOf<String?>("end", "approval.created"), names)
    }

    @Test
    fun aClosedStreamThrowsSoTheCallerReconnects() = runTest {
        val server = ScriptedServer { stream(event(1)) }
        val error = server.client().use { client -> assertFailsWith<OstromException> { client.follow(after = 0) { } } }
        assertEquals(0, error.status)
        assertNull(error.type) // Transient: reconnect.
        assertEquals("The event stream closed.", error.message)
    }

    @Test
    fun theReaderBoundsTheDataOfOneEvent() = runTest {
        val body = "event: message\n" + "data: ${"x".repeat(100)}\n".repeat(20) + "\n"
        val error = assertFailsWith<OstromException> {
            readServerSentEvents(ByteReadChannel(body), maxData = 1_000) { true }
        }
        assertEquals(0, error.status)
        assertEquals(OstromClient.MALFORMED_EVENT, error.type)
    }

    @Test
    fun invalidUtf8IsDecodedWithAReplacementCharacter() = runTest {
        val body = "event: message\ndata: ".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28) +
            "\n\n".encodeToByteArray()
        val frames = mutableListOf<ServerSentEvent>()
        readServerSentEvents(ByteReadChannel(body)) { frames += it; false }
        assertEquals("�(", frames.single().data)
    }

    @Test
    fun theReaderParsesFieldsWithoutASpaceOrAValue() = runTest {
        val frames = mutableListOf<ServerSentEvent>()
        val body = "id:7\nevent:gap\ndata\ndata:{}\n\nevent:\ndata: unnamed\n\n\n\nevent: stop\n\n"
        readServerSentEvents(ByteReadChannel(body)) { frames += it; it.event != "stop" }
        assertEquals("gap", frames[0].event)
        assertEquals("\n{}", frames[0].data)
        // An empty event name is no name: the frame is an unnamed one. Blank lines alone dispatch nothing.
        assertNull(frames[1].event)
        assertEquals(3, frames.size)
    }

    @Test
    fun anOverLongLineIsAMalformedEvent() = runTest {
        val error = assertFailsWith<OstromException> {
            readServerSentEvents(ByteReadChannel("data: ${"x".repeat(200)}\n\n"), maxLine = 100) { true }
        }
        assertEquals(0, error.status)
        assertEquals(OstromClient.MALFORMED_EVENT, error.type)
    }

    @Test
    fun aConnectionFailureReachesTheCallerAsTheEngineReportsIt() = runTest {
        val server = ScriptedServer { throw IllegalStateException("offline") }
        val error = server.client().use { client ->
            assertFailsWith<IllegalStateException> { client.follow(after = 1) { } }
        }
        assertEquals("offline", error.message)
    }

    @Test
    fun theHandlerOwnFailureIsNotDisguisedAsANetworkFailure() = runTest {
        val server = ScriptedServer { stream(event(2)) }
        val error = server.client().use { client ->
            assertFailsWith<IllegalStateException> { client.follow(after = 1) { error("handler bug") } }
        }
        assertEquals("handler bug", error.message)
    }
}
