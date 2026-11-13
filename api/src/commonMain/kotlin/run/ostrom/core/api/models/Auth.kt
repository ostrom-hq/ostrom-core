// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import run.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The signed-in person, as a sign-in answers it.
 *
 * @property login Their handle.
 * @property name Display name.
 * @property avatarUrl Picture.
 */
@Serializable
public data class AuthUser(
    val login: String,
    val name: String? = null,
    val avatarUrl: String? = null,
)

/**
 * A token issued to a developer or a service (`POST /api/v1/auth/device/token`).
 *
 * @property token The bearer token.
 * @property login Whose it is.
 * @property expiresAt When it expires; for a token of `ostrom login --admin`, 8 hours after the yes at most.
 * @property scopes What it may do.
 */
@Serializable
public data class AuthToken(
    val token: String,
    val login: String,
    val expiresAt: String? = null,
    val scopes: List<String>,
)

/**
 * What a device sign-in asks for (`POST /api/v1/auth/device`): nothing for the ordinary sign-in, the governance write
 * scopes for `ostrom login --admin`.
 *
 * @property scopes Exactly `policy:write`, `harness:write` and `gates:policy`, or those three and `budgets:write`;
 *   empty for the ordinary sign-in.
 */
@Serializable
public data class DeviceLoginStart(
    val scopes: List<String> = emptyList(),
)

/**
 * The start of an OAuth device flow (RFC 8628).
 *
 * @property deviceCode The code to poll with; never shown.
 * @property userCode The code the person types on the verification page.
 * @property verificationUri The verification page.
 * @property interval Seconds to wait between two polls.
 * @property expiresIn Seconds the codes stay valid.
 * @property verificationUriComplete The verification page with the code filled in.
 */
@Serializable
public data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Int,
    val expiresIn: Int,
    val verificationUriComplete: String? = null,
)

/**
 * The device code to exchange for a token (RFC 8628 section 3.4).
 *
 * @property deviceCode The [DeviceCode.deviceCode] of the start.
 */
@Serializable
public data class DeviceTokenRequest(
    val deviceCode: String,
)

/**
 * The one-time code of the mobile sign-in (PKCE), with the verifier and the exact redirect URI of its start.
 *
 * @property code The code the browser came back with.
 * @property codeVerifier The PKCE verifier whose challenge started the sign-in.
 * @property redirectUri The redirect URI of the start, byte for byte.
 */
@Serializable
public data class MobileTokenRequest(
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
)

/**
 * The token of a mobile sign-in.
 *
 * @property token The app's token (`osm_…`, 30 days).
 * @property user Who signed in.
 * @property expiresAt When the token expires.
 * @property unlockKey Kept by the app behind the phone's biometrics, presented to `unlockMobileApp` before a yes; never
 *   sent anywhere else.
 */
@Serializable
public data class MobileToken(
    val token: String,
    val user: AuthUser,
    val expiresAt: String? = null,
    val unlockKey: String? = null,
)

/**
 * The phone's biometric unlock (`POST /api/v1/auth/mobile/unlock`).
 *
 * @property unlockKey The [MobileToken.unlockKey] of the sign-in.
 * @property requestId The approval this unlock is for (planned on the server): a yes to a high-risk action takes an
 *   unlock bound to its own approval.
 */
@Serializable
public data class MobileUnlockRequest(
    val unlockKey: String,
    val requestId: String? = null,
)

/**
 * When the unlock was recorded: the app's token answers a gate for 5 minutes after it.
 *
 * @property authenticatedAt ISO time of the unlock.
 */
@Serializable
public data class MobileUnlock(
    val authenticatedAt: String,
)

/**
 * The sign-in methods an instance offers (`GET /api/v1/auth/providers`).
 *
 * @property methods The providers (`github`, `google`, `email`, `oidc:<connection>`…).
 * @property signUp Who may get a new account; absent from an older server, which means [SignUpPolicy.OPEN].
 */
@Serializable
public data class SignInMethods(
    val methods: List<String>,
    val signUp: SignUpPolicy? = null,
)

/** Who may get a new account on an instance. */
@Serializable(with = SignUpPolicy.Serializer::class)
public enum class SignUpPolicy(
    /** The JSON value. */
    public val value: String,
) {
    /** Anyone who signs in. */
    OPEN("open"),

    /** Only with an invitation, an invitation link, the setup link or the organization's SSO (`signup_closed`). */
    INVITE("invite"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<SignUpPolicy>(
        "run.ostrom.core.api.models.SignUpPolicy",
        SignUpPolicy.entries,
        SignUpPolicy.UNKNOWN,
        SignUpPolicy::value,
    )
}
