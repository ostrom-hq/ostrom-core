package dev.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A coding agent session, one segment of it. A launch is a segment: a resumed agent session (`--resume` keeps its id)
 * opens a new segment `<agentSessionId>~r<n>` chained to the previous one; a launcher that wakes after being declared
 * lost reopens a segment the same way. The receipt of each segment cites the previous receipt.
 *
 * @property id Segment id (`<agentSessionId>` for the first, `<agentSessionId>~r<n>` after).
 * @property agentSessionId Session id of the agent, shared by every segment.
 * @property segment Segment number (1 for the first).
 * @property resumedFrom Segment this one resumes.
 * @property previousReceiptId Receipt of the previous segment.
 * @property launchId Id of the launch (`ostrom <agent>`), sent by every hook and MCP call of the launch.
 * @property repo Repository.
 * @property realRepo Real repository (`owner/name`, from `origin`).
 * @property cwd Working directory.
 * @property agent Coding agent (`claude-code`, `codex`, `opencode`…).
 * @property dev Login of the developer.
 * @property devVerified True when [dev] comes from a verified Ostrom token.
 * @property devVia How the developer was identified.
 * @property harnessVersion Harness version.
 * @property packageDigest Session package applied.
 * @property model Model used.
 * @property startedAt ISO start.
 * @property endedAt ISO end; set when the session is closed, after which nothing is added to it.
 * @property endedBy What closed it (`SessionEnd hook`, `launcher`, `launcher lost: …`, `superseded`).
 * @property lastSeenAt Last sign of life of the launcher (every 20 s).
 * @property gates Gate requests of the session.
 * @property receiptId Receipt of the segment, once signed.
 * @property tools Tool calls counted.
 * @property costUsd Inference cost.
 * @property exitCode Exit code of the launcher.
 * @property runId Workflow run the session belongs to, when a run started it.
 */
@Serializable
public data class Session(
    val id: String,
    val agentSessionId: String? = null,
    val segment: Int? = null,
    val resumedFrom: String? = null,
    val previousReceiptId: String? = null,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val dev: String,
    val devVerified: Boolean? = null,
    val devVia: String? = null,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
    val endedBy: String? = null,
    val lastSeenAt: String? = null,
    val gates: List<String> = emptyList(),
    val receiptId: String? = null,
    val tools: Int? = null,
    val costUsd: Double? = null,
    val exitCode: Int? = null,
    val runId: String? = null,
)

/**
 * Starts a session or a segment (hook `SessionStart`). The same launch finds its open segment again (SessionStart
 * comes back after a compaction); another launch of the same agent session opens a new segment.
 *
 * @property id Agent session id.
 * @property launchId Launch id.
 * @property repo Repository.
 * @property realRepo Real repository.
 * @property cwd Working directory.
 * @property agent Coding agent.
 * @property dev Declared developer, used only when no verified token says otherwise.
 * @property harnessVersion Harness version.
 * @property packageDigest Session package applied.
 * @property model Model.
 */
@Serializable
public data class StartSessionRequest(
    val id: String,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val dev: String? = null,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
)

/**
 * Ends a session (hook `SessionEnd` or the launcher): the server signs the segment's receipt.
 *
 * @property tools Tool calls counted.
 * @property model Model.
 * @property costUsd Inference cost.
 * @property source What ended it.
 * @property exitCode Launcher exit code.
 */
@Serializable
public data class EndSessionRequest(
    val tools: Int? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val source: String? = null,
    val exitCode: Int? = null,
)

/**
 * A session with the receipt the server signed for it.
 *
 * @property session The session.
 * @property receipt Its receipt, once signed.
 */
@Serializable
public data class SessionResponse(
    val session: Session,
    val receipt: Receipt? = null,
)
