// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A phone to notify (`POST /api/v1/push-devices`).
 *
 * @property pushToken The push token of the phone.
 * @property provider The push provider.
 * @property platform The phone's platform.
 * @property locale The phone's language, for the text of the notifications.
 */
@Serializable
public data class RegisterPushDeviceRequest(
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String? = null,
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
