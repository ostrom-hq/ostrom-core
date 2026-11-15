// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// A conversation session (`runners.cloud.conversation.v1` and the keys after it): TeamCode's transcript, messages,
// permission requests, background tasks, subagent feeds and files (docs/cloud-conversations.md, api
// CloudConversation.kt), for a cloud session created with `mode: conversation`. Kinds, statuses and states stay
// strings: a newer server may add one, which must not break a decode.

/** The modes of a cloud session ([CloudSession.mode]). */
public object CloudSessionModes {
    /** A PTY relayed over the terminal WebSocket; a session without a mode is one. */
    public const val TERMINAL: String = "terminal"

    /** The agent runs headless and is talked to through the transcript and messages routes. */
    public const val CONVERSATION: String = "conversation"
}

/**
 * What the server keeps of runner event data: the nesting depth from the event, the entries per object or array, the
 * key length, and the reasoning keys it drops. Shared by the server's sanitizer and the runner, which leaves a
 * permission request's input out rather than letting the server cut it.
 */
public object CloudTranscriptLimits {
    /** Nesting levels kept from the event. */
    public const val MAX_DEPTH: Int = 16

    /** Entries kept per object or array. */
    public const val MAX_ENTRIES: Int = 200

    /** Characters of a key. */
    public const val MAX_KEY_CHARS: Int = 64

    /** Characters of a string, the rest cut and marked. */
    public const val MAX_STRING_CHARS: Int = 16_000

    /** Bytes of an event, above which it becomes `{truncated, originalBytes}`. */
    public const val MAX_EVENT_BYTES: Int = 60_000

    /** Reasoning keys removed wherever they are. */
    public val DROPPED_KEYS: Set<String> = setOf("thinking", "signature", "reasoning", "redacted_thinking")
}

/** Normalized transcript event types ([CloudEvent.type]). Clients tolerate unknown types and unknown `data` fields. */
public object CloudEventTypes {
    /** A consumed message: `text`, `inputId`, `authorId`, and `files` (`[{id, name, size, contentType}]`). */
    public const val USER_MESSAGE: String = "message.user"

    /**
     * The agent's text (`text`). With `messageId` and `index` it replaces the [AGENT_MESSAGE_DELTA] text of that
     * block, or of every block of the message when `index` is absent.
     */
    public const val AGENT_MESSAGE: String = "message.agent"

    /** Live text while it is written (`messageId`, `index`, `text`: what the block gained since its previous delta). */
    public const val AGENT_MESSAGE_DELTA: String = "message.agent.delta"

    /** `{id, name, input, parentToolUseId?}`. */
    public const val TOOL_CALL: String = "tool.call"

    /** `{id, output, isError, exitCode?, parentToolUseId?}`. */
    public const val TOOL_RESULT: String = "tool.result"

    /** `{phase: started, completed, interrupted or failed, durationMs?, costMicros?, ...}`. */
    public const val TURN: String = "turn"

    /** `{agent, conversation?, conversationId?, model?, exitCode?}`. */
    public const val STATUS: String = "status"

    /** `{code, message}`. */
    public const val ERROR: String = "error"

    /** Server-written: an input was cancelled or rejected, or a permission answer consumed. */
    public const val INPUT: String = "input"

    /** The agent waits for a person before using a tool (`requestId`, `toolName`, `input`, `question`). */
    public const val PERMISSION_REQUEST: String = "permission.request"

    /** Runner-written: nobody answered within the organization's deadline, so the request was denied. */
    public const val PERMISSION_EXPIRED: String = "permission.expired"

    /** Runner-written: the agent's live background tasks, the whole set each time it changes. */
    public const val TASKS: String = "tasks"

    /** Every type this version knows. */
    public val all: List<String> = listOf(
        USER_MESSAGE, AGENT_MESSAGE, AGENT_MESSAGE_DELTA, TOOL_CALL, TOOL_RESULT, TURN, STATUS, ERROR, INPUT,
        PERMISSION_REQUEST, PERMISSION_EXPIRED, TASKS,
    )
}

/**
 * One transcript event.
 *
 * @property seq From 1, increased by one per session.
 * @property at When the server stored it (ISO-8601).
 * @property epoch The execution that produced it; 0 for an event the server wrote.
 * @property data Bounded ([CloudTranscriptLimits]) and secret-masked.
 * @property feed The id of the `tool.call` that started the subagent the event belongs to
 *   (`runners.cloud.transcript.feeds.v1`); null for the main agent.
 */
@Serializable
public data class CloudEvent(
    val seq: Long,
    val type: String,
    val at: String,
    val epoch: Long,
    val data: JsonObject,
    val feed: String? = null,
)

/**
 * A page of events in ascending order. Continue forward with `after=`[nextAfter], backward with
 * `before=`[nextBefore]; a null cursor means nothing more in that direction.
 *
 * @property firstSeq The oldest retained event (older ones expired).
 * @property lastSeq The session's newest event when the page was read.
 */
