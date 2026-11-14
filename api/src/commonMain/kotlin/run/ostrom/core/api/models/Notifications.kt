// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), api/src/commonMain/kotlin/me/nathanfallet/teamcode/api/models/Push.kt; see NOTICE.
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A phone to notify (`POST /api/v1/push-devices`).
 *
 * The phone sends its FCM registration token (iOS and Android alike: FCM hands an iOS push to APNs) and the P-256
 * public key every notification is encrypted for ([PushPayload]), at every start of the app and when FCM rotates the
 * token. `expo` is deprecated: the server still takes an Expo push token until the apps register FCM, then refuses it.
 *
 * @property pushToken The push token of the phone: its FCM registration token (`fcm`), or its Expo push token
 *   (`ExponentPushToken[…]`, deprecated).
 * @property provider The push provider: `fcm` (`expo` is deprecated).
 * @property platform The phone's platform: `ios` or `android`.
 * @property locale The phone's language, for the text of the notifications.
 * @property publicKey The phone's P-256 public key (SubjectPublicKeyInfo, DER, base64), required with `fcm`: each
 *   notification is encrypted for it, so the push provider sees a token and an opaque blob only.
 */
@Serializable
public data class RegisterPushDeviceRequest(
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
    val publicKey: String? = null,
)

/**
 * What an `fcm` push carries, encrypted for the phone's [RegisterPushDeviceRequest.publicKey] (ported from TeamCode's
 * `PushPayload`). The FCM data is `{"p": <encrypted payload>, "v": "1"}`, the encrypted payload being
 * `base64(0x01 | ephemeral P-256 public key (65 bytes, uncompressed) | nonce (12 bytes) | AES-256-GCM ciphertext and
 * 16-byte tag)`, with the key `HKDF-SHA256(ECDH(ephemeral private, phone public), salt = ephemeral point | phone point,
 * info = "ostrom-push-v1", 32 bytes)`, both points as 65-byte uncompressed SEC1 points (`0x04 | x | y`, the phone's
 * read from its SubjectPublicKeyInfo), and the plaintext this object's JSON, [v] always written. The app decrypts it
 * in its notification service extension (iOS) or messaging service (Android) and shows [title] and [body]; on iOS a
 * generic alert shows when it cannot.
 *
 * @property v The payload's format, 1.
 * @property id The notification's id.
 * @property origin The server's public URL (`OSTROM_PUBLIC_URL`): with [orgId] and [memberId], which signed-in
 *   account of the app it is for.
 * @property orgId The organization the phone was registered in.
 * @property memberId The member the phone notifies.
 * @property kind `approval` (a request waits for the member's yes), a notification kind ([NotificationKinds]) or
 *   `test`.
 * @property title The title.
 * @property body The text.
 * @property approvalId The request a tap opens, for `approval`.
 * @property sessionId The session a tap opens.
 * @property waitId The wait a tap opens, for a wait outside a session.
 * @property at When it was sent, in Unix milliseconds.
 */
@Serializable
public data class PushPayload(
    val v: Int = 1,
    val id: String,
    val origin: String,
    val orgId: String,
    val memberId: String,
    val kind: String,
    val title: String,
    val body: String,
    val approvalId: String? = null,
    val sessionId: String? = null,
    val waitId: String? = null,
    val at: Long,
)

/**
 * A phone registered for push notifications.
 *
 * @property id Its id, to delete it (`DELETE /api/v1/push-devices/{id}`).
 * @property provider The push provider.
 * @property platform The phone's platform.
 * @property locale The phone's language.
 * @property createdAt ISO time of the registration.
 */
@Serializable
public data class PushDevice(
    val id: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
    val createdAt: String,
)

/**
 * What the caller is notified of on their phone (`/api/v1/me/notifications`).
 *
 * @property requests Approvals waiting for them.
 * @property decisions Answers to their requests.
 * @property spending Budgets spent past their limit.
 * @property quietHours Hours without notifications.
 */
@Serializable
public data class NotificationPreferences(
    val requests: Boolean,
    val decisions: Boolean,
    val spending: Boolean,
    val quietHours: QuietHours? = null,
)

/**
 * Hours without notifications.
 *
 * @property start When they start.
 * @property end When they end.
 * @property timeZone The time zone of [start] and [end].
 */
@Serializable
public data class QuietHours(
    val start: String,
    val end: String,
    val timeZone: String,
)

/**
 * The wire names of the notification router (`notifications.v1`, ported from TeamCode's `Notifications`). A client
 * tolerates a channel or a kind it does not know, from a newer server.
 */
public object NotificationKinds {
    /** An agent or a wait needs the member: a wait a person resolves is assigned to them. */
    public const val WAITING: String = "waiting"

    /** The agent of their session finished its turn. */
    public const val TURN_COMPLETED: String = "turn_completed"

    /** The agent of their session failed its turn. */
    public const val TURN_FAILED: String = "turn_failed"

    /** Their session failed. */
    public const val SESSION_FAILED: String = "session_failed"

    /** Every kind, one switch each per channel. */
    public val ALL: List<String> = listOf(WAITING, TURN_COMPLETED, TURN_FAILED, SESSION_FAILED)
}

