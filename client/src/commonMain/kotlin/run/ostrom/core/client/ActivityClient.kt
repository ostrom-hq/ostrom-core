// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.ActivityPage

/** The organization's activity feed. Reached through [OstromClient.activity]. */
public class ActivityClient internal constructor(private val transport: Transport) {
    /**
     * A page of activity (`GET /api/v1/activity`).
     *
     * @param before The [ActivityPage.next] of the previous page; the first page when null.
     * @param repo Only the activity of this repository.
     */
    public suspend fun page(before: String? = null, repo: String? = null): ActivityPage =
        transport.json(HttpMethod.Get, "/api/v1/activity", query("before" to before, "repo" to repo))
}
