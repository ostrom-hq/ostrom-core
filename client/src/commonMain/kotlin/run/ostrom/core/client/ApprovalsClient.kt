// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.AnswerApprovalRequest
import run.ostrom.core.api.models.ApprovalAnswer
import run.ostrom.core.api.models.ApprovalQueue
import run.ostrom.core.api.models.ApprovalView

/** The caller's yes queue and their answers (`/api/v1/approvals`). Reached through [OstromClient.approvals]. */
public class ApprovalsClient internal constructor(private val transport: Transport) {
    /** The approvals waiting for the caller and those answered lately (`GET /api/v1/approvals/queue`). */
    public suspend fun queue(): ApprovalQueue = transport.json(HttpMethod.Get, "/api/v1/approvals/queue")

    /** One approval as the app shows it (`GET /api/v1/approvals/{id}/view`). */
    public suspend fun view(id: String): ApprovalView =
        transport.json(HttpMethod.Get, "/api/v1/approvals/${segment(id)}/view")

    /**
     * Answers an approval (`POST /api/v1/approvals/{id}/answer`). A yes from the phone needs a recent
     * [AuthClient.unlock]; otherwise it throws [OstromException] `403 step_up_required` (with
     * [OstromException.requestBound] when the unlock is not bound to this approval).
     *
     * @param shownDigest The digest of what the person was shown, so a yes never covers a changed request.
     * @param note What the approver tells the agent.
     */
    public suspend fun answer(
        id: String,
        answer: ApprovalAnswer,
        shownDigest: String,
        note: String? = null,
    ): ApprovalView = transport.jsonBody(
        HttpMethod.Post,
        "/api/v1/approvals/${segment(id)}/answer",
        AnswerApprovalRequest(answer, shownDigest, note),
    )
}