@Serializable
public data class CloudEventPage(
    val events: List<CloudEvent>,
    val firstSeq: Long? = null,
    val lastSeq: Long? = null,
    val nextAfter: Long? = null,
    val nextBefore: Long? = null,
)

/**
 * A message to the agent (`POST /api/v1/cloud/sessions/{id}/messages`).
 *
 * @property clientMessageId An alias of the `Idempotency-Key` header; send one or the other.
 * @property wake False leaves a paused session paused; the message waits for the next resume.
 * @property expectedLastEventSeq Refuses the message (409 `stale_view`) when newer events exist.
 * @property fileIds Files the caller uploaded to this session (`runners.cloud.files.v1`, at most 5); with files the
 *   text may be empty.
 */
@Serializable
public data class SendCloudMessageRequest(
    val text: String? = null,
    val wake: Boolean = true,
    val clientMessageId: String? = null,
    val expectedLastEventSeq: Long? = null,
    val fileIds: List<String> = emptyList(),
)

/** Input kinds ([CloudInput.kind]). */
public object CloudInputKinds {
    /** A message to the agent. */
    public const val MESSAGE: String = "message"

    /** Stops the current turn. */
    public const val INTERRUPT: String = "interrupt"

    /** The answer to a permission request. */
    public const val PERMISSION: String = "permission"

    /** Stops a background task; its text is the task id. */
    public const val STOP_TASK: String = "stop_task"

    /** The running agent's new model. */
    public const val SET_MODEL: String = "set_model"

    /** The running agent's new effort. */
    public const val SET_EFFORT: String = "set_effort"
}

/** Input acknowledgement levels, in order. `cancelled` and `rejected` are final like `consumed`. */
public object CloudInputStatuses {
    /** Waiting for the runner. */
    public const val QUEUED: String = "queued"

    /** Handed to the runner. */
    public const val DELIVERED: String = "delivered"

    /** Written to the agent. */
    public const val CONSUMED: String = "consumed"

    /** Withdrawn by the server (the session ended). */
    public const val CANCELLED: String = "cancelled"

    /** Refused by the runner or after too many deliveries. */
    public const val REJECTED: String = "rejected"

    /** Every status, in order. */
    public val all: List<String> = listOf(QUEUED, DELIVERED, CONSUMED, CANCELLED, REJECTED)
}

/**
 * A message, an interrupt, a permission answer, a task stop or a model or effort change, and its acknowledgement.
 *
 * @property kind One of [CloudInputKinds].
 * @property status One of [CloudInputStatuses].
 * @property woke Accepting it resumed a paused session.
 * @property reason Why it was cancelled or rejected, a stable code.
 * @property eventSeq The `message.user` event recording a consumed message.
 */
@Serializable
public data class CloudInput(
    val id: String,
    val kind: String,
    val status: String,
    val authorId: String,
    val text: String? = null,
    val wake: Boolean = true,
    val woke: Boolean = false,
    val clientMessageId: String? = null,
    val createdAt: String,
    val deliveredAt: String? = null,
    val deliveredEpoch: Long? = null,
    val consumedAt: String? = null,
    val finishedAt: String? = null,
    val reason: String? = null,
    val eventSeq: Long? = null,
    val fileIds: List<String> = emptyList(),
)

/** The session's inputs, newest first; [nextBefore] is the cursor of the following page. */
@Serializable
public data class CloudInputPage(
    val inputs: List<CloudInput>,
    val nextBefore: String? = null,
)

/**
 * The answer to the `permission.request` event [requestId] (`runners.cloud.permissions.v1`).
 *
 * @property behavior `allow` or `deny`.
 * @property message Tells the agent why it was denied.
 * @property answers Answers a question (`AskUserQuestion`), one entry per question, by index; needs `allow`.
 */
@Serializable
public data class AnswerCloudPermissionRequest(
    val requestId: String,
    val behavior: String,
    val message: String? = null,
    val answers: List<CloudQuestionAnswer>? = null,
)

/**
 * The answer to the question at index [question] (from 0, in the request's `input.questions`): the chosen [options] by
 * index (several only for a `multiSelect` question), or a free [text]. One or the other.
 */
@Serializable
public data class CloudQuestionAnswer(
    val question: Int,
    val options: List<Int> = emptyList(),
    val text: String? = null,
)

/**
 * Subagent feeds (`runners.cloud.transcript.feeds.v1`): events carry a [CloudEvent.feed], the events page takes
 * `feed=`, and the agents route lists the subagents.
 */
public object CloudFeeds {
    /** The `feed` value for the main agent's own events. */
    public const val MAIN: String = "main"

    /** The ids the server takes as a feed: the id of a `tool.call`. */
    public val FEED: Regex = Regex("[A-Za-z0-9_.:-]{1,128}")

    /** Subagents one list holds at most (the newest). */
    public const val MAX_AGENTS: Int = 200
}

