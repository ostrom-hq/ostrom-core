package dev.ostrom.core.api.models

import dev.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.Serializable

/**
 * A run of a check script, reported by the launcher. A failed enforced check blocks the action it guards.
 *
 * @property id Run id (`run_…`).
 * @property at ISO time.
 * @property sessionId Session.
 * @property digest Package digest.
 * @property version Package version.
 * @property nodeId Check node.
 * @property scriptId Script.
 * @property scriptSha SHA-256 of the script that ran.
 * @property tampered True when the script on disk did not match the package.
 * @property mode Enforced or shadow.
 * @property exit Exit code, null when it did not run (timeout, sandbox refusal).
 * @property durationMs Duration.
 * @property head Commit the repository was at.
 * @property cached True when a passing result for the same head was reused.
 * @property sandbox Sandbox used (`sandbox-exec`, `bwrap`, `none`…).
 * @property outputTail Last lines of output.
 * @property dev Developer.
 * @property repo Repository.
 */
@Serializable
public data class CheckRun(
    val id: String,
    val at: String,
    val sessionId: String? = null,
    val digest: String,
    val version: String? = null,
    val nodeId: String,
    val scriptId: String,
    val scriptSha: String? = null,
    val tampered: Boolean? = null,
    val mode: PackageMode,
    val exit: Int?,
    val durationMs: Long? = null,
    val head: String? = null,
    val cached: Boolean? = null,
    val sandbox: String? = null,
    val outputTail: String? = null,
    val dev: String,
    val repo: String? = null,
)

/** The verdict of a check. */
@Serializable(with = CheckOutcome.Serializer::class)
public enum class CheckOutcome(
    /** The JSON value. */
    public val value: String,
) {
    PASSED("passed"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    SKIPPED("skipped"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<CheckOutcome>(
        "dev.ostrom.core.api.models.CheckOutcome",
        CheckOutcome.entries,
        CheckOutcome.UNKNOWN,
        CheckOutcome::value,
    )
}

/**
 * Reports a check run (launcher → server).
 *
 * @property sessionId Session.
 * @property digest Package digest.
 * @property nodeId Node.
 * @property scriptId Script.
 * @property scriptSha SHA-256 of the script that ran.
 * @property exit Exit code.
 * @property outcome Verdict.
 * @property durationMs Duration.
 * @property head Commit.
 * @property cached Reused result.
 * @property sandbox Sandbox.
 * @property outputTail Output tail (4 000 characters at most).
 */
@Serializable
public data class ReportCheckRequest(
    val sessionId: String? = null,
    val digest: String,
    val nodeId: String,
    val scriptId: String,
    val scriptSha: String? = null,
    val exit: Int?,
    val outcome: CheckOutcome,
    val durationMs: Long? = null,
    val head: String? = null,
    val cached: Boolean? = null,
    val sandbox: String? = null,
    val outputTail: String? = null,
)

/** Delivery status of a notification. */
@Serializable(with = NotificationStatus.Serializer::class)
public enum class NotificationStatus(
    /** The JSON value. */
    public val value: String,
) {
    QUEUED("queued"),
    SENT("sent"),
    LOGGED_ONLY("logged-only"),
    NOT_SENT("not-sent"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<NotificationStatus>(
        "dev.ostrom.core.api.models.NotificationStatus",
        NotificationStatus.entries,
        NotificationStatus.UNKNOWN,
        NotificationStatus::value,
    )
}

/**
 * A notification sent by the server for a notify node (the channel secret never reaches the machine).
 *
 * @property id Notification id (`ntf_…`).
 * @property at ISO time.
 * @property sessionId Session.
 * @property digest Package digest.
 * @property version Package version.
 * @property nodeId Notify node.
 * @property notifyId Compiled notification.
 * @property channel Channel.
 * @property mode Enforced or shadow.
 * @property status Delivery status.
 * @property text Text sent, placeholders replaced.
 * @property dev Developer.
 * @property repo Repository.
 * @property error Why it was not sent.
 * @property link Link to the sent message.
 * @property attempts Delivery attempts.
 * @property nextAt Next attempt.
 * @property sentAt ISO time sent.
 * @property messageId Id of the message in the channel.
 */
@Serializable
public data class Notification(
    val id: String,
    val at: String,
    val sessionId: String? = null,
    val digest: String,
    val version: String? = null,
    val nodeId: String,
    val notifyId: String,
    val channel: String,
    val mode: PackageMode,
    val status: NotificationStatus,
    val text: String,
    val dev: String,
    val repo: String? = null,
    val error: String? = null,
    val link: String? = null,
    val attempts: Int? = null,
    val nextAt: String? = null,
    val sentAt: String? = null,
    val messageId: String? = null,
)
