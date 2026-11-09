package run.ostrom.core.api.models

import run.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** How a gate request is enforced. */
@Serializable(with = GateMode.Serializer::class)
public enum class GateMode(
    /** The JSON value. */
    public val value: String,
) {
    /** The action waits for a yes. */
    ENFORCED("enforced"),

    /** Logged only (the workflow is proposed). */
    SHADOW("shadow"),

    /** No gate covers the action. */
    UNGATED("ungated"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<GateMode>(
        "run.ostrom.core.api.models.GateMode",
        GateMode.entries,
        GateMode.UNKNOWN,
        GateMode::value,
    )
}

/** Status of a gate request. A yes serves once: it is `consumed` by the action it allowed. */
@Serializable(with = GateStatus.Serializer::class)
public enum class GateStatus(
    /** The JSON value. */
    public val value: String,
) {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    SHADOW("shadow"),
    UNGATED("ungated"),
    CONSUMED("consumed"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<GateStatus>(
        "run.ostrom.core.api.models.GateStatus",
        GateStatus.entries,
        GateStatus.UNKNOWN,
        GateStatus::value,
    )
}

/**
 * The gate of a workflow that covers an action, as the catalog resolves it. Its canonical form is part of the gate
 * receipt's subject preimage.
 *
 * @property workflowId Workflow.
 * @property workflowName Workflow name.
 * @property nodeId Gate node.
 * @property label Gate label.
 * @property approvers Logins who may say yes.
 * @property status Status of the workflow.
 * @property enforce True when the gate blocks.
 * @property basis `declared` (the gate declares this action) or `guessed`.
 * @property reason Where the approvers come from.
 */
@Serializable
public data class GateRef(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val approvers: List<String>,
    val status: WorkflowStatus,
    val enforce: Boolean,
    val basis: String? = null,
    val reason: String,
)

/**
 * A push, read by the hook before it leaves: the remote and, for each ref, the commit it receives. The yes covers
 * these commits to these refs of this remote, not the command text: another SHA is another request.
 *
 * @property remote Remote repository (`owner/name`).
 * @property refs Refs and SHAs.
 * @property force True for a forced push (`--force`, `+ref`); not part of the push preimage.
 */
@Serializable
public data class GatePush(
    val remote: String,
    val refs: List<GatePushRef>,
    val force: Boolean? = null,
)

/**
 * A ref of a push.
 *
 * @property ref Ref (`main`, `refs/tags/v1`).
 * @property sha Commit (40 or 64 hex), empty for a deletion.
 */
@Serializable
public data class GatePushRef(val ref: String, val sha: String)

/**
 * A pull request merge, whichever path asked (the `gh pr merge` hook, the GitHub check): one request per pull request
 * and head commit, shared.
 *
 * @property number Pull request number.
 * @property sha Head commit.
 * @property owner Author.
 */
@Serializable
public data class GatePullRequest(val number: String, val sha: String, val owner: String? = null)

/**
 * CI context of a gate asked by a CI check.
 *
 * @property sha Commit.
 * @property pr Pull request number.
 */
@Serializable
public data class GateCi(val sha: String? = null, val pr: String? = null)

/**
 * A gate request: an agent (or a CI check) asks to perform a boundary action.
 *
 * @property id Gate request id.
 * @property approvalId The approval shown to people.
 * @property sessionId Session (segment) that asked.
 * @property runId Workflow run that asked.
 * @property repo Repository.
 * @property realRepo Real repository where the command runs.
 * @property dev Developer who asked.
 * @property cls Action class.
 * @property label Action label.
 * @property tool Tool (`Bash`, `Write`, `mcp`).
 * @property command Command.
 * @property key Action key (see `SubjectPreimages.pushActionKey`): finds the same request again.
 * @property gate The covering gate, or null.
 * @property mode Enforcement.
 * @property status Status.
 * @property at ISO time asked.
 * @property decidedBy Who answered.
 * @property decidedAt When.
 * @property receiptId Gate receipt.
 * @property harnessVersion Harness version.
 * @property ci CI context.
 * @property devVerified True when the developer was verified.
 * @property pr Pull request merged.
 * @property askedVia Paths that joined the request (`hook`, `ci`, `mcp`).
 * @property selfApproved True when the yes came from the asker, the only possible approver.
 * @property push Push covered.
 */
@Serializable
public data class GateRequest(
    val id: String,
    val approvalId: String? = null,
    val sessionId: String? = null,
    val runId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val dev: String,
    @SerialName("class") val cls: String,
    val label: String,
    val tool: String,
    val command: String,
    val key: String,
    val gate: GateRef?,
    val mode: GateMode,
    val status: GateStatus,
    val at: String,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val receiptId: String? = null,
    val harnessVersion: String? = null,
    val ci: GateCi? = null,
    val devVerified: Boolean? = null,
    val pr: GatePullRequest? = null,
    val askedVia: List<String>? = null,
    val selfApproved: Boolean? = null,
    val push: GatePush? = null,
)

/**
 * Asks for a gate (hook `PreToolUse`, CI check, MCP). A request in a closed session is refused with 409
 * `session_closed`: reopen a segment and ask there.
 *
 * @property sessionId Session.
 * @property runId Workflow run.
 * @property repo Repository.
 * @property realRepo Real repository.
 * @property cls Action class.
 * @property tool Tool.
 * @property command Command.
 * @property dev Declared developer.
 * @property harnessVersion Harness version.
 * @property ci CI context.
 * @property diff Diff shown to the approver (8 000 characters at most).
 * @property push Push read by the hook.
 * @property pr Pull request.
 */
@Serializable
public data class GateAskRequest(
    val sessionId: String? = null,
    val runId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    @SerialName("class") val cls: String,
    val tool: String? = null,
    val command: String? = null,
    val dev: String? = null,
    val harnessVersion: String? = null,
    val ci: GateCi? = null,
    val diff: String? = null,
    val push: GatePush? = null,
    val pr: GatePullRequest? = null,
)

/** Status of an approval. */
@Serializable(with = ApprovalStatus.Serializer::class)
public enum class ApprovalStatus(
    /** The JSON value. */
    public val value: String,
) {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXPIRED("expired"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<ApprovalStatus>(
        "run.ostrom.core.api.models.ApprovalStatus",
        ApprovalStatus.entries,
        ApprovalStatus.UNKNOWN,
        ApprovalStatus::value,
    )
}

/**
 * A request for a yes, shown to an approver. It is bound to what was shown: [shownDigest] is the digest of the title,
 * the evidence and the gate context (repository, SHA, commits); the yes is signed with it, and another commit is
 * another approval. One approval per approver ([group] links them); the first answer closes the others.
 *
 * @property id Approval id.
 * @property group Gate request the approvals of every approver share.
 * @property workflowId Workflow.
 * @property taskId Task.
 * @property title Title.
 * @property evidence What is shown.
 * @property repo Repository the yes is bound to.
 * @property sha Commit the yes is bound to.
 * @property commits Commits shown (SHAs, part of what the yes is bound to).
 * @property commitDetails The same commits with their title and author, for the approver's screen.
 * @property checks CI checks of [sha], shown as evidence.
 * @property requestedFrom Approver asked.
 * @property channel Where it is shown (`console`, `slack`, `discord`, `app`, `cli`).
 * @property status Status.
 * @property decidedBy Who answered.
 * @property decidedAt When.
 * @property shownDigest Digest of what was shown.
 * @property askedBy Who asked (the session's developer, the pull request author). Nobody among them answers their own
 * request when another approver exists; a sole approver's yes is marked [selfApproved].
 * @property selfApproved True when the yes came from an asker.
 * @property expiresAt ISO expiry.
 * @property action The action the yes lets through (the app titles its button after [ApprovalAction.kind]).
 * @property diffStats The size of the change between [ApprovalAction.baseSha] and [ApprovalAction.headSha].
 * @property diffTruncated True when the approver is not shown the whole diff; the app then refuses a mobile yes.
 */
@Serializable
public data class Approval(
    val id: String,
    val group: String? = null,
    val workflowId: String? = null,
    val taskId: String? = null,
    val title: String,
    val evidence: List<Evidence> = emptyList(),
    val repo: String? = null,
    val sha: String? = null,
    val commits: List<String>? = null,
    val commitDetails: List<ApprovalCommit>? = null,
    val checks: List<ApprovalCheck>? = null,
    val requestedFrom: String,
    val channel: String,
    val status: ApprovalStatus,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val shownDigest: String,
    val askedBy: List<String>? = null,
    val selfApproved: Boolean? = null,
    val expiresAt: String? = null,
    val action: ApprovalAction? = null,
    val diffStats: DiffStats? = null,
    val diffTruncated: Boolean? = null,
)

/** The kind of action a yes lets through. */
@Serializable(with = ApprovalActionKind.Serializer::class)
public enum class ApprovalActionKind(
    /** The JSON value. */
    public val value: String,
) {
    PUSH("push"),
    MERGE("merge"),
    DEPLOY("deploy"),
    TOOL("tool"),
    OTHER("other"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<ApprovalActionKind>(
        "run.ostrom.core.api.models.ApprovalActionKind",
        ApprovalActionKind.entries,
        ApprovalActionKind.UNKNOWN,
        ApprovalActionKind::value,
    )
}

/** The risk of an action; a `high` one needs biometrics for a mobile yes. */
@Serializable(with = ApprovalActionRisk.Serializer::class)
public enum class ApprovalActionRisk(
    /** The JSON value. */
    public val value: String,
) {
    LOW("low"),
    HIGH("high"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<ApprovalActionRisk>(
        "run.ostrom.core.api.models.ApprovalActionRisk",
        ApprovalActionRisk.entries,
        ApprovalActionRisk.UNKNOWN,
        ApprovalActionRisk::value,
    )
}

/**
 * The action a yes lets through.
 *
 * @property kind Push, merge, deploy, tool or other.
 * @property repo Repository (`owner/name`).
 * @property ref Branch or ref the action lands on.
 * @property headSha Commit that lands.
 * @property baseSha Commit it lands on, when known.
 * @property risk Low or high.
 */
@Serializable
public data class ApprovalAction(
    val kind: ApprovalActionKind,
    val repo: String,
    val ref: String,
    val headSha: String,
    val baseSha: String? = null,
    val risk: ApprovalActionRisk,
)

/**
 * The size of a change.
 *
 * @property files Files changed.
 * @property additions Lines added.
 * @property deletions Lines deleted.
 */
@Serializable
public data class DiffStats(val files: Int, val additions: Int, val deletions: Int)

/**
 * A commit shown with an approval.
 *
 * @property sha Commit.
 * @property title First line of its message.
 * @property author Author login or name.
 * @property url Link.
 */
@Serializable
public data class ApprovalCommit(
    val sha: String,
    val title: String,
    val author: String? = null,
    val url: String? = null,
)

/** State of a CI check. */
@Serializable(with = CheckState.Serializer::class)
public enum class CheckState(
    /** The JSON value. */
    public val value: String,
) {
    SUCCESS("success"),
    FAILURE("failure"),
    PENDING("pending"),
    SKIPPED("skipped"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<CheckState>(
        "run.ostrom.core.api.models.CheckState",
        CheckState.entries,
        CheckState.UNKNOWN,
        CheckState::value,
    )
}

/**
 * A CI check shown with an approval.
 *
 * @property name Check name.
 * @property state State.
 * @property url Link.
 */
@Serializable
public data class ApprovalCheck(val name: String, val state: CheckState, val url: String? = null)

/**
 * A line of evidence shown with an approval.
 *
 * @property label Label.
 * @property value Value.
 * @property url Link.
 */
@Serializable
public data class Evidence(val label: String, val value: String, val url: String? = null)

/** A yes or a no. */
@Serializable
public enum class ApprovalAnswer {
    @SerialName("approve") APPROVE,
    @SerialName("reject") REJECT,
}

/**
 * Answers an approval. The answer is refused (409) when [shownDigest] is not the digest of what the approval shows now:
 * a yes is never given to something else than what was seen.
 *
 * @property answer Yes or no.
 * @property shownDigest Digest of what the approver saw.
 * @property note What the approver tells the agent with the answer; sent to its session, written on the receipt.
 */
@Serializable
public data class AnswerApprovalRequest(
    val answer: ApprovalAnswer,
    val shownDigest: String,
    val note: String? = null,
)

/**
 * A gate request with its approval and its receipt.
 *
 * @property gate The request.
 * @property approval The approval, when one was asked.
 * @property receipt The signed receipt, once answered.
 * @property waitingFor Approvers who can still answer.
 */
@Serializable
public data class GateResponse(
    val gate: GateRequest,
    val approval: Approval? = null,
    val receipt: Receipt? = null,
    val waitingFor: List<String>? = null,
)

/**
 * A signed decision on something else than a gate (a rule, a workflow, an owner, a policy).
 *
 * @property id Decision id (`dec_<uuid>`).
 * @property at ISO time.
 * @property by Login.
 * @property subject What was decided.
 * @property action What was done (`accepted`, `edited`, `rejected`…).
 * @property before State before.
 * @property after State after.
 * @property receiptId Receipt.
 */
@Serializable
public data class Decision(
    val id: String,
    val at: String,
    val by: String,
    val subject: DecisionSubject,
    val action: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val receiptId: String,
)

/**
 * The subject of a decision.
 *
 * @property kind `rule`, `workflow`, `owner`, `policy`, `gate`.
 * @property id Id.
 * @property label Label.
 */
@Serializable
public data class DecisionSubject(val kind: String, val id: String, val label: String? = null)

/**
 * Who approves what in a repository (`GET /api/v1/gates/approvers`): the gates catalogue seen from the approvers' side,
 * read from the same catalogue the gates decide on.
 *
 * @property approvers Every approver with the gates they answer, sorted by name.
 * @property unanswered The gates that name no approver: their actions can never run.
 * @property ungated The action classes no gate covers, sorted.
 */
@Serializable
public data class ApproversGraph(
    val approvers: List<ApproverLine>,
    val unanswered: List<ApproverGate>,
    val ungated: List<String>,
)

/**
 * One approver and the gates they answer.
 *
 * @property approver The approver, as the workflow names them.
 * @property gates Their gates, by workflow then node.
 */
@Serializable
public data class ApproverLine(val approver: String, val gates: List<ApproverGate>)

/**
 * A gate seen from its approvers, with the actions it covers.
 *
 * @property workflowId The workflow.
 * @property workflowName Its name.
 * @property nodeId The gate node.
 * @property label The gate's label.
 * @property enforce False while the workflow is only proposed.
 * @property actions The action classes it covers (`push-main`, `deploy`…), sorted.
 */
@Serializable
public data class ApproverGate(
    val workflowId: String,
    val workflowName: String,
    val nodeId: String,
    val label: String,
    val enforce: Boolean,
    val actions: List<String>,
)
