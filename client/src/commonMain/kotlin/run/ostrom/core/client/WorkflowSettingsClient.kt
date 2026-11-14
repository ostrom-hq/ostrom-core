// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowSettings

/** Who may decide workflows (`/api/v1/workflows/settings`). Reached through [OstromClient.workflowSettings]. */
public class WorkflowSettingsClient internal constructor(private val transport: Transport) {
    /** Who may decide workflows and whether the caller may (`GET /api/v1/workflows/settings`); any member reads. */
    public suspend fun get(): WorkflowSettings = transport.json(HttpMethod.Get, "/api/v1/workflows/settings")

    /**
     * Changes who may decide workflows (`PUT /api/v1/workflows/settings`): an owner or an admin, from a browser
     * session only.
     *
     * Throws [OstromException] with `forbidden` (403, not a console session, or not an owner or an admin) or
     * `invalid_editors` (400, a `members` list naming someone who is not an active member).
     *
     * @return The settings as saved.
     */
    public suspend fun save(editors: WorkflowEditors): WorkflowSettings =
        transport.jsonBody(HttpMethod.Put, "/api/v1/workflows/settings", editors)
}
