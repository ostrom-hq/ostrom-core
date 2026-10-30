package dev.ostrom.core.api.models

import dev.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A signed receipt: who asked for what, under which rules, who said yes, and when.
 *
 * The body is every field except [signature]; its canonical JSON (see `dev.ostrom.core.receipts.CanonicalJson`) is
 * what is signed. A receipt received from elsewhere may carry fields this model does not know: verify the raw JSON
 * object, not this model, or those fields are lost and the digest no longer matches.
 *
 * @property id Receipt id, `rcpt_<uuid>`.
 * @property subject What the receipt is about, with the digest of its preimage.
 * @property initiator Login of the person (or `ci`) who started the action.
 * @property agent Coding agent that acted (`claude-code`, `codex`, `ci`…), when one did.
 * @property model Model the agent used, when known.
 * @property rules Rules in force, each with the digest of its canonical form.
 * @property decisions Every yes or no the action carried.
 * @property costUsd Inference cost of the session, in US dollars.
 * @property packageRef Session package applied (workflow receipts).
 * @property nodes What each compiled workflow node did (workflow receipts).
 * @property createdAt ISO-8601 UTC timestamp with milliseconds.
 * @property signature `ed25519;kid=<kid>;sha256=<hex>;sig=<base64>`.
 */
@Serializable
public data class Receipt(
    val id: String,
    val subject: ReceiptSubject,
    val initiator: String,
    val agent: String? = null,
    val model: String? = null,
    val rules: List<ReceiptRule>,
    val decisions: List<ReceiptDecision>,
    val costUsd: Double? = null,
    @SerialName("package") val packageRef: ReceiptPackage? = null,
    val nodes: List<ReceiptNode>? = null,
    val createdAt: String,
    val signature: String,
)

/**
 * The subject of a receipt.
 *
 * @property kind What kind of thing was decided or run.
 * @property id Id of the subject (session id, gate id, `<session>@<package version>` for a workflow…).
 * @property digest SHA-256 hex of the canonical form of the subject preimage
 *   (`dev.ostrom.core.receipts.SubjectPreimages`).
 */
@Serializable
public data class ReceiptSubject(
    val kind: ReceiptSubjectKind,
    val id: String,
    val digest: String,
)

/** The kinds of receipt subjects. Serial names are part of the signed format and never change. */
@Serializable(with = ReceiptSubjectKind.Serializer::class)
public enum class ReceiptSubjectKind(
    /** The JSON value. */
    public val value: String,
) {
    TASK("task"),
    WORKFLOW("workflow"),
    RULE_CHANGE("rule-change"),
    OWNER_CHANGE("owner-change"),
    HARNESS("harness"),
    GATE("gate"),
    SESSION("session"),
    POLICY("policy"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<ReceiptSubjectKind>(
        "dev.ostrom.core.api.models.ReceiptSubjectKind",
        ReceiptSubjectKind.entries,
        ReceiptSubjectKind.UNKNOWN,
        ReceiptSubjectKind::value,
    )
}

/**
 * A rule in force when the receipt was signed.
 *
 * @property id Rule id.
 * @property digest SHA-256 hex of the canonical form of the rule.
 */
@Serializable
public data class ReceiptRule(
    val id: String,
    val digest: String,
)

/**
 * A yes or a no carried by the receipt.
 *
 * @property approvalId Id of the approval (or of the decision) that was answered.
 * @property by Login of the person who answered.
 * @property at ISO-8601 UTC timestamp of the answer.
 * @property result `approved`, `rejected`, or the action of a decision (`accepted`, `edited`…).
 * @property selfApproved True when the yes came from the person who asked, because nobody else could approve.
 */
@Serializable
public data class ReceiptDecision(
    val approvalId: String,
    val by: String,
    val at: String,
    val result: String,
    val selfApproved: Boolean? = null,
)

/**
 * The session package a workflow receipt reports on.
 *
 * @property version Package version, `p<yyyyMMddHHmm>-<8 hex of the content digest>`.
 * @property digest SHA-256 hex of the exact payload bytes of the package envelope.
 */
@Serializable
public data class ReceiptPackage(
    val version: String,
    val digest: String,
)

/**
 * What one compiled workflow node did during a session.
 *
 * @property id Node id.
 * @property workflowId Workflow the node belongs to.
 * @property kind Node kind (`check`, `gate`, `notify`, `agent`, `step`, `trigger`…).
 * @property label Label shown to people.
 * @property outcome What happened (`passed`, `failed`, `approved`, `sent`…).
 * @property detail Free text detail.
 * @property ref Id of a related record (the gate's receipt, `rcpt_…`, for a gate node).
 */
@Serializable
public data class ReceiptNode(
    val id: String,
    val workflowId: String,
    val kind: String,
    val label: String,
    val outcome: String,
    val detail: String? = null,
    val ref: String? = null,
)

/**
 * A receipt before signing: every field of [Receipt] but the signature. The signer adds nothing else: the caller
 * chooses the id (`rcpt_<uuid>`) and the creation time, so a library call never reads a clock or a random source.
 *
 * @property id Receipt id, `rcpt_<uuid>`.
 * @property subject What the receipt is about.
 * @property initiator Login of the person (or `ci`) who started the action.
 * @property agent Coding agent that acted, when one did.
 * @property model Model the agent used, when known.
 * @property rules Rules in force.
 * @property decisions Every yes or no the action carried.
 * @property costUsd Inference cost, in US dollars.
 * @property packageRef Session package applied (workflow receipts).
 * @property nodes What each compiled workflow node did (workflow receipts).
 * @property createdAt ISO-8601 UTC timestamp with milliseconds.
 */
@Serializable
public data class ReceiptDraft(
    val id: String,
    val subject: ReceiptSubject,
    val initiator: String,
    val agent: String? = null,
    val model: String? = null,
    val rules: List<ReceiptRule> = emptyList(),
    val decisions: List<ReceiptDecision> = emptyList(),
    val costUsd: Double? = null,
    @SerialName("package") val packageRef: ReceiptPackage? = null,
    val nodes: List<ReceiptNode>? = null,
    val createdAt: String,
)
