// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0):
// client/src/jvmTest/kotlin/me/nathanfallet/teamcode/client/CloudFileUploadTest.kt.
package run.ostrom.core.client

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.FileNotFoundException
import okio.ByteString.Companion.toByteString
import run.ostrom.core.api.models.CloudFiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The streamed upload of a file to a session (`runners.cloud.files.v1`), against a scripted server. */
class CloudFileUploadTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        ByteArray(300_000) { (it % 251).toByte() }

    private fun sha(bytes: ByteArray) = bytes.toByteString().sha256().hex()

    private fun fileView(bytes: ByteArray, sha256: String = sha(bytes), size: Int = bytes.size) =
        """{"id":"f1","sessionId":"cs_1","name":"a.png","size":$size,"contentType":"image/png",""" +
            """"sha256":"$sha256","createdAt":"t","expiresAt":"u"}"""

    private fun retryAfter(seconds: String) = listOf(HttpHeaders.RetryAfter to seconds)

    private fun error(code: String) = """{"error":{"type":"$code","message":"No."}}"""

    /** A server that reads each body whole, as a server does, and keeps it beside its request. */
    private class Reading(val answer: suspend MockRequestHandleScope.(Int) -> HttpResponseData) {
        val bodies = mutableListOf<ByteArray>()
        val server = ScriptedServer { request ->
            bodies += request.body.received()
            answer(bodies.size)
        }
        val requests: List<HttpRequestData> get() = server.requests
    }

    @Test
    fun aFileIsStreamedWithItsNameKeyAndProgressAndCheckedAgainstTheStoredDigest() = runTest {
        val progress = mutableListOf<Long>()
        val server = Reading { json(fileView(png), HttpStatusCode.Created) }
        server.server.client().use { client ->
            val source = CloudFileSource.of(png)
            val file = client.cloudFiles.upload("cs_1", "my shot.png", source, "key-1") { sent, total ->
                assertEquals(png.size.toLong(), total)
                progress += sent
            }
            assertEquals("f1", file.id)
        }
        val request = server.requests.single()
        assertEquals("/api/v1/cloud/sessions/cs_1/files", request.url.encodedPath)
        assertEquals("my shot.png", request.url.parameters["name"])
        assertEquals("key-1", request.headers["Idempotency-Key"])
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        assertEquals(png.size.toLong(), request.body.contentLength, "The size is announced")
        assertEquals("application/octet-stream", request.body.contentType.toString())
        assertContentEquals(png, server.bodies.single())
        // From 0, in chunks of 64 KiB, up to the whole file.
        assertEquals(0L, progress.first())
        assertEquals(png.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertTrue(progress.size >= 5, progress.toString())
    }

    @Test
    fun aSourceIsReadAgainForEachAttemptWithTheSameKeyAndAReplayIsAccepted() = runTest {
        val retries = mutableListOf<Throwable>()
        var opened = 0
        val source = CloudFileSource(png.size.toLong()) {
            opened++
            Buffer().apply { write(png) }
        }
        val server = Reading { attempt ->
            when (attempt) {
                1 -> json(error(CloudFiles.STORAGE_UNAVAILABLE), HttpStatusCode.ServiceUnavailable)
                2 -> throw FileNotFoundException("connection reset")
                3 -> json(error("rate_limited"), HttpStatusCode.TooManyRequests, retryAfter("2"))
                4 -> json(error("file_upload_in_progress"), HttpStatusCode.Conflict, retryAfter("60"))
                5 -> json(error("timeout"), HttpStatusCode.RequestTimeout, retryAfter("0"))
                // The server already stored it under this key: it answers the first upload again.
                else -> json(fileView(png), extraHeaders = listOf("Idempotency-Replayed" to "true"))
            }
        }
        server.server.client().use { client ->
            val onRetry: (Throwable) -> Unit = { retries += it }
            val file = client.cloudFiles.upload("cs_1", "a.png", source, "same-key", attempts = 6, onRetry = onRetry)
            assertEquals(png.size.toLong(), file.size)
        }
        assertEquals(6, server.requests.size)
        assertEquals(6, opened)
        assertEquals(listOf("same-key"), server.requests.map { it.headers["Idempotency-Key"] }.distinct())
        server.bodies.forEach { assertContentEquals(png, it) }
        assertEquals(5, retries.size)
        assertEquals(CloudFiles.STORAGE_UNAVAILABLE, (retries[0] as OstromException).type)
    }

    @Test
    fun refusalsKeepTheServerTypeAndAreNotRetried() = runTest {
        val refusals = listOf(
            Triple(HttpStatusCode.PayloadTooLarge, CloudFiles.FILE_TOO_LARGE, null),
            Triple(HttpStatusCode.UnsupportedMediaType, CloudFiles.FILE_TYPE_NOT_ALLOWED, null),
            Triple(HttpStatusCode.TooManyRequests, CloudFiles.UPLOAD_QUOTA, 3_600L),
            Triple(HttpStatusCode.Conflict, CloudFiles.STORAGE_NOT_CONFIGURED, null),
            Triple(HttpStatusCode.BadRequest, "empty_file", null),
            Triple(HttpStatusCode.UnprocessableEntity, "idempotency_conflict", null),
            Triple(HttpStatusCode.Forbidden, "forbidden", null),
        )
        for ((status, code, retryAfter) in refusals) {
            val headers = listOfNotNull(retryAfter?.let { HttpHeaders.RetryAfter to it.toString() })
            val server = Reading { json(error(code), status, headers) }
            server.server.client().use { client ->
                val failure = assertFailsWith<OstromException>(code) {
                    client.cloudFiles.upload("cs_1", "a.png", CloudFileSource.of(png), "k")
                }
                assertEquals(code, failure.type)
                assertEquals(status.value, failure.status)
                assertEquals(retryAfter, failure.retryAfterSeconds)
            }
            assertEquals(1, server.requests.size, "$code is not retried")
        }
    }

    @Test
    fun transientFailuresStopAfterTheLastAttempt() = runTest {
        val unavailable = Reading { json(error(CloudFiles.STORAGE_UNAVAILABLE), HttpStatusCode.ServiceUnavailable) }
        unavailable.server.client().use { client ->
            val failure = assertFailsWith<OstromException> {
                client.cloudFiles.upload("cs_1", "a.png", CloudFileSource.of(png), "k", attempts = 2)
            }
            assertEquals(503, failure.status)
        }
        assertEquals(2, unavailable.requests.size)
        val offline = Reading { throw FileNotFoundException("unreachable") }
        offline.server.client().use { client ->
            assertFailsWith<FileNotFoundException> {
                client.cloudFiles.upload("cs_1", "a.png", CloudFileSource.of(png), "k", attempts = 3)
            }
        }
        assertEquals(3, offline.requests.size)
    }

    @Test
    fun otherStoredBytesAreNeverUsed() = runTest {
        for (view in listOf(fileView(png, sha256 = "0".repeat(64)), fileView(png, size = 1))) {
            val server = Reading { json(view, HttpStatusCode.Created) }
            server.server.client().use { client ->
                val failure = assertFailsWith<OstromException> {
                    client.cloudFiles.upload("cs_1", "a.png", CloudFileSource.of(png), "k")
                }
                assertEquals(CloudFilesClient.FILE_INTEGRITY_FAILED, failure.type)
                assertEquals(0, failure.status)
            }
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun aSourceThatDoesNotHoldItsSizeOrCannotBeReadFailsWithoutARetry() = runTest {
        val sources = listOf(
            CloudFileSource(png.size + 10L) { Buffer().apply { write(png) } },
            CloudFileSource(png.size - 10L) { Buffer().apply { write(png) } },
            CloudFileSource(png.size.toLong()) { throw FileNotFoundException("gone") },
            CloudFileSource(png.size.toLong()) { Failing() },
        )
        for (source in sources) {
            val server = Reading { json(fileView(png), HttpStatusCode.Created) }
            server.server.client().use { client ->
                assertFailsWith<CloudFileReadException> { client.cloudFiles.upload("cs_1", "a.png", source, "k") }
            }
            assertEquals(1, server.requests.size, "Never retried")
        }
    }

    @Test
    fun aSourceFailureSurfacedAsACancellationIsTheSourceFailure() = runTest {
        // An engine that cancels the request when its body fails: the source's failure is what the caller sees.
        val server = ScriptedServer { request ->
            request.body.received()
            throw CancellationException("The request was cancelled.")
        }
        server.client().use { client ->
            val source = CloudFileSource(png.size + 1L) { Buffer().apply { write(png) } }
            assertFailsWith<CloudFileReadException> { client.cloudFiles.upload("cs_1", "a.png", source, "k") }
        }
        // An engine that fails the request with its own error once the body failed.
        val failing = ScriptedServer { request ->
            request.body.received()
            throw FileNotFoundException("The request failed.")
        }
        failing.client().use { client ->
            val source = CloudFileSource(png.size + 1L) { Buffer().apply { write(png) } }
            assertFailsWith<CloudFileReadException> { client.cloudFiles.upload("cs_1", "a.png", source, "k") }
        }
        assertEquals(1, failing.requests.size)
        val cancelled = ScriptedServer { throw CancellationException("The request was cancelled.") }
        cancelled.client().use { client ->
            assertFailsWith<CancellationException> {
                client.cloudFiles.upload("cs_1", "a.png", CloudFileSource.of(png), "k")
            }
        }
    }

    @Test
    fun invalidArgumentsAreRefusedBeforeAnyRequest() = runTest {
        val server = ScriptedServer()
        server.client().use { client ->
            val source = CloudFileSource.of(png)
            for (name in listOf(" ", "a\nb", "x".repeat(1025))) {
                assertFailsWith<IllegalArgumentException> { client.cloudFiles.upload("cs_1", name, source, "k") }
            }
            assertFailsWith<IllegalArgumentException> { client.cloudFiles.upload("cs_1", "a", source, "a b") }
            assertFailsWith<IllegalArgumentException> { client.cloudFiles.upload("cs_1", "a", source, "") }
            for (attempts in listOf(0, 11)) {
                assertFailsWith<IllegalArgumentException> {
                    client.cloudFiles.upload("cs_1", "a", source, "k", attempts)
                }
            }
        }
        assertTrue(server.requests.isEmpty())
        assertFailsWith<IllegalArgumentException> { CloudFileSource(-1) { Buffer() } }
    }

    @Test
    fun typesAreGuessedByExtension() {
        assertEquals("image/jpeg", CloudFileTypes.forName("Photo.JPG"))
        assertEquals("text/markdown", CloudFileTypes.forName("notes.md"))
        assertNull(CloudFileTypes.forName("build.log"))
        assertNull(CloudFileTypes.forName("png"))
        assertNull(CloudFileTypes.forName("trailing."))
        assertTrue("pdf" in CloudFileTypes.extensions)
    }

    /** A source whose first read fails. */
    private class Failing : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = throw FileNotFoundException("unreadable")
        override fun close() = Unit
    }
}

/** The body as a server reads it: a writer that fails ends it in error instead of leaving it open. */
internal suspend fun OutgoingContent.received(): ByteArray =
    if (this !is OutgoingContent.WriteChannelContent) {
        toByteArray()
    } else {
        coroutineScope {
            val channel = ByteChannel()
            launch {
                try {
                    writeTo(channel)
                    channel.close()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    channel.cancel(error)
                }
            }
            try {
                channel.toByteArray()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
    }
