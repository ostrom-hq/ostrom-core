// SPDX-License-Identifier: Apache-2.0
// The streamed upload is ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0):
// uploadCloudFile of client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/TeamCodeClient.kt and
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/CloudFileUpload.kt.
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import okio.HashingSink
import okio.blackholeSink
import run.ostrom.core.api.models.CloudFile
import run.ostrom.core.api.models.CloudFilePolicy
import run.ostrom.core.api.models.CloudFiles

/**
 * Files sent with a message to a conversation session (`runners.cloud.files.v1`, TeamCode's cloud files). Reached
 * through [OstromClient.cloudFiles].
 */
public class CloudFilesClient internal constructor(private val transport: Transport) {
    /**
     * Uploads a file for the next message (`POST .../files?name=`, `runners.cloud.files.v1`); name its id in
     * the `fileIds` of a message. A retry with the same [idempotencyKey] and bytes returns the first file.
     */
    public suspend fun upload(
        id: String,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String? = null,
    ): CloudFile =
        transport.send(HttpMethod.Post, "${path(id)}/files", query("name" to name)) {
            idempotencyKey?.let { header(IDEMPOTENCY_KEY, it) }
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.body()

    /**
     * Uploads one file to session [id] (`POST .../files?name=`), streamed from [source] in 64 KiB chunks; name the
     * returned id in a message's `fileIds`. [name] is the file's name, which the server cleans; the server recognizes
     * the type from the bytes.
     *
     * Every attempt sends the same [idempotencyKey], so a retry never stores the file twice. A network failure, `408`,
     * `409 file_upload_in_progress`, `429` other than [CloudFiles.UPLOAD_QUOTA] and `5xx` are retried up to
     * [attempts] times in all, with backoff (1 s doubling to 15 s, or the server's `Retry-After` capped at 30 s);
     * [onRetry] is told before each wait. Other refusals throw [OstromException] with the server's type at once, a
     * source that cannot be read [CloudFileReadException]. The SHA-256 of the bytes sent is compared to the one the
     * server stored: a difference throws [OstromException] with type [FILE_INTEGRITY_FAILED] (status `0`); upload
     * again under a new key then, since the same key returns the stored bytes. [onProgress] runs on the caller's
     * dispatcher with the bytes sent so far, from 0 at the start of each attempt.
     */
    @Suppress("LongParameterList") // TeamCode's uploadCloudFile: the retry and progress hooks have defaults.
    public suspend fun upload(
        id: String,
        name: String,
        source: CloudFileSource,
        idempotencyKey: String,
        attempts: Int = UPLOAD_ATTEMPTS,
        onRetry: (Throwable) -> Unit = {},
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): CloudFile {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none { it.isISOControl() }) {
            "Invalid file name."
        }
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) { "Invalid idempotency key." }
        require(attempts in 1..MAX_ATTEMPTS) { "Use 1 to $MAX_ATTEMPTS attempts." }
        // Progress is reported where the caller runs (a UI thread, the CLI's input loop), not on the engine's writer.
        val caller = currentCoroutineContext().minusKey(Job)
        val report: suspend (Long) -> Unit = { sent -> withContext(caller) { onProgress(sent, source.size) } }
        var wait = FIRST_WAIT_MILLIS
        var attempt = 1
        while (true) {
            val failure = try {
                return send(id, name, source, idempotencyKey, report)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                retryable(error, last = attempt >= attempts)
            }
            onRetry(failure)
            val retryAfter = (failure as? OstromException)?.retryAfterSeconds
            // The server's pause when it gave one, capped so that an upload does not stall for minutes.
            delay(retryAfter?.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)?.times(MILLIS) ?: wait)
            wait = (wait * 2).coerceAtMost(MAX_WAIT_MILLIS)
            attempt++
        }
    }

    /** Whether the organization can send files now, and its limits (`GET /api/v1/cloud/files/policy`). */
    public suspend fun policy(): CloudFilePolicy = transport.json(HttpMethod.Get, "/api/v1/cloud/files/policy")

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"

    /** [error] when another attempt may succeed and one is left; thrown otherwise. */
    private fun retryable(error: Exception, last: Boolean): Exception {
        val transient = when (error) {
            is CloudFileReadException -> false
            is OstromException -> error.transient()
            else -> true
        }
        if (!transient || last) throw error
        return error
    }

    /** One attempt: the file streamed, then the stored file checked against what was sent. */
    private suspend fun send(
        id: String,
        name: String,
        source: CloudFileSource,
        idempotencyKey: String,
        report: suspend (Long) -> Unit,
    ): CloudFile {
        val body = StreamedFile(source, report)
        val response = post(id, name, idempotencyKey, body)
        body.unreadable?.let { throw it }
        val file: CloudFile = response.body()
        if (file.size != source.size || !file.sha256.equals(body.sha256(), ignoreCase = true)) {
            throw OstromException(0, FILE_INTEGRITY_FAILED, "The server stored other bytes than the ones sent.")
        }
        return file
    }

    /** The request of one attempt; a failure of the source wins over how the engine surfaced it. */
    private suspend fun post(id: String, name: String, idempotencyKey: String, body: StreamedFile): HttpResponse = try {
        transport.send(HttpMethod.Post, "${path(id)}/files", query("name" to name)) {
            header(IDEMPOTENCY_KEY, idempotencyKey)
            setBody(body)
            timeout { requestTimeoutMillis = UPLOAD_TIMEOUT_MILLIS }
        }
    } catch (error: CancellationException) {
        // The engine may surface the source's failure as the cancellation of the request; a cancelled caller rethrows
        // its own cancellation here.
        currentCoroutineContext().ensureActive()
        throw body.unreadable ?: error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw body.unreadable ?: error
    }

    /** One attempt's body: [source] read in chunks, counted, hashed and reported as it is written. */
    private class StreamedFile(
        private val source: CloudFileSource,
        private val report: suspend (Long) -> Unit,
    ) : OutgoingContent.WriteChannelContent() {
        private val digest = HashingSink.sha256(blackholeSink())

        /** Set when the source failed: the engine may surface it wrapped, or as the cancellation of the request. */
        var unreadable: CloudFileReadException? = null
            private set

        override val contentLength: Long = source.size
        override val contentType: ContentType = ContentType.Application.OctetStream

        fun sha256(): String = digest.hash.hex()

        override suspend fun writeTo(channel: ByteWriteChannel) {
            try {
                write(channel)
            } catch (error: CloudFileReadException) {
                // The body ends in error, never as a shorter or longer file, whatever the engine does with the throw.
                channel.cancel(error)
                throw error
            }
        }

        private suspend fun write(channel: ByteWriteChannel) {
            report(0)
            val raw = reading { source.open() }
            raw.use { input ->
                val buffer = Buffer()
                var sent = 0L
                while (true) {
                    val count = reading { input.readAtMostTo(buffer, UPLOAD_CHUNK_BYTES) }
                    if (count < 0) break
                    sent += count
                    if (sent > source.size) fail("The file grew while it was sent.")
                    val bytes = buffer.readByteArray()
                    digest.write(okio.Buffer().write(bytes), bytes.size.toLong())
                    channel.writeFully(bytes)
                    report(sent)
                }
                if (sent != source.size) fail("The file changed while it was sent.")
            }
            channel.flush()
        }

        private inline fun <T> reading(read: () -> T): T = try {
            read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail("The file cannot be read.", error)
        }

        private fun fail(message: String, cause: Throwable? = null): Nothing =
            throw CloudFileReadException(message, cause).also { unreadable = it }
    }

    /** The defaults and the failure types of [upload]. */
    public companion object {
        /** Attempts of a streamed [upload] by default, the first included. */
        public const val UPLOAD_ATTEMPTS: Int = 5

        /** The stored file's size or SHA-256 differs from what was sent ([OstromException.type], status `0`). */
        public const val FILE_INTEGRITY_FAILED: String = "file_integrity_failed"

        /** `409`: the first upload of this key is still being stored; retried. */
        public const val FILE_UPLOAD_IN_PROGRESS: String = "file_upload_in_progress"

        private const val MAX_ATTEMPTS = 10
        private const val MAX_NAME_CHARS = 1024
        private const val UPLOAD_CHUNK_BYTES = 64L * 1024

        /** 100 MiB, the largest file an organization may allow, over a slow link: one attempt. */
        private const val UPLOAD_TIMEOUT_MILLIS = 15 * 60_000L
        private const val FIRST_WAIT_MILLIS = 1_000L
        private const val MAX_WAIT_MILLIS = 15_000L
        private const val MAX_RETRY_AFTER_SECONDS = 30L
        private const val MILLIS = 1_000L
        private const val STATUS_TIMEOUT = 408
        private const val STATUS_CONFLICT = 409
        private const val STATUS_TOO_MANY = 429
        private const val STATUS_CLASS = 100
        private const val SERVER_ERROR_CLASS = 5

        /** As the server takes it: 1 to 200 visible ASCII characters. */
        private val IDEMPOTENCY_KEY_PATTERN = Regex("[!-~]{1,200}")

        private fun OstromException.transient(): Boolean =
            status == STATUS_TIMEOUT || status / STATUS_CLASS == SERVER_ERROR_CLASS ||
                (status == STATUS_TOO_MANY && type != CloudFiles.UPLOAD_QUOTA) ||
                (status == STATUS_CONFLICT && type == FILE_UPLOAD_IN_PROGRESS)
    }
}

