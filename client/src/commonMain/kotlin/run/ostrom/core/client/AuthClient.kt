// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import run.ostrom.core.api.models.Account
import run.ostrom.core.api.models.AuthToken
import run.ostrom.core.api.models.DeviceCode
import run.ostrom.core.api.models.DeviceLoginStart
import run.ostrom.core.api.models.DeviceTokenRequest
import run.ostrom.core.api.models.Me
import run.ostrom.core.api.models.MobileToken
import run.ostrom.core.api.models.MobileTokenRequest
import run.ostrom.core.api.models.MobileUnlock
import run.ostrom.core.api.models.MobileUnlockRequest
import run.ostrom.core.api.models.SignInMethods

/**
 * Sign-in and the caller (`/api/v1/auth`): the phone's PKCE sign-in, the device flow of the CLI and the desktop, the
 * biometric unlock, who the caller is and signing out. Reached through [OstromClient.auth].
 */
public class AuthClient internal constructor(private val transport: Transport) {
    /** The sign-in methods the instance offers and who may get an account (`GET /api/v1/auth/providers`). */
    public suspend fun signInMethods(): SignInMethods = transport.json(HttpMethod.Get, "/api/v1/auth/providers")

    /**
     * The page that starts a GitHub sign-in for an app (`GET /api/v1/auth/github/start`), to open in the system
     * browser. No request is made; the browser comes back to [redirectUri] with `code` and [state].
     *
     * @param redirectUri The app's return address (`ostrom://auth/callback`), sent again to [exchangeMobileCode].
     * @param state A random value the return must carry back ([Pkce.base64Url] of 16 random bytes).
     * @param codeChallenge [Pkce.challenge].
     */
    public fun githubStartUrl(redirectUri: String, state: String, codeChallenge: String): String =
        startUrl("github", redirectUri, state, codeChallenge)

    /** [githubStartUrl] for any provider of [signInMethods] (`GET /api/v1/auth/{provider}/start`). */
    public fun startUrl(provider: String, redirectUri: String, state: String, codeChallenge: String): String =
        URLBuilder("${transport.origin}/api/v1/auth/${segment(provider)}/start").apply {
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", Pkce.METHOD)
        }.buildString()

    /**
     * Exchanges the one-time code of the browser's return for the app's token (`POST /api/v1/auth/mobile/token`).
     * The code works once, for 60 s, with the exact [redirectUri] of the start.
     */
    public suspend fun exchangeMobileCode(code: String, codeVerifier: String, redirectUri: String): MobileToken =
        transport.jsonBody(
            HttpMethod.Post,
            "/api/v1/auth/mobile/token",
            MobileTokenRequest(code, codeVerifier, redirectUri),
        )

    /**
     * Records the phone's biometric unlock (`POST /api/v1/auth/mobile/unlock`), after which the app's token answers a
     * gate for 5 minutes. [requestId] binds it to the approval a high-risk yes is for (see
     * [OstromException.requestBound]).
     */
    public suspend fun unlock(unlockKey: String, requestId: String? = null): MobileUnlock =
        transport.jsonBody(HttpMethod.Post, "/api/v1/auth/mobile/unlock", MobileUnlockRequest(unlockKey, requestId))

    /** The caller, their membership and capabilities in the organization the call acts in (`GET /api/v1/auth/me`). */
    public suspend fun me(): Me = transport.json(HttpMethod.Get, "/api/v1/auth/me")

    /** The signed-in person's account (`GET /api/v1/auth/me/account`). */
    public suspend fun account(): Account = transport.json(HttpMethod.Get, "/api/v1/auth/me/account")

    /** Revokes the token of this client (`POST /api/v1/auth/logout`); forget it afterwards. */
    public suspend fun logout() {
        transport.noContent(HttpMethod.Post, "/api/v1/auth/logout")
    }

    /**
     * Starts a device sign-in (`POST /api/v1/auth/device`): show [DeviceCode.userCode] and open
     * [DeviceCode.verificationUri], then [pollDeviceLogin] every [DeviceCode.interval] seconds.
     *
     * @param scopes Empty for the ordinary sign-in; the governance write scopes for an administrator's sign-in.
     */
    public suspend fun startDeviceLogin(scopes: List<String> = emptyList()): DeviceCode =
        transport.jsonBody(HttpMethod.Post, "/api/v1/auth/device", DeviceLoginStart(scopes))

    /**
     * The token of a device sign-in once approved (`POST /api/v1/auth/device/token`). Until then it throws
     * [OstromException]: `428 authorization_pending` (poll again after the interval), `400 slow_down` (poll less
     * often), `400 access_denied` or `400 expired_token` (start again).
     */
    public suspend fun pollDeviceLogin(deviceCode: String): AuthToken =
        transport.jsonBody(HttpMethod.Post, "/api/v1/auth/device/token", DeviceTokenRequest(deviceCode))
}
