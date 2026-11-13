// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import run.ostrom.core.api.models.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The origin check, the headers of every call, the failures and the lifetime of [OstromClient]. */
class OstromClientTest {

    @Test
    fun anHttpsOriginIsKeptWithoutItsTrailingSlash() {
        assertEquals("https://ostrom.example", ScriptedServer().client(serverUrl = "https://ostrom.example/").origin)
        val spaced = ScriptedServer().client(serverUrl = " https://ostrom.example:8443 ")
        assertEquals("https://ostrom.example:8443", spaced.origin)
    }

    @Test
    fun plainHttpIsAcceptedOnLoopbackOnly() {
        for (url in listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080")) {
            assertEquals(Url(url).toString().trimEnd('/'), ScriptedServer().client(serverUrl = url).origin, url)
        }
        val error = assertFailsWith<IllegalArgumentException> { OstromClient.normalizedOrigin("http://ostrom.example") }
        assertEquals("Ostrom requires HTTPS except on loopback.", error.message)
        assertFailsWith<IllegalArgumentException> { OstromClient.normalizedOrigin("ftp://ostrom.example") }
    }

    @Test
    fun anOriginWithCredentialsPathQueryOrFragmentIsRefused() {
        for (url in listOf(
            "https://user@ostrom.example",
            "https://user:secret@ostrom.example",
            "https://:secret@ostrom.example",
            "https://ostrom.example/api",
            "https://ostrom.example?org=a",
            "https://ostrom.example#top",
            "ostrom.example",
        )) {
            assertFailsWith<IllegalArgumentException>(url) { OstromClient.normalizedOrigin(url) }
        }
        assertEquals(
            "An Ostrom server URL is required.",
            assertFailsWith<IllegalArgumentException> { OstromClient.normalizedOrigin("  ") }.message,
        )
    }

    @Test
    fun everyCallCarriesTheTokenTheOrganizationTheUserAgentAndAcceptsJson() = runTest {
        val server = ScriptedServer { json("""{"apiVersion":1,"serverVersion":"1.4.0","features":["waits.v1"]}""") }
        val capabilities = server.client(organization = "org-1", userAgent = "ostrom-app/1.0").use { it.capabilities() }
        assertEquals(listOf("waits.v1"), capabilities.features)
        assertEquals("1.4.0", capabilities.serverVersion)
        val request = server.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://ostrom.example/api/v1/capabilities", request.url.toString())
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        assertEquals("org-1", request.headers["Ostrom-Org"])
        assertEquals("application/json", request.headers[HttpHeaders.Accept])
        assertEquals("ostrom-app/1.0", request.headers[HttpHeaders.UserAgent])
    }

