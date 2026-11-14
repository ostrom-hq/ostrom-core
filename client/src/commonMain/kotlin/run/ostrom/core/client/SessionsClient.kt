// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.SessionPage
import run.ostrom.core.api.models.SessionResponse

/** The organization's agent sessions (`/api/v1/sessions`), read only. Reached through [OstromClient.sessions]. */
public class SessionsClient internal constructor(private val transport: Transport) {
    /**
     * A page of session segments, newest first (`GET /api/v1/sessions`).
     *
     * @param repo Only this repository's.
     * @param dev Only this person's.
     * @param open Only the open (true) or the ended (false) ones; both when null.
     * @param before The [SessionPage.next] of the previous page; the first page when null.
     * @param limit The page size (1 to 100); the server's default when null.
     */
    public suspend fun list(
        repo: String? = null,
        dev: String? = null,
        open: Boolean? = null,
        before: String? = null,
        limit: Int? = null,
    ): SessionPage = transport.json(
        HttpMethod.Get,
        "/api/v1/sessions",
        query(
            "repo" to repo,
            "dev" to dev,
            "open" to open?.toString(),
            "before" to before,
            "limit" to limit?.toString(),
        ),
    )

    /**
     * One segment with its receipt once ended (`GET /api/v1/sessions/{id}`). An owner or an administrator reading
     * another member's session gets its record only, without its gates and receipt.
     */
    public suspend fun get(id: String): SessionResponse =
        transport.json(HttpMethod.Get, "/api/v1/sessions/${segment(id)}")
}
