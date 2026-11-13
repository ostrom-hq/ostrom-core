// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * The yes queue of the caller (`GET /api/v1/approvals/queue`).
 *
 * @property operator Login a yes is signed as.
 * @property you Who is looking at the queue.
 * @property pending The approvals waiting for an answer.
 * @property recent The approvals answered lately.
 */
@Serializable
public data class ApprovalQueue(
    val operator: String,
    val you: ApprovalViewer,
    val pending: List<ApprovalView>,
    val recent: List<ApprovalView>,
)

/**
 * Who is looking at the yes queue.
 *
 * @property login Their login.
 * @property via `console`, `app`, `cli` or `operator-account`.
 * @property name Display name.
 */
@Serializable
public data class ApprovalViewer(
    val login: String,
    val via: String,
    val name: String? = null,
)

/**
 * An approval as the console and the app show it (`GET /api/v1/approvals/{id}/view`): the request, the gate, the
 * rules, the commits and checks (in [approval]), the receipt once answered.
 *
 * @property approval The approval itself.
 * @property who Who asked.
 * @property what What for.
 * @property gate The gate.
 * @property rules The rules behind the gate.
 * @property diff The diff shown to the approver.
 * @property pr The pull request.
 * @property askedVia Where the request was sent.
 * @property note What the approver told the agent with the answer.
 * @property receipt The signed receipt, once answered.
 * @property verification The verification of [receipt].
 * @property you What the caller may do with it.
 */
@Serializable
public data class ApprovalView(
    val approval: Approval,
    val who: ApprovalWho? = null,
    val what: ApprovalWhat? = null,
    val gate: GateRef? = null,
    val rules: List<Rule> = emptyList(),
    val diff: String? = null,
    val pr: GatePullRequest? = null,
    val askedVia: List<String> = emptyList(),
    val note: String? = null,
    val receipt: Receipt? = null,
    val verification: Verification? = null,
    val you: ApprovalCallerView,
)

/**
 * Who asked for an approval.
 *
 * @property dev The developer.
 * @property agent Their agent.
 * @property model Its model.
 * @property session Its session.
 */
@Serializable
public data class ApprovalWho(
    val dev: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val session: String? = null,
)

/**
 * What an approval is for.
 *
 * @property action The action.
 * @property command Its command.
 * @property repo Its repository.
 * @property sessionRepo The session's repository.
 * @property harness The session's harness.
 */
@Serializable
public data class ApprovalWhat(
    val action: String? = null,
    val command: String? = null,
    val repo: String? = null,
    val sessionRepo: String? = null,
    val harness: String? = null,
)

/**
 * What the caller may do with an approval.
 *
 * @property login The caller.
 * @property self True when it is the caller's own request.
 * @property canApprove Whether the caller may answer. It does not wait for a fresh sign-in or the phone's unlock: a
 *   locked app reads true, and its yes is refused at the answer with 403 `step_up_required`.
 * @property reason Why the caller may not answer, for a person.
 * @property reasonType The key of [reason] when the server names one (planned): `github_link_required` or
 *   `self_approval_refused`.
 * @property selfApproval True when a yes would be the caller's approval of their own work.
 */
@Serializable
public data class ApprovalCallerView(
    val login: String,
    val self: Boolean,
    val canApprove: Boolean,
    val reason: String? = null,
    val reasonType: String? = null,
    val selfApproval: Boolean? = null,
)

/**
 * The result of an offline-equivalent receipt verification.
 *
 * @property ok Whether the signature holds.
 * @property digest The digest of the signed body.
 * @property kid The key that signed it.
 * @property reason Why it does not verify.
 * @property orgBound Whether the signed body names the organization that verifies it; a client that relies on the
 *   receipt being this organization's checks it is true.
 */
@Serializable
public data class Verification(
    val ok: Boolean,
    val digest: String? = null,
    val kid: String? = null,
    val reason: String? = null,
    val orgBound: Boolean? = null,
)
