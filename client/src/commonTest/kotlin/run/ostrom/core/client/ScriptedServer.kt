// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/**
 * A server scripted by the test: each request is recorded in [requests] and answered by [answer]. [client] builds an
 * [OstromClient] whose engine is this server.
 */
internal class ScriptedServer(
    private val answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") },
) {
    val requests = mutableListOf<HttpRequestData>()

    /** The [HttpClient]s the factory built, to check that [OstromClient.close] closes them. */
    val httpClients = mutableListOf<HttpClient>()

    fun client(
        token: String? = "synthetic-token",
        organization: String? = null,
        userAgent: String? = null,
        serverUrl: String = "https://ostrom.example",
    ): OstromClient = OstromClient(
        serverUrl = serverUrl,
        token = { token },
        organization = { organization },
        createHttpClient = { configure ->
            HttpClient(MockEngine { request -> requests += request; answer(request) }) { configure(this) }
                .also { httpClients += it }
        },
        userAgent = userAgent,
    )

    /** The single request made, with its JSON body as text (empty without one). */
    fun single(): Pair<HttpRequestData, String> {
        val request = requests.single()
        return request to request.bodyText()
    }
}

/** The body of a request as the client sent it. */
internal fun HttpRequestData.bodyText(): String = when (val content = body) {
    is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
    else -> ""
}

/** A JSON answer. */
internal fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extraHeaders: List<Pair<String, String>> = emptyList(),
): HttpResponseData = respond(
    ByteReadChannel(body),
    status,
    headersOf(*(listOf(HttpHeaders.ContentType to ContentType.Application.Json.toString()) + extraHeaders)
        .map { (name, value) -> name to listOf(value) }.toTypedArray()),
)

/** An event-stream answer. */
internal fun MockRequestHandleScope.stream(body: String): HttpResponseData = respond(
    ByteReadChannel(body),
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, "text/event-stream"),
)
