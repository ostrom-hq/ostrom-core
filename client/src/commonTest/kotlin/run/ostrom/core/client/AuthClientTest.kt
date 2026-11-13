// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.MemberRole
import run.ostrom.core.api.models.SignUpPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each call of [AuthClient] and [OrganizationsClient]: its method, path and body, and what it reads back. */
class AuthClientTest {

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    @Test
    fun theSignInMethodsAreReadAndAnUnknownSignUpPolicyStillDecodes() = runTest {
        val server = ScriptedServer { json("""{"methods":["github","email"],"signUp":"waitlist"}""") }
        val methods = server.client(token = null).use { it.auth.signInMethods() }
        assertEquals(listOf("github", "email"), methods.methods)
        assertEquals(SignUpPolicy.UNKNOWN, methods.signUp)
        assertEquals("/api/v1/auth/providers", server.single().first.url.encodedPath)
    }

    @Test
    fun theGithubStartUrlCarriesTheReturnTheStateAndTheChallengeWithoutARequest() {
        val server = ScriptedServer()
        val url = server.client(token = null).use {
            Url(it.auth.githubStartUrl("ostrom://auth/callback", "state-1", "challenge-1"))
        }
        assertEquals("https://ostrom.example/api/v1/auth/github/start", url.toString().substringBefore('?'))
        assertEquals("ostrom://auth/callback", url.parameters["redirect_uri"])
        assertEquals("state-1", url.parameters["state"])
        assertEquals("challenge-1", url.parameters["code_challenge"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun anotherProviderStartsFromItsOwnEncodedSegment() {
        val url = ScriptedServer().client(token = null).use { it.auth.startUrl("oidc:acme/x", "app://r", "s", "c") }
        assertEquals("https://ostrom.example/api/v1/auth/oidc%3Aacme%2Fx/start", url.substringBefore('?'))
        assertFailsWith<IllegalArgumentException> { ScriptedServer().client().auth.startUrl("..", "app://r", "s", "c") }
    }

    @Test
    fun theMobileCodeIsExchangedForTheTokenAndItsUnlockKey() = runTest {
        val server = ScriptedServer {
            json("""{"token":"osm_1","user":{"login":"ada"},"expiresAt":"2026-12-13T00:00:00Z","unlockKey":"uk"}""")
        }
        val token = server.client(token = null).use {
            it.auth.exchangeMobileCode("code-1", "verifier-1", "ostrom://cb")
        }
        assertEquals("osm_1", token.token)
        assertEquals("ada", token.user.login)
        assertEquals("uk", token.unlockKey)
        val (request, body) = server.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/auth/mobile/token", request.url.encodedPath)
        assertJson("""{"code":"code-1","codeVerifier":"verifier-1","redirectUri":"ostrom://cb"}""", body)
    }

    @Test
    fun anUnlockIsSentWithItsApprovalOnlyWhenGiven() = runTest {
        val server = ScriptedServer { json("""{"authenticatedAt":"2026-11-13T10:00:00Z"}""") }
        server.client().use { client ->
            assertEquals("2026-11-13T10:00:00Z", client.auth.unlock("uk").authenticatedAt)
            client.auth.unlock("uk", requestId = "apr_1")
        }
        assertEquals(listOf("/api/v1/auth/mobile/unlock"), server.requests.map { it.url.encodedPath }.distinct())
        assertJson("""{"unlockKey":"uk"}""", server.requests[0].bodyText())
        assertJson("""{"unlockKey":"uk","requestId":"apr_1"}""", server.requests[1].bodyText())
    }

    @Test
    fun theCallerAndTheirAccountAreRead() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/me" -> json(
                    """{"dev":{"login":"ada","verified":true,"via":"mobile"},"capabilities":["approve"],""" +
                        """"organization":{"id":"org-1","name":"Acme","avatarUrl":""}}""",
                )
                else -> json(
                    """{"id":"a1","login":"ada","displayName":"Ada","createdAt":"2026-01-01T00:00:00Z",""" +
                        """"emails":[{"email":"ada@example.com","verified":true}],"providers":["github"]}""",
                )
            }
        }
        server.client().use { client ->
            val me = client.auth.me()
            assertEquals("ada", me.dev.login)
            assertEquals("org-1", me.organization?.id)
            assertNull(me.member)
            val account = client.auth.account()
            assertEquals("ada@example.com", account.emails.single().email)
        }
        assertEquals(listOf("/api/v1/auth/me", "/api/v1/auth/me/account"), server.requests.map { it.url.encodedPath })
    }

    @Test
    fun logoutPostsAndReadsNothing() = runTest {
        val server = ScriptedServer { json("", HttpStatusCode.NoContent) }
        server.client().use { it.auth.logout() }
        val (request, _) = server.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/auth/logout", request.url.encodedPath)
    }

    @Test
    fun aDeviceSignInStartsThenSurfacesPendingAnswersBeforeTheToken() = runTest {
        var polls = 0
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/device" -> json(
                    """{"deviceCode":"dc","userCode":"ABCD-EFGH","verificationUri":"https://ostrom.example/device",""" +
                        """"interval":5,"expiresIn":900}""",
                )
                else -> when (polls++) {
                    0 -> json("""{"error":{"type":"authorization_pending","message":"Not yet."}}""",
                        HttpStatusCode.PreconditionRequired)
                    1 -> json("""{"error":{"type":"slow_down","message":"Slower."}}""", HttpStatusCode.BadRequest)
                    else -> json("""{"token":"ost_1","login":"ada","scopes":["sessions:write"]}""")
                }
            }
        }
        server.client(token = null).use { client ->
            val code = client.auth.startDeviceLogin()
            assertEquals("ABCD-EFGH", code.userCode)
            assertEquals(5, code.interval)
            val pending = assertFailsWith<OstromException> { client.auth.pollDeviceLogin("dc") }
            assertEquals(428 to "authorization_pending", pending.status to pending.type)
            val slow = assertFailsWith<OstromException> { client.auth.pollDeviceLogin("dc") }
            assertEquals(400 to "slow_down", slow.status to slow.type)
            assertEquals("ost_1", client.auth.pollDeviceLogin("dc").token)
            client.auth.startDeviceLogin(listOf("policy:write", "harness:write", "gates:policy"))
        }
        assertJson("{}", server.requests[0].bodyText())
        assertJson("""{"deviceCode":"dc"}""", server.requests[1].bodyText())
        assertJson("""{"scopes":["policy:write","harness:write","gates:policy"]}""", server.requests.last().bodyText())
    }

    @Test
    fun theCallersOrganizationsAreListed() = runTest {
        val server = ScriptedServer {
            json("""{"items":[{"id":"org-1","slug":"acme","name":"Acme","avatarUrl":"","role":"approver"}]}""")
        }
        val organizations = server.client().use { it.organizations.mine() }
        assertEquals(listOf("acme"), organizations.map { it.slug })
        assertEquals(MemberRole.APPROVER, organizations.single().role)
        assertEquals("/api/v1/me/organizations", server.single().first.url.encodedPath)
    }
}