/**
 * A member's switch for one kind on one channel.
 *
 * @property channel `push` (later `slack`, `discord`).
 * @property kind One of [NotificationKinds].
 * @property enabled Whether the member is told.
 */
@Serializable
public data class NotificationSetting(val channel: String, val kind: String, val enabled: Boolean)

/**
 * `GET /api/v1/notifications/settings`: the channels this server offers and every switch in force, defaults filled.
 *
 * @property channels The channels offered; empty while none is configured.
 * @property settings Every switch, one per channel and kind.
 */
@Serializable
public data class NotificationSettings(val channels: List<String>, val settings: List<NotificationSetting>)

/**
 * `PUT /api/v1/notifications/settings`: turns one kind on or off on one channel for the caller.
 *
 * @property channel A channel the server offers.
 * @property kind One of [NotificationKinds].
 * @property enabled The new state.
 */
@Serializable
public data class NotificationSettingChange(val channel: String, val kind: String, val enabled: Boolean)

/**
 * One installation's result of `POST /api/v1/notifications/push/test`.
 *
 * @property status `sent`, `unregistered`, `retry` or `rejected`.
 * @property error A short code, never a provider's answer.
 */
@Serializable
public data class PushTestResult(val status: String, val error: String? = null)

/**
 * A test push to the caller's phones.
 *
 * @property ok Whether one was sent.
 * @property results One per installation.
 */
@Serializable
public data class PushTestResults(val ok: Boolean, val results: List<PushTestResult>)

/**
 * The Ostrom Push relay protocol (`push.v1`, ported from TeamCode's `PushRelay`): what a server sends to the relay
 * that holds the Firebase project of the published apps. The relay sees an instance id, a token and an encrypted
 * blob: never a member, a session or the text. Every request but [REGISTER_PATH] is signed by the instance with its
 * Ed25519 key: headers [INSTANCE_HEADER], [TIMESTAMP_HEADER] (Unix seconds) and [SIGNATURE_HEADER] (base64url, no
 * padding) over [signingString].
 */
public object PushRelay {
    /** Registers an instance's key. */
    public const val REGISTER_PATH: String = "/v1/instances"

    /** Sends one push. */
    public const val SEND_PATH: String = "/v1/send"

    /** The instance's id. */
    public const val INSTANCE_HEADER: String = "Ostrom-Instance"

    /** The request's time, Unix seconds. */
    public const val TIMESTAMP_HEADER: String = "Ostrom-Timestamp"

    /** The Ed25519 signature. */
    public const val SIGNATURE_HEADER: String = "Ostrom-Signature"

    /** A signed request older or newer than this is refused. */
    public const val MAX_SKEW_SECONDS: Long = 300L

    /** The encrypted payload, base64: well under FCM's 4 096-byte data limit. */
    public const val MAX_PAYLOAD_CHARS: Int = 3_000

    /** The longest FCM token. */
    public const val MAX_TOKEN_CHARS: Int = 512

    /** What the relay shows when the device cannot decrypt. */
    public const val GENERIC_TITLE: String = "Ostrom"

    /** The text shown with [GENERIC_TITLE]. */
    public const val GENERIC_BODY: String = "New activity in Ostrom"

    /** The key of the encrypted payload in the FCM data. */
    public const val DATA_PAYLOAD: String = "p"

    /** The key of the payload's format version in the FCM data. */
    public const val DATA_VERSION: String = "v"

    /**
     * What a request's signature covers.
     *
     * @param method The HTTP method.
     * @param path The path.
     * @param timestamp The [TIMESTAMP_HEADER].
     * @param bodySha256Hex The SHA-256 of the body, lowercase hex.
     *
     * @return `<METHOD>\n<path>\n<timestamp>\n<sha256>`.
     */
    public fun signingString(method: String, path: String, timestamp: Long, bodySha256Hex: String): String =
        "${method.uppercase()}\n$path\n$timestamp\n$bodySha256Hex"
}

/**
 * `POST /v1/instances`: an instance introduces its Ed25519 public key. The same key gets the same id.
 *
 * @property publicKey The raw 32 bytes, base64url.
 */
@Serializable
public data class PushRelayRegisterRequest(val publicKey: String)

/**
 * The relay's id for an instance.
 *
 * @property instanceId The id, to send in [PushRelay.INSTANCE_HEADER].
 */
@Serializable
public data class PushRelayRegisterResponse(val instanceId: String)

/**
 * `POST /v1/send`: one push to one installation.
 *
 * @property token The FCM registration token.
 * @property platform `ios` or `android`.
 * @property payload The encrypted [PushPayload], base64.
 * @property collapseKey An opaque lowercase hex token grouping pushes that replace one another.
 * @property ttlSeconds How long FCM keeps it.
 */
@Serializable
public data class PushRelaySendRequest(
    val token: String,
    val platform: String,
    val payload: String,
    val collapseKey: String,
    val ttlSeconds: Long,
)

/**
 * The outcome of one push.
 *
 * @property status `sent`, `unregistered` (forget the token), `retry` or `rejected` (refused for good).
 * @property retryAfterSeconds When to try again, when known.
 * @property error A short code.
 */
@Serializable
public data class PushRelaySendResponse(
    val status: String,
    val retryAfterSeconds: Long? = null,
    val error: String? = null,
)
