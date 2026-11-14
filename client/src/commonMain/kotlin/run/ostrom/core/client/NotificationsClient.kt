// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.NotificationPreferences
import run.ostrom.core.api.models.NotificationSettingChange
import run.ostrom.core.api.models.NotificationSettings
import run.ostrom.core.api.models.PushTestResults

/**
 * What the caller is notified of: their preferences, their switches per channel and kind (`notifications.v1`) and a
 * test push to their own phones. Reached through [OstromClient.notifications].
 */
public class NotificationsClient internal constructor(private val transport: Transport) {
    /** The caller's preferences (`GET /api/v1/me/notifications`). */
    public suspend fun preferences(): NotificationPreferences =
        transport.json(HttpMethod.Get, "/api/v1/me/notifications")

    /** Replaces the caller's preferences (`PUT /api/v1/me/notifications`) and returns them as saved. */
    public suspend fun save(preferences: NotificationPreferences): NotificationPreferences =
        transport.jsonBody(HttpMethod.Put, "/api/v1/me/notifications", preferences)

    /**
     * The channels the server offers and every switch in force, defaults filled (`GET /api/v1/notifications/settings`).
     */
    public suspend fun settings(): NotificationSettings =
        transport.json(HttpMethod.Get, "/api/v1/notifications/settings")

    /**
     * Turns one [kind] on or off on one [channel] for the caller (`PUT /api/v1/notifications/settings`); answers every
     * switch in force, as [settings] does.
     */
    public suspend fun setSetting(channel: String, kind: String, enabled: Boolean): NotificationSettings =
        transport.jsonBody(
            HttpMethod.Put,
            "/api/v1/notifications/settings",
            NotificationSettingChange(channel, kind, enabled),
        )

    /**
     * A test notification to the caller's own phones, sent at once (`POST /api/v1/notifications/push/test`,
     * `notifications.push.test.v1`), with each installation's result. Refused with 409 `push_off` (no provider) or
     * `push_no_device` (no phone), and 429 `push_test_rate_limited` with its `Retry-After`.
     */
    public suspend fun sendTestPush(): PushTestResults =
        transport.json(HttpMethod.Post, "/api/v1/notifications/push/test")
}
