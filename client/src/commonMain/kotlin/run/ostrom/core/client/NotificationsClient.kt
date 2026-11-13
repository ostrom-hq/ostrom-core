// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.NotificationPreferences

/** What the caller is notified of on their phone. Reached through [OstromClient.notifications]. */
public class NotificationsClient internal constructor(private val transport: Transport) {
    /** The caller's preferences (`GET /api/v1/me/notifications`). */
    public suspend fun preferences(): NotificationPreferences =
        transport.json(HttpMethod.Get, "/api/v1/me/notifications")

    /** Replaces the caller's preferences (`PUT /api/v1/me/notifications`) and returns them as saved. */
    public suspend fun save(preferences: NotificationPreferences): NotificationPreferences =
        transport.jsonBody(HttpMethod.Put, "/api/v1/me/notifications", preferences)
}
