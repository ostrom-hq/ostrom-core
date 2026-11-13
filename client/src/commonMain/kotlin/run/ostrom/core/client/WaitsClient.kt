// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import run.ostrom.core.api.models.AnswerWaitRequest
import run.ostrom.core.api.models.Wait
import run.ostrom.core.api.models.WaitPage

/** The waits a person answers (`/api/v1/waits`, `waits.v1`). Reached through [OstromClient.waits]. */
public class WaitsClient internal constructor(private val transport: Transport) {
    /**
     * A page of waits (`GET /api/v1/waits`).
     *
     * @param status Only waits in this status (`open`, `answered`…); every status when null.
     * @param mine Only the waits assigned to the caller.
     * @param before The [WaitPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 100); the server's default when null.
     * @param resolver Who resolves them: `human`, `time`, `event` or `all`; the server's default when null.
     */
    public suspend fun list(
        status: String? = null,
        mine: Boolean? = null,
        before: String? = null,
        limit: Int? = null,
        resolver: String? = null,
    ): WaitPage = transport.json(
        HttpMethod.Get,
        "/api/v1/waits",
        query(
            "status" to status,
            "mine" to mine?.toString(),
            "before" to before,
            "limit" to limit?.toString(),
            "resolver" to resolver,
        ),
    )

    /** One wait (`GET /api/v1/waits/{id}`). */
    public suspend fun get(id: String): Wait = transport.json(HttpMethod.Get, "/api/v1/waits/${segment(id)}")

    /**
     * Answers a wait with one of its actions and the values of its fields (`POST /api/v1/waits/{id}/answer`).
     *
     * @return The wait as answered.
     */
    public suspend fun answer(id: String, action: String, values: Map<String, JsonElement> = emptyMap()): Wait =
        transport.jsonBody(HttpMethod.Post, "/api/v1/waits/${segment(id)}/answer", AnswerWaitRequest(action, values))
}
