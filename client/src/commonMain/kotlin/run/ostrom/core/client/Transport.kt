// SPDX-License-Identifier: Apache-2.0
// The request, failure and stream handling of teamcode's TeamCodeClient (https://github.com/nathanfallet/teamcode,
// Apache-2.0, client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/TeamCodeClient.kt), for Ostrom's contract.
package run.ostrom.core.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import run.ostrom.core.api.OstromJson

/**
 * What every call of one [OstromClient] shares: the origin, the credentials read at each call, and the HTTP client.
 * Its subclients build paths with [segment] and call [json], [jsonBody] or [noContent].
 */
internal class Transport(
    val origin: String,
    private val token: suspend () -> String?,
    private val organization: suspend () -> String?,
    private val userAgent: String?,
    val http: HttpClient,
) {
    /**
     * Sends one call to [path] (already encoded, from `/api/v1`) with [query], and returns its successful answer.
     * Throws [OstromException] for any other status.
     */
    suspend fun send(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        val response = http.request("$origin$path") {
            this.method = method
            for ((name, value) in query) parameter(name, value)
            authorize()
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            configure()
        }
        if (!response.status.isSuccess()) throw failure(response)
        return response
    }

    /** A call answered with a JSON body of type [R]. */
    suspend inline fun <reified R> json(
        method: HttpMethod,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): R = send(method, path, query).body()

    /** A call that sends [body] as JSON and is answered with a JSON body of type [R]. */
    suspend inline fun <reified B, reified R> jsonBody(method: HttpMethod, path: String, body: B): R =
        send(method, path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /** A call whose answer carries nothing to read (`204`). */
    suspend fun noContent(method: HttpMethod, path: String) {
        send(method, path)
    }

    /**
     * One SSE request to [route]: [after] as `Last-Event-ID`, a 60 s silence limit (the server sends keep-alives more
     * often), `denied` (and the route's end event when it has one) ending it, unnamed frames skipped and every other
     * frame handed to [onFrame]. [onOpen] runs once the server accepted the stream, before any frame. A stream that
     * closes or breaks a bound throws [OstromException] with status `0`; a refusal throws it with its status and type.
     */
    suspend fun follow(
        route: StreamRoute,
        after: Long?,
        onOpen: suspend () -> Unit,
        onFrame: suspend (event: String, data: String) -> Unit,
    ): StreamEnd = http.prepareGet("$origin${route.path}") {
        for ((name, value) in route.query) parameter(name, value)
        authorize()
        after?.let { header(LAST_EVENT_ID, it.toString()) }
        header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = STREAM_SILENCE_MILLIS
        }
    }.execute { response ->
        if (!response.status.isSuccess()) throw failure(response)
        onOpen()
        // Only read once a frame ended the stream: the reader throws when the stream closes first.
        var end = StreamEnd.DENIED
        readServerSentEvents(response.bodyAsChannel()) { frame ->
            val name = frame.event
            when {
                name == null -> true
                name == DENIED -> false
                name == route.endEvent -> {
                    end = StreamEnd.ENDED
                    false
                }
                else -> {
                    onFrame(name, frame.data)
                    true
                }
            }
        }
        end
    }

    private suspend fun HttpRequestBuilder.authorize() {
        token()?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
        organization()?.takeIf { it.isNotBlank() }?.let { header(ORGANIZATION_HEADER, it) }
        userAgent?.let { header(HttpHeaders.UserAgent, it) }
    }

    /** The failure of [response]: its `ApiError` when the body is one, never the rest of the body. */
    private suspend fun failure(response: HttpResponse): OstromException {
        val status = response.status.value
        // Bounded: an error body is small, and a proxy's large or slow page must not be buffered whole.
        val bytes = response.bodyAsChannel().readRemaining(MAX_ERROR_BODY + 1L).readByteArray()
        val detail = if (bytes.size > MAX_ERROR_BODY) null else errorDetail(bytes.decodeToString())
        return OstromException(
            status = status,
            type = detail?.type,
            message = detail?.message ?: "Ostrom answered HTTP $status.",
            retryAfterSeconds = retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]),
            requestBound = detail?.requestBound == true,
        )
    }

    private fun errorDetail(text: String): ErrorBody.Detail? = try {
        OstromJson.decodeFromString(ErrorBody.serializer(), text).error.takeIf { ERROR_TYPE.matches(it.type) }
    } catch (_: IllegalArgumentException) {
        // Not an ApiError (a proxy's page, an empty body): SerializationException is an IllegalArgumentException, and
        // its message quotes the body, so nothing of it is kept.
        null
    }

    /**
     * The contract's `ApiError`, with the `error.requestBound` of a `step_up_required` (`StepUpRequired` in
     * api/openapi.yaml), read on its own since `ErrorDetail` does not carry it.
     */
    @Serializable
    private class ErrorBody(val error: Detail) {
        @Serializable
        class Detail(val type: String, val message: String? = null, val requestBound: Boolean = false)
    }

    companion object {
        /** The header naming the organization a call acts in (`Ostrom-Org`). */
        const val ORGANIZATION_HEADER: String = "Ostrom-Org"
        private const val LAST_EVENT_ID = "Last-Event-ID"
        private const val DENIED = "denied"
        private const val STREAM_SILENCE_MILLIS = 60_000L
        private const val MAX_ERROR_BODY = 16 * 1024
        private val ERROR_TYPE = Regex("[a-z0-9_]{1,64}")
    }
}

/**
 * A followed event stream: its [path] (encoded, from `/api/v1`), its [query], and the [endEvent] that ends it when it
 * has one (a feed that never ends has none).
 */
internal class StreamRoute(
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val endEvent: String? = null,
)

/** How a followed event stream ended without a failure. */
public enum class StreamEnd {
    /** The stream's own end event: everything was sent. */
    ENDED,

    /** `event: denied`: the server stopped accepting the credential. */
    DENIED,
}

/** The query parameters of a call: the pairs whose value is set, in order. */
internal fun query(vararg parameters: Pair<String, String?>): List<Pair<String, String>> =
    parameters.mapNotNull { (name, value) -> value?.let { name to it } }

/** `Retry-After` as delta seconds; HTTP dates and malformed values are ignored. */
internal fun retryAfterSeconds(value: String?): Long? {
    val text = value?.trim().orEmpty()
    return if (text.length in 1..MAX_RETRY_AFTER_DIGITS && text.all { it in '0'..'9' }) text.toLong() else null
}

private const val MAX_RETRY_AFTER_DIGITS = 7

/**
 * [value] as one encoded path segment: every byte but the unreserved characters of RFC 3986 percent-encoded (`/`, `%`
 * and `?` included). Empty, `.` and `..` are refused: URL resolution would turn them into another route and send the
 * call, with its token, there.
 */
internal fun segment(value: String): String {
    require(value.isNotEmpty() && value != "." && value != "..") { "Not a path segment." }
    return buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (char.isLetterOrDigit() && code < ASCII_LIMIT || char in UNRESERVED_MARKS) {
                append(char)
            } else {
                append('%').append(HEX[code shr NIBBLE]).append(HEX[code and NIBBLE_MASK])
            }
        }
    }
}

private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0x0F
private const val HEX = "0123456789ABCDEF"
private const val UNRESERVED_MARKS = "-._~"
