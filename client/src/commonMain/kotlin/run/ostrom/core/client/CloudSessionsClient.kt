// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.CloudSession
import run.ostrom.core.api.models.CloudSessionPage
import run.ostrom.core.api.models.CreateCloudSessionRequest

/**
 * The caller's cloud agent sessions (`runners.cloud.v1`, `/api/v1/cloud/sessions`). Reached through
 * [OstromClient.cloudSessions]. Pause, resume and stop are accepted at once and carried out by the server's
 * reconciler: read the session again to follow its status.
 */
public class CloudSessionsClient internal constructor(private val transport: Transport) {
    /**
     * A page of the caller's cloud sessions, newest first (`GET /api/v1/cloud/sessions`).
     *
     * @param status Only the sessions in this status.
     * @param before The [CloudSessionPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 50); the server's default when null.
     */
    public suspend fun list(status: String? = null, before: String? = null, limit: Int? = null): CloudSessionPage =
        transport.json(
            HttpMethod.Get,
            "/api/v1/cloud/sessions",
            query("status" to status, "before" to before, "limit" to limit?.toString()),
        )

    /** One cloud session (`GET /api/v1/cloud/sessions/{id}`). */
    public suspend fun get(id: String): CloudSession = transport.json(HttpMethod.Get, path(id))

    /**
     * Starts a cloud session (`POST /api/v1/cloud/sessions`), with the legacy single repository or an environment
     * recipe (`runners.cloud.environment.v1`).
     */
    public suspend fun create(request: CreateCloudSessionRequest): CloudSession =
        transport.jsonBody(HttpMethod.Post, "/api/v1/cloud/sessions", request)

    /** Pauses a session, keeping its workspace (`POST /api/v1/cloud/sessions/{id}/pause`). */
    public suspend fun pause(id: String): CloudSession = transport.json(HttpMethod.Post, "${path(id)}/pause")

    /**
     * Resumes a paused session (`POST /api/v1/cloud/sessions/{id}/resume`): a retryable `preparation_failed` is tried
     * again this way.
     */
    public suspend fun resume(id: String): CloudSession = transport.json(HttpMethod.Post, "${path(id)}/resume")

    /** Terminates a session; its workspace is deleted (`DELETE /api/v1/cloud/sessions/{id}`). */
    public suspend fun stop(id: String): CloudSession = transport.json(HttpMethod.Delete, path(id))

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"
}