/** Where a subagent is ([CloudSubagent.state]). */
public object CloudSubagentStates {
    /** Its call has not returned, or it is a live background task. */
    public const val RUNNING: String = "running"

    /** Its call returned. */
    public const val COMPLETED: String = "completed"

    /** Its call returned an error. */
    public const val FAILED: String = "failed"
}

/**
 * A subagent of a session.
 *
 * @property id The `tool.call` that started it, and the [CloudEvent.feed] of its events.
 * @property type Its `subagent_type`.
 * @property state One of [CloudSubagentStates].
 * @property steps Its tool uses so far.
 * @property parentId The feed it was started from; null for the main agent.
 * @property taskId The id to stop it with while it is a live background task.
 */
@Serializable
public data class CloudSubagent(
    val id: String,
    val type: String? = null,
    val description: String = "",
    val state: String,
    val startedAt: String,
    val endedAt: String? = null,
    val steps: Int = 0,
    val parentId: String? = null,
    val taskId: String? = null,
)

/** The session's subagents, oldest first, at most [CloudFeeds.MAX_AGENTS] (the newest). */
@Serializable
public data class CloudSubagentList(val agents: List<CloudSubagent>)

/** Background tasks (`runners.cloud.tasks.v1`): `tasks` events and their stop. */
public object CloudTasks {
    /** Tasks one event lists at most. */
    public const val MAX_TASKS: Int = 50

    /** Characters of a description. */
    public const val MAX_DESCRIPTION_CHARS: Int = 200

    /** The task ids the server takes. */
    public val TASK_ID: Regex = Regex("[A-Za-z0-9_-]{1,64}")

    /** Rejected for a runner that cannot stop a task. */
    public const val UNSUPPORTED: String = "task_stop_unsupported"

    /** Rejected by the runner: no live task has this id. */
    public const val UNKNOWN: String = "unknown_task"
}

/** The background task of the session's agent to stop. */
@Serializable
public data class StopCloudTaskRequest(val taskId: String)

/** The owner's standing approval of tool uses (`runners.cloud.permissions.bypass.v1`). */
@Serializable
public data class SetCloudPermissionBypassRequest(val enabled: Boolean)

/** The model of the session's agent (`runners.cloud.model.v1`). */
@Serializable
public data class SetCloudModelRequest(val model: String)

/** The effort of the session's agent (`runners.cloud.effort.v1`). */
@Serializable
public data class SetCloudEffortRequest(val effort: String)

/**
 * Why the session's agent waits for a person (`runners.cloud.permissions.v1`).
 *
 * @property reason `permission` (a tool use to approve) or `question`.
 * @property requestId What the permissions route takes.
 * @property eventSeq The `permission.request` event.
 */
@Serializable
public data class CloudSessionWaiting(
    val reason: String,
    val requestId: String? = null,
    val since: String? = null,
    val eventSeq: Long? = null,
)

/** Files sent with a message (`runners.cloud.files.v1`): limits and refusals. */
public object CloudFiles {
    /** Files one message carries at most. */
    public const val MAX_FILES_PER_MESSAGE: Int = 5

    /** 413: a file above the organization's limit. */
    public const val FILE_TOO_LARGE: String = "file_too_large"

    /** 415: neither an image, a PDF nor UTF-8 text. */
    public const val FILE_TYPE_NOT_ALLOWED: String = "file_type_not_allowed"

    /** 429: a session or daily quota. */
    public const val UPLOAD_QUOTA: String = "upload_quota"

    /** 409: no tested artifact bucket. */
    public const val STORAGE_NOT_CONFIGURED: String = "file_storage_not_configured"

    /** 503: the bucket did not answer. */
    public const val STORAGE_UNAVAILABLE: String = "file_storage_unavailable"

    /** 409 on a message: a named file expired, was sent already or belongs to someone else. */
    public const val FILE_UNAVAILABLE: String = "file_unavailable"

    /** 413 on a message: its files together exceed the per-message limit. */
    public const val FILES_TOO_LARGE: String = "files_too_large"

    /** The reason a message with files is rejected for a runner that does not download them. */
    public const val UNSUPPORTED: String = "files_unsupported"
}

/**
 * A file uploaded to a session.
 *
 * @property name The cleaned name the host writes it under.
 * @property contentType The type recognized from its bytes.
 * @property expiresAt Name it in a message before then.
 */
@Serializable
public data class CloudFile(
    val id: String,
    val sessionId: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val sha256: String,
    val createdAt: String,
    val expiresAt: String,
)

/**
 * Whether the caller's organization can send files now, and its limits.
 *
 * @property reason `file_storage_not_configured` when it cannot.
 */
@Serializable
public data class CloudFilePolicy(
    val available: Boolean,
    val reason: String? = null,
    val maxFileBytes: Long,
    val maxMessageBytes: Long,
    val maxFilesPerMessage: Int,
    val types: List<String>,
)
