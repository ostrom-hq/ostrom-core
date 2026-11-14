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
 * @property kind `approval` (a request waits for the member's yes) or `test`.
 * @property title The title.
 * @property body The text.
 * @property approvalId The request a tap opens, for `approval`.
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
