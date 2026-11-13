// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.Artifact
import run.ostrom.core.api.models.ArtifactPage
import run.ostrom.core.api.models.ArtifactTicket
import run.ostrom.core.api.models.ArtifactTicketRequest
import run.ostrom.core.api.models.ArtifactVersionPage

/** The artifacts the caller may read (`/api/v1/artifacts`). Reached through [OstromClient.artifacts]. */
public class ArtifactsClient internal constructor(private val transport: Transport) {
    /**
     * A page of artifacts (`GET /api/v1/artifacts`).
     *
     * @param before The [ArtifactPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 100, 20 by default).
     */
    public suspend fun list(before: String? = null, limit: Int? = null): ArtifactPage =
        transport.json(HttpMethod.Get, "/api/v1/artifacts", query("before" to before, "limit" to limit?.toString()))

    /** One artifact (`GET /api/v1/artifacts/{id}`). */
    public suspend fun get(id: String): Artifact = transport.json(HttpMethod.Get, "/api/v1/artifacts/${segment(id)}")

    /** The versions of an artifact (`GET /api/v1/artifacts/{id}/versions`). */
    public suspend fun versions(id: String): ArtifactVersionPage =
        transport.json(HttpMethod.Get, "/api/v1/artifacts/${segment(id)}/versions")

    /**
     * A short-lived reading address of an artifact for a web view (`POST /api/v1/artifacts/{id}/tickets`).
     *
     * @param version The version to open; the latest when null.
     */
    public suspend fun ticket(id: String, version: Int? = null): ArtifactTicket =
        transport.jsonBody(HttpMethod.Post, "/api/v1/artifacts/${segment(id)}/tickets", ArtifactTicketRequest(version))
}
