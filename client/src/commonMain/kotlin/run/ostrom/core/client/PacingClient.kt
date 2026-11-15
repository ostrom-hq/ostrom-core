// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.models.DirectiveClaim
import run.ostrom.core.api.models.DirectiveClaimRequest
import run.ostrom.core.api.models.DirectiveReceipt
import run.ostrom.core.api.models.DirectiveResponse
import run.ostrom.core.api.models.DirectiveScope
import run.ostrom.core.api.models.DirectiveStage
import run.ostrom.core.api.models.PacingPreview
import run.ostrom.core.api.models.PacingSettings

/**
 * The capacity controller's settings and its advice to agents (`capacity.pacing.v1`). Reached through
 * [OstromClient.pacing]. Pacing never caps parallelism: it spaces the admission of new calls, and the server enforces
 * it whatever the agent does. The CLI's hook asks [claim] before each prompt and tool call, then confirms with
 * [receipt].
 */
public class PacingClient internal constructor(private val transport: Transport) {
    /** The organization's settings (`GET /api/v1/policy/pacing`), any member. */
    public suspend fun settings(): PacingSettings = transport.json(HttpMethod.Get, "/api/v1/policy/pacing")

    /**
     * Changes the settings (`PUT /api/v1/policy/pacing`), owners and admins signed in as a person; a field left null
     * keeps its stored value.
     */
    public suspend fun setSettings(settings: PacingSettings): PacingSettings =
        transport.jsonBody(HttpMethod.Put, "/api/v1/policy/pacing", settings)

    /** The settings and the advice the controller would give now (`GET /api/v1/directives/preview`). */
    public suspend fun preview(): PacingPreview = transport.json(HttpMethod.Get, "/api/v1/directives/preview")

    /** The caller's current advice, member-wide (`GET /api/v1/directives/current`). */
    public suspend fun current(): DirectiveResponse = transport.json(HttpMethod.Get, "/api/v1/directives/current")

    /** The current advice of one of the caller's agent sessions (`POST /api/v1/directives/current`). */
    public suspend fun session(sessionId: String): DirectiveResponse =
        transport.jsonBody(HttpMethod.Post, "/api/v1/directives/current", DirectiveScope(sessionId))

    /**
     * Claims the delivery of a session's new advice at [stage] (`POST /api/v1/directives/claim`); both fields of the
     * answer are absent when nothing new is to be delivered.
     */
    public suspend fun claim(sessionId: String, stage: DirectiveStage): DirectiveClaim =
        transport.jsonBody(HttpMethod.Post, "/api/v1/directives/claim", DirectiveClaimRequest(sessionId, stage))

    /** Records that the advice [id] reached [stage] (`POST /api/v1/directives/receipt`, `204`). Idempotent. */
    public suspend fun receipt(id: String, stage: DirectiveStage, sessionId: String? = null, claimId: String? = null) {
        transport.send(HttpMethod.Post, "/api/v1/directives/receipt") {
            contentType(ContentType.Application.Json)
            setBody(DirectiveReceipt(id, stage, sessionId, claimId))
        }
    }
}
