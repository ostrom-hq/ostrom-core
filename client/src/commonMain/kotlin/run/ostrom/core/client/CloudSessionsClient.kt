// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.models.CloudSession
import run.ostrom.core.api.models.CloudSessionPage
import run.ostrom.core.api.models.CreateCloudSessionRequest
import run.ostrom.core.api.models.SetCloudEffortRequest
import run.ostrom.core.api.models.SetCloudGitHubAccessRequest
import run.ostrom.core.api.models.SetCloudModelRequest
import run.ostrom.core.api.models.SetCloudPermissionBypassRequest

/**
 * The caller's cloud agent sessions (`runners.cloud.v1`, `/api/v1/cloud/sessions`). Reached through
 * [OstromClient.cloudSessions]. Pause, resume and stop are accepted at once and carried out by the server's
 * reconciler: read the session again to follow its status.
 */
@Suppress("TooManyFunctions") // One function per route of /api/v1/cloud/sessions/{id}.
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
     *
     * @param idempotencyKey One [Idempotency.newKey] per press, reused for each retry of it: a create repeated with the
     *   same key and request (an answer was lost) returns the session it created (`202`, [Idempotency.REPLAYED_HEADER])
     *   instead of starting a second one, on a server announcing `runners.cloud.idempotency.v1`; the same key with
     *   another request is [Idempotency.CONFLICT] (422). It must satisfy [Idempotency.isValid], else an
     *   [IllegalArgumentException] is thrown before any request.
     */
    public suspend fun create(request: CreateCloudSessionRequest, idempotencyKey: String? = null): CloudSession {
        require(idempotencyKey == null || Idempotency.isValid(idempotencyKey)) {
            "An idempotency key is 1 to ${Idempotency.MAX_LENGTH} characters of A-Za-z0-9._:-."
        }
        return transport.send(HttpMethod.Post, "/api/v1/cloud/sessions") {
            contentType(ContentType.Application.Json)
            if (idempotencyKey != null) header(Idempotency.KEY_HEADER, idempotencyKey)
            setBody(request)
        }.body()
    }

    /** Pauses a session, keeping its workspace (`POST /api/v1/cloud/sessions/{id}/pause`). */
    public suspend fun pause(id: String): CloudSession = transport.json(HttpMethod.Post, "${path(id)}/pause")

    /**
     * Resumes a paused session (`POST /api/v1/cloud/sessions/{id}/resume`): a retryable `preparation_failed` is tried
     * again this way.
     */
    public suspend fun resume(id: String): CloudSession = transport.json(HttpMethod.Post, "${path(id)}/resume")

    /** Terminates a session; its workspace is deleted (`DELETE /api/v1/cloud/sessions/{id}`). */
    public suspend fun stop(id: String): CloudSession = transport.json(HttpMethod.Delete, path(id))

    /** Turns the owner's standing approval of tool uses on or off (`PUT .../permission-bypass`). */
    public suspend fun setPermissionBypass(id: String, enabled: Boolean): CloudSession =
        transport.jsonBody(HttpMethod.Put, "${path(id)}/permission-bypass", SetCloudPermissionBypassRequest(enabled))

    /** Changes the agent's model, while it runs too (`PUT .../model`, `runners.cloud.model.v1`). */
    public suspend fun setModel(id: String, model: String): CloudSession =
        transport.jsonBody(HttpMethod.Put, "${path(id)}/model", SetCloudModelRequest(model))

    /** Changes the agent's effort, while it runs too (`PUT .../effort`, `runners.cloud.effort.v1`). */
    public suspend fun setEffort(id: String, effort: String): CloudSession =
        transport.jsonBody(HttpMethod.Put, "${path(id)}/effort", SetCloudEffortRequest(effort))

    /**
     * Chooses whose instructions the agent follows with the owner's GitHub token (`PUT .../github`,
     * `runners.cloud.github.v1`): `owner`, `shared` or `none`. The session's owner only.
     */
    public suspend fun setGitHubAccess(id: String, access: String): CloudSession =
        transport.jsonBody(HttpMethod.Put, "${path(id)}/github", SetCloudGitHubAccessRequest(access))

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"
}
