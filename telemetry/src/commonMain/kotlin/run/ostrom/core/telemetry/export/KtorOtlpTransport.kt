// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.export

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Sends OTLP/HTTP protobuf with a Ktor client. The caller owns the client and its engine (CIO or Curl on the server,
 * the platform default in the CLI) and gives telemetry its own client, so a hung backend never holds a connection the
 * product needs (CONVENTIONS 10.2).
 *
 * @param client A client with a request timeout installed.
 * @param endpoint Where to send.
 */
public class KtorOtlpTransport(
    private val client: HttpClient,
    private val endpoint: OtlpEndpoint,
) : OtlpTransport {
    override suspend fun send(signal: Signal, body: ByteArray): ExportResult {
        val response = try {
            client.post(endpoint.url(signal)) {
                headers { endpoint.headers.forEach { (name, value) -> append(name, value) } }
                contentType(PROTOBUF)
                setBody(body)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A network failure (refused, reset, timeout): retryable. The cause is not logged, it can name the host's
            // credentials in a URL.
            null
        }
        return if (response == null) ExportResult.Retryable() else classify(response)
    }

    private fun classify(response: HttpResponse): ExportResult = when (val status = response.status.value) {
        in RETRYABLE -> ExportResult.Retryable(
            response.headers[HttpHeaders.RetryAfter]?.let { it.trim().toLongOrNull() }?.takeIf { it >= 0 }?.seconds,
        )
        else -> if (status / CLASS == SUCCESS_CLASS) ExportResult.Success else ExportResult.Rejected(status)
    }
}

private val PROTOBUF = ContentType("application", "x-protobuf")

// OTLP/HTTP specification, "Retryable Response Codes".
private val RETRYABLE = setOf(429, 502, 503, 504)
private const val CLASS = 100
private const val SUCCESS_CLASS = 2