    @Test
    fun noCredentialIsSentWhenTheProvidersHaveNoneOrABlankOne() = runTest {
        val server = ScriptedServer { json("""{"apiVersion":1,"serverVersion":"1.4.0","features":[]}""") }
        server.client(token = null, organization = null).use { it.capabilities() }
        server.client(token = " ", organization = "").use { it.capabilities() }
        for (request in server.requests) {
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["Ostrom-Org"])
            // Ktor's own agent, never one the caller did not choose.
            assertFalse(request.headers[HttpHeaders.UserAgent].orEmpty().startsWith("ostrom"))
        }
    }

    @Test
    fun anApiErrorBecomesAnOstromExceptionWithItsTypeAndMessage() = runTest {
        val server = ScriptedServer {
            json("""{"error":{"type":"org_required","message":"Choose an organization."}}""", HttpStatusCode.Conflict)
        }
        val error = server.client().use { client -> assertFailsWith<OstromException> { client.capabilities() } }
        assertEquals(409, error.status)
        assertEquals("org_required", error.type)
        assertEquals("Choose an organization.", error.message)
        assertNull(error.retryAfterSeconds)
        assertFalse(error.requestBound)
    }

    @Test
    fun aStepUpBoundToAnotherApprovalSaysSo() = runTest {
        val body = """{"error":{"type":"step_up_required","message":"Unlock again.","requestBound":true}}"""
        val server = ScriptedServer { json(body, HttpStatusCode.Forbidden) }
        val error = server.client().use { client -> assertFailsWith<OstromException> { client.capabilities() } }
        assertEquals("step_up_required", error.type)
        assertTrue(error.requestBound)
    }

    @Test
    fun aBodyThatIsNotAnApiErrorIsNeverKept() = runTest {
        val bodies = listOf(
            "<html>Bad gateway: secret-upstream-detail</html>",
            "",
            """{"error":{"type":"Not A Key","message":"secret-upstream-detail"}}""",
            """{"error":{"type":"too_big","message":"${"x".repeat(17 * 1024)}"}}""",
        )
        for (body in bodies) {
            val server = ScriptedServer { json(body, HttpStatusCode.BadGateway) }
            val error = server.client().use { client -> assertFailsWith<OstromException> { client.capabilities() } }
            assertEquals(502, error.status)
            assertNull(error.type, body.take(40))
            assertEquals("Ostrom answered HTTP 502.", error.message)
        }
    }

    @Test
    fun retryAfterIsReadAsDeltaSecondsOnly() = runTest {
        val server = ScriptedServer {
            json("""{"error":{"type":"rate_limited","message":"Later."}}""", HttpStatusCode.TooManyRequests,
                listOf(HttpHeaders.RetryAfter to " 30 "))
        }
        val error = server.client().use { client -> assertFailsWith<OstromException> { client.capabilities() } }
        assertEquals(429, error.status)
        assertEquals(30L, error.retryAfterSeconds)
        assertNull(retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(retryAfterSeconds("12345678"))
        assertNull(retryAfterSeconds(""))
        assertNull(retryAfterSeconds("-1"))
        assertNull(retryAfterSeconds("1a"))
        assertNull(retryAfterSeconds(null))
        assertEquals(1234567L, retryAfterSeconds("1234567"))
    }

    @Test
    fun aRedirectIsNeverFollowed() = runTest {
        val server = ScriptedServer {
            json("", HttpStatusCode.Found, listOf(HttpHeaders.Location to "https://elsewhere.example/"))
        }
        val error = server.client().use { client -> assertFailsWith<OstromException> { client.capabilities() } }
        assertEquals(302, error.status)
        assertEquals(listOf("ostrom.example"), server.requests.map { it.url.host })
    }

    @Test
    fun aNetworkFailurePropagatesAsTheEngineReportsIt() = runTest {
        val server = ScriptedServer { error("offline") }
        val error = server.client().use { client -> assertFailsWith<IllegalStateException> { client.capabilities() } }
        assertEquals("offline", error.message)
    }

    @Test
    fun withoutAnOrganizationProviderTheServerChoosesTheOrganization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = OstromClient("https://ostrom.example", token = { null }, createHttpClient = { configure ->
            HttpClient(MockEngine { request ->
                requests += request
                json("""{"apiVersion":1,"serverVersion":"1.4.0","features":[]}""")
            }) { configure(this) }
        })
        client.use { it.capabilities() }
        assertNull(requests.single().headers["Ostrom-Org"])
    }

    @Test
    fun closeClosesTheHttpClientItCreated() {
        val server = ScriptedServer()
        val client = server.client()
        val http = server.httpClients.single()
        assertTrue(http.isActive)
        client.close()
        assertFalse(http.isActive)
    }

    @Test
    fun queryParametersAreEncodedByTheClient() = runTest {
        val server = ScriptedServer { json("""{"apiVersion":1,"serverVersion":"1.4.0","features":[]}""") }
        server.client().use { client ->
            client.transport.json<Capabilities>(HttpMethod.Get, "/api/v1/capabilities", listOf("before" to "a&b c"))
        }
        val request = server.requests.single()
        assertEquals("a&b c", request.url.parameters["before"])
        assertEquals(1, request.url.parameters.names().size)
    }

    @Test
    fun aPathSegmentIsEncodedAndDotSegmentsAreRefused() {
        assertEquals("a-Z_0.9~", segment("a-Z_0.9~"))
        assertEquals("a%2Fb%3F%25%20%C3%A9", segment("a/b?% é"))
        assertEquals("..%2F", segment("../"))
        for (refused in listOf("", ".", "..")) {
            assertEquals("Not a path segment.", assertFailsWith<IllegalArgumentException> { segment(refused) }.message)
        }
    }
}
