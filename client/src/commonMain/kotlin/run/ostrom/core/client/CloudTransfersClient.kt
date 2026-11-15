// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.OnBehalfOf
import run.ostrom.core.api.models.AcceptCloudTransferRequest
import run.ostrom.core.api.models.CloudTransfer
import run.ostrom.core.api.models.CloudTransferPage
import run.ostrom.core.api.models.CloudTransferRecipients
import run.ostrom.core.api.models.CreateCloudTransferRequest

/**
 * Ownership transfers of cloud sessions (`runners.cloud.transfer.v1`, TeamCode's cloud transfers): ask for one,
 * list and read them, accept, decline or cancel. Reached through [OstromClient.cloudTransfers]. Accepting is
 * acknowledged at once; the server pauses the session and changes its owner: read the transfer again to follow it.
 */
public class CloudTransfersClient internal constructor(private val transport: Transport) {
    /** The members a session can be given to (`GET /api/v1/cloud/transfer-recipients`), by login. */
    public suspend fun recipients(): CloudTransferRecipients =
        transport.json(HttpMethod.Get, "/api/v1/cloud/transfer-recipients")

    /**
     * Gives a session to another member (`POST /api/v1/cloud/sessions/{id}/transfer`). [idempotencyKey] is required
     * by the server: a retry with the same key and request returns the first transfer.
     *
     * @param onBehalfOf The member an administrator acts for ([OnBehalfOf.HEADER], `runners.cloud.onbehalf.writes.v1`):
     *   a server without the capability ignores it, so check it first. It must satisfy [OnBehalfOf.isValid], else an
     *   [IllegalArgumentException] is thrown before any request.
     */
    public suspend fun request(
        sessionId: String,
        request: CreateCloudTransferRequest,
        idempotencyKey: String,
        onBehalfOf: String? = null,
    ): CloudTransfer {
        requireReference(onBehalfOf)
        return transport.send(HttpMethod.Post, "/api/v1/cloud/sessions/${segment(sessionId)}/transfer") {
            header(IDEMPOTENCY_KEY, idempotencyKey)
            if (onBehalfOf != null) header(OnBehalfOf.HEADER, onBehalfOf)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * A page of the caller's transfers, newest first (`GET /api/v1/cloud/transfers`).
     *
     * @param direction `incoming`, `outgoing` or `all` (null).
     * @param states Only these states.
     * @param session Only the transfers of this session.
     * @param before The [CloudTransferPage.next] of the previous page.
     * @param limit The page size (1 to 50).
     */
    public suspend fun list(
        direction: String? = null,
        states: List<String> = emptyList(),
        session: String? = null,
        before: String? = null,
        limit: Int? = null,
    ): CloudTransferPage = transport.json(
        HttpMethod.Get,
        "/api/v1/cloud/transfers",
        query("direction" to direction, "session" to session, "before" to before, "limit" to limit?.toString()) +
            states.map { "state" to it },
    )

    /** One transfer (`GET /api/v1/cloud/transfers/{id}`). */
    public suspend fun get(id: String): CloudTransfer = transport.json(HttpMethod.Get, path(id))

    /**
     * Accepts a session offered to the caller (`POST .../accept`); [resume] null keeps what the offer asked for.
     *
     * @param onBehalfOf The recipient, for an administrator's call on their behalf (see [request]).
     */
    public suspend fun accept(id: String, resume: Boolean? = null, onBehalfOf: String? = null): CloudTransfer {
        requireReference(onBehalfOf)
        return transport.send(HttpMethod.Post, "${path(id)}/accept") {
            if (onBehalfOf != null) header(OnBehalfOf.HEADER, onBehalfOf)
            contentType(ContentType.Application.Json)
            setBody(AcceptCloudTransferRequest(resume))
        }.body()
    }

    /**
     * Declines an offered session (`POST .../decline`).
     *
     * @param onBehalfOf The recipient, for an administrator's call on their behalf (see [request]).
     */
    public suspend fun decline(id: String, onBehalfOf: String? = null): CloudTransfer =
        decide("${path(id)}/decline", onBehalfOf)

    /**
     * Withdraws an offer, or cancels an accepted transfer before the owner change starts (`POST .../cancel`).
     *
     * @param onBehalfOf The session's former owner, for an administrator's call on their behalf (see [request]).
     */
    public suspend fun cancel(id: String, onBehalfOf: String? = null): CloudTransfer =
        decide("${path(id)}/cancel", onBehalfOf)

    private suspend fun decide(path: String, onBehalfOf: String?): CloudTransfer {
        requireReference(onBehalfOf)
        return transport.send(HttpMethod.Post, path) {
            if (onBehalfOf != null) header(OnBehalfOf.HEADER, onBehalfOf)
        }.body()
    }

    private fun path(id: String) = "/api/v1/cloud/transfers/${segment(id)}"
}