/**
 * The bytes of a file sent to a session ([CloudFilesClient.upload]): exactly [size] bytes, read from the start each
 * time [open] is called, since a retry sends them again. Each source is closed after its attempt.
 */
public class CloudFileSource(public val size: Long, public val open: () -> RawSource) {
    init {
        require(size >= 0) { "A file size cannot be negative." }
    }

    /** Sources of bytes already at hand. */
    public companion object {
        /** Bytes already in memory. */
        public fun of(bytes: ByteArray): CloudFileSource =
            CloudFileSource(bytes.size.toLong()) { Buffer().apply { write(bytes) } }
    }
}

/** The [CloudFileSource] could not be read, or did not hold its announced size; an upload never retries it. */
public class CloudFileReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The types an organization may allow for files sent to sessions, as `GET /cloud/files/policy` lists them, by file
 * extension. The server recognizes the type from the bytes; this only guesses it before sending, for a local check.
 * Any other UTF-8 text is taken as `text/plain`.
 */
public object CloudFileTypes {
    private val byExtension = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
        "gif" to "image/gif", "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown",
        "markdown" to "text/markdown", "csv" to "text/csv", "json" to "application/json",
    )

    /** The extensions [forName] knows, lowercase and without the dot. */
    public val extensions: Set<String> = byExtension.keys

    /** The type of [name] by its extension (any case), or null for an extension this list does not know. */
    public fun forName(name: String): String? = byExtension[name.substringAfterLast('.', "").lowercase()]
}
