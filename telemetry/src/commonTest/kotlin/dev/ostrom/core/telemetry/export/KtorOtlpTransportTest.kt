package dev.ostrom.core.telemetry.export

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class KtorOtlpTransportTest {
    private val endpoint = OtlpEndpoint(
        baseUrl = "https://otlp.example.test/otlp/",
        headers = mapOf("Authorization" to "Basic c2VjcmV0"),
    )

    private fun transport(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        KtorOtlpTransport(HttpClient(MockEngine(handler)), endpoint)

    @Test
    fun aBatchIsPostedAsProtobufToTheSignalPathWithTheHeaders() = runTest {
        var seen: HttpRequestData? = null
        var body = byteArrayOf()
        val result = transport { request ->
            seen = request
            body = request.body.toByteArray()
            respond("", HttpStatusCode.OK)
        }.send(Signal.METRICS, byteArrayOf(1, 2, 3))

        assertEquals(ExportResult.Success, result)
        val request = requireNotNull(seen)
        assertEquals("https://otlp.example.test/otlp/v1/metrics", request.url.toString())
        assertEquals("Basic c2VjcmV0", request.headers[HttpHeaders.Authorization])
        assertEquals("application/x-protobuf", request.body.contentType.toString())
        assertEquals(listOf<Byte>(1, 2, 3), body.toList())
    }

    @Test
    fun throttlingAndGatewayErrorsAreRetryableWithTheirRetryAfter() = runTest {
        val cases = mapOf(
            respondWith(HttpStatusCode.TooManyRequests, "7") to ExportResult.Retryable(7.seconds),
            respondWith(HttpStatusCode.BadGateway, null) to ExportResult.Retryable(),
            respondWith(HttpStatusCode.ServiceUnavailable, "Wed, 21 Oct 2026 07:28:00 GMT") to ExportResult.Retryable(),
            respondWith(HttpStatusCode.GatewayTimeout, "-3") to ExportResult.Retryable(),
        )

        cases.forEach { (status, expected) ->
            assertEquals(expected, transport { status() }.send(Signal.TRACES, byteArrayOf()))
        }
    }

    @Test
    fun otherErrorsAreRejectedForGood() = runTest {
        assertEquals(
            ExportResult.Rejected(400),
            transport { respond("", HttpStatusCode.BadRequest) }.send(Signal.LOGS, byteArrayOf()),
        )
        assertEquals(
            ExportResult.Rejected(500),
            transport { respond("", HttpStatusCode.InternalServerError) }.send(Signal.LOGS, byteArrayOf()),
        )
    }

    @Test
    fun aNetworkFailureIsRetryable() = runTest {
        assertEquals(
            ExportResult.Retryable(),
            transport { throw IllegalStateException("connection refused") }.send(Signal.TRACES, byteArrayOf()),
        )
    }

    @Test
    fun cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            transport { throw CancellationException("caller went away") }.send(Signal.TRACES, byteArrayOf())
        }
    }

    @Test
    fun theEndpointNeverPrintsItsHeaderValues() {
        assertEquals(
            "OtlpEndpoint(https://otlp.example.test/otlp/, headers=[Authorization], signals=[TRACES, METRICS, LOGS])",
            endpoint.toString(),
        )
        assertEquals(listOf("v1/traces", "v1/metrics", "v1/logs"), Signal.entries.map { it.path })
    }

    private fun respondWith(
        status: HttpStatusCode,
        retryAfter: String?,
    ): MockRequestHandleScope.() -> HttpResponseData = {
        respond(
            content = "",
            status = status,
            headers = retryAfter?.let { headersOf(HttpHeaders.RetryAfter, it) } ?: headersOf(),
        )
    }
}
