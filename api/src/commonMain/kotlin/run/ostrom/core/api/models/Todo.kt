// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import run.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// A session's todo (`todos.v1`): the plan reference and its items, each with a complete description and a typed end
// condition. An item is done only when its checks pass, never because the agent says so. Ported from TeamCode
// (api/models/Todos.kt, docs/todos.md, commit 9a2fc6c7); times are ISO-8601 strings, as everywhere in Ostrom.

/**
 * One typed check of an item's end condition, told apart by `type`. A check is read again at every stop, so it must
 * stay true once the work moves on; no check names a pull request number or a version: the PR is found by the marker
 * that `startTodoItem` returns, a release by the merge commit of the item named by `of`.
 *
 * A `type` this version does not know (sent by a newer server) decodes to [Unknown] instead of failing the whole list;
 * [Unknown] is never written.
 */
@Serializable(with = TodoCheck.Serializer::class)
public sealed interface TodoCheck {

    /** The item's pull request (found by its marker, or linked) is merged in [repo] (`owner/name`). */
    @Serializable
    @SerialName("pr_merged")
    public data class PrMerged(val repo: String) : TodoCheck

    /** The item's pull request is closed without merge in [repo], for the stated [reason]. */
    @Serializable
    @SerialName("pr_closed")
    public data class PrClosed(val repo: String, val reason: String) : TodoCheck

    /**
     * The earliest published, non-draft release of [repo] whose tag is at or ahead of the merge commit of the item
     * [of] (or of [commit]); it is linked to the item with its tag.
     *
     * @property of The id of the item whose pull request's merge commit the release must contain.
     * @property commit A commit, when no item of the list made it.
     */
    @Serializable
    @SerialName("release_contains")
    public data class ReleaseContains(val repo: String, val of: String? = null, val commit: String? = null) : TodoCheck

    /**
     * Every workflow run of [repo] on the merge commit of the item [of] (or on [ref]) completed green.
     *
     * @property workflow Only the runs of this workflow, when given.
     */
    @Serializable
    @SerialName("ci_green")
    public data class CiGreen(
        val repo: String,
        val of: String? = null,
        val ref: String? = null,
        val workflow: String? = null,
    ) : TodoCheck

    /**
     * A command run on the agent's machine (`sh -c`, `cmd /c` on Windows) exits with [expectExit]. [run] may cite
     * `${pr:<item>.number}`, `${pr:<item>.mergeSha}` and `${release:<item>.tag}`, replaced from the item links.
     *
     * @property cwd The directory it runs in.
     * @property timeoutSeconds 1 to 120 seconds.
     */
    @Serializable
    @SerialName("command")
    public data class Command(
        val run: String,
        val cwd: String? = null,
        val expectExit: Int = 0,
        val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ) : TodoCheck {
        /** The default of [timeoutSeconds]. */
        public companion object {
            /** A command times out after a minute unless it says otherwise. */
            public const val DEFAULT_TIMEOUT_SECONDS: Int = 60
        }
    }

    /**
     * The artifact [id] (or the one linked to the item) is published, at version [minVersion] or later. Verified by the
     * server, never from the caller's report.
     */
    @Serializable
    @SerialName("artifact_published")
    public data class ArtifactPublished(val id: String? = null, val minVersion: Int? = null) : TodoCheck

    /** The wait linked to the item is answered, with [action] when given. Verified by the server. */
    @Serializable
    @SerialName("wait_answered")
    public data class WaitAnswered(val action: String? = null) : TodoCheck

    /** The agent's word for [what]: nothing verifies it, and the item is shown as not verified. The last resort. */
    @Serializable
    @SerialName("attested")
    public data class Attested(val what: String) : TodoCheck

    /**
     * A check of a [type] this version does not know, with every field it came with ([fields], `type` included).
     * Shown as text; sending it is a bug, so writing it throws.
     */
    public data class Unknown(val type: String, val fields: JsonObject) : TodoCheck

    /**
     * Writes a check as one JSON object, `type` first, and reads it back by its `type`. Its descriptor is the one of a
     * sealed hierarchy of the known checks only, so the contract test sees the union of the OpenAPI schema.
     */
    @OptIn(InternalSerializationApi::class)
    public object Serializer : KSerializer<TodoCheck> {

        private val variants: Map<String, KSerializer<out TodoCheck>> = listOf(
            PrMerged.serializer(), PrClosed.serializer(), ReleaseContains.serializer(), CiGreen.serializer(),
            Command.serializer(), ArtifactPublished.serializer(), WaitAnswered.serializer(), Attested.serializer(),
        ).associateBy { it.descriptor.serialName }

        override val descriptor: SerialDescriptor =
            buildSerialDescriptor("run.ostrom.core.api.models.TodoCheck", PolymorphicKind.SEALED) {
                element("type", String.serializer().descriptor)
                element(
                    "value",
                    buildSerialDescriptor("kotlinx.serialization.Sealed<TodoCheck>", SerialKind.CONTEXTUAL) {
                        for ((type, variant) in variants) element(type, variant.descriptor)
                    },
                )
            }

        override fun serialize(encoder: Encoder, value: TodoCheck) {
            // The API is JSON only (OstromJson): a check is never written in another format.
            val output = encoder as JsonEncoder
            val (type, fields) = fieldsOf(output.json, value)
            output.encodeJsonElement(JsonObject(mapOf(TYPE to JsonPrimitive(type)) + fields))
        }

        override fun deserialize(decoder: Decoder): TodoCheck {
            val input = decoder as JsonDecoder
            val fields = input.decodeJsonElement() as? JsonObject
                ?: throw SerializationException("A check is a JSON object.")
            val type = fields[TYPE] as? JsonPrimitive
            if (type == null || !type.isString) throw SerializationException("A check needs its type.")
            val variant = variants[type.content] ?: return Unknown(type.content, fields)
            return input.json.decodeFromJsonElement(variant, JsonObject(fields - TYPE))
        }

        private fun fieldsOf(json: Json, value: TodoCheck): Pair<String, JsonObject> = when (value) {
            is PrMerged -> fields(json, PrMerged.serializer(), value)
            is PrClosed -> fields(json, PrClosed.serializer(), value)
            is ReleaseContains -> fields(json, ReleaseContains.serializer(), value)
            is CiGreen -> fields(json, CiGreen.serializer(), value)
            is Command -> fields(json, Command.serializer(), value)
            is ArtifactPublished -> fields(json, ArtifactPublished.serializer(), value)
            is WaitAnswered -> fields(json, WaitAnswered.serializer(), value)
            is Attested -> fields(json, Attested.serializer(), value)
            is Unknown -> throw SerializationException("${value.type} is a check type this version does not know")
        }

        private fun <T : TodoCheck> fields(json: Json, serializer: KSerializer<T>, value: T): Pair<String, JsonObject> =
            serializer.descriptor.serialName to json.encodeToJsonElement(serializer, value).jsonObject

        private const val TYPE = "type"
    }
}

/**
 * The end condition of an item: a sentence for people and the typed checks that decide it. Frozen once created.
 *
 * @property text What "done" means, at most 1 000 characters.
 * @property checks At least one typed check.
 */
@Serializable
public data class TodoDoneWhen(
    val text: String,
    val checks: List<TodoCheck>,
)

/**
 * An item as the agent writes it (`createSessionTodo`, `addTodoItem`).
 *
 * @property id A slug of at most 40 characters (`[a-z0-9][a-z0-9-]*`), unique in the list.
 * @property name One line, at most 80 characters.
 * @property description 80 to 4 000 characters: what, where, why, what is out of scope; enough for a new agent.
 * @property doneWhen The end condition.
 * @property dependsOn Ids of the items that must be done (or dropped) before this one starts.
 */
@Serializable
public data class TodoItemInput(
    val id: String,
    val name: String,
    val description: String,
    val doneWhen: TodoDoneWhen,
    val dependsOn: List<String> = emptyList(),
)

/**
 * Creates the session's todo, once per session.
 *
 * @property sessionId The session, named by its owner or by its agent token, which is confined to its own session and
 *   names one of its segments.
 * @property reference One paragraph pointing to the full plan (an artifact link, or the person's request as is), at
 *   most 4 000 characters; shown to the agent at every stop.
 * @property refs At most 10 links shown with the reference (`artifact`, `session` or `url`).
 * @property items Every step of the plan, in order, at most 500.
 */
@Serializable
public data class CreateTodoRequest(
    val sessionId: String? = null,
    val reference: String,
    val refs: List<WaitRef> = emptyList(),
    val items: List<TodoItemInput>,
)

/**
 * Adds an item found on the way: marked `discovered`, placed after the item in progress and its own dependencies.
 *
 * @property reason Why the plan needs it, at most 1 000 characters.
 */
@Serializable
public data class AddTodoItemRequest(
    val sessionId: String? = null,
    val item: TodoItemInput,
    val reason: String,
)

/**
 * Starts an item.
 *
 * @property background The item is given to a background agent: the item in progress stays in progress.
 * @property agent The background agent's name, shown with the item.
 */
@Serializable
public data class StartTodoItemRequest(
    val sessionId: String? = null,
    val background: Boolean = false,
    val agent: String? = null,
)

/**
 * Appends a dated note to an item.
 *
 * @property text At most 2 000 characters; an item holds at most 100 notes.
 */
@Serializable
public data class NoteTodoItemRequest(
    val sessionId: String? = null,
    val text: String,
)

/**
 * Asks the session's owner to drop an item.
 *
 * @property reason Why the item no longer makes sense, at most 1 000 characters.
 */
@Serializable
public data class DropTodoItemRequest(
    val sessionId: String? = null,
    val reason: String,
)

/** What a link of an item points at. */
@Serializable(with = TodoLinkKind.Serializer::class)
public enum class TodoLinkKind(
    /** The JSON value. */
    public val value: String,
) {
    PR("pr"),
    RELEASE("release"),
    ARTIFACT("artifact"),
    WAIT("wait"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoLinkKind>(
        "run.ostrom.core.api.models.TodoLinkKind",
        TodoLinkKind.entries,
        TodoLinkKind.UNKNOWN,
        TodoLinkKind::value,
    )
}

/**
 * Binds a pull request, a release (their https address in [ref]), an artifact or a wait (their id) to an item. A
 * link is frozen: the same [ref] may be completed with [number], [sha] or [tag]; another of the same kind is refused
 * (`already_linked`), except to replace a pull request closed without merge ([replaceClosed]).
 *
 * @property number A pull request's number.
 * @property sha A pull request's merge commit.
 * @property tag A release's tag.
 */
@Serializable
public data class LinkTodoItemRequest(
    val sessionId: String? = null,
    val kind: TodoLinkKind,
    val ref: String,
    val number: Int? = null,
    val sha: String? = null,
    val tag: String? = null,
    val replaceClosed: Boolean = false,
)

/**
 * What the check at [index] of the item's stored condition observed.
 *
 * @property passed True passed, false failed, null unknown (unreachable, not linked yet): only true counts as done.
 * @property observed What was seen, at most 2 000 characters.
 * @property urls At most 10 https addresses that show it.
 */
@Serializable
public data class TodoCheckReport(
    val index: Int,
    val passed: Boolean? = null,
    val observed: String,
    val urls: List<String> = emptyList(),
)

/**
 * Reports what each check of an item observed: done only if every check passed.
 *
 * @property checks One report per position of the stored condition.
 */
@Serializable
public data class CompleteTodoItemRequest(
    val sessionId: String? = null,
    val checks: List<TodoCheckReport>,
)

/**
 * A link of an item.
 *
 * @property ref The https address of a pull request or a release, or the id of an artifact or a wait.
 * @property at When it was bound (ISO-8601).
 */
@Serializable
public data class TodoLink(
    val kind: TodoLinkKind,
    val ref: String,
    val number: Int? = null,
    val sha: String? = null,
    val tag: String? = null,
    val at: String,
)

/**
 * The last verification of an item: kept when it refused.
 *
 * @property at When (ISO-8601).
 * @property passed True passed, false failed, null unknown.
 * @property text What the checks observed.
 * @property urls The https addresses that show it.
 */
@Serializable
public data class TodoProof(
    val at: String,
    val passed: Boolean? = null,
    val text: String,
    val urls: List<String> = emptyList(),
)

/** A dated note of an item; notes are only appended. */
@Serializable
public data class TodoNote(
    val at: String,
    val text: String,
)

/** Where an item comes from. */
@Serializable(with = TodoItemOrigin.Serializer::class)
public enum class TodoItemOrigin(
    /** The JSON value. */
    public val value: String,
) {
    /** Written with the list. */
    PLAN("plan"),

    /** Added on the way (`addTodoItem`, or a pull request no item covered). */
    DISCOVERED("discovered"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoItemOrigin>(
        "run.ostrom.core.api.models.TodoItemOrigin",
        TodoItemOrigin.entries,
        TodoItemOrigin.UNKNOWN,
        TodoItemOrigin::value,
    )
}

/** The state of an item. */
@Serializable(with = TodoItemStatus.Serializer::class)
public enum class TodoItemStatus(
    /** The JSON value. */
    public val value: String,
) {
    TODO("todo"),
    DOING("doing"),
    DONE("done"),

    /** A wait asks the session's owner whether to drop it. */
    DROP_REQUESTED("drop_requested"),

    /** Dropped on the owner's yes; never deleted. */
    DROPPED("dropped"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoItemStatus>(
        "run.ostrom.core.api.models.TodoItemStatus",
        TodoItemStatus.entries,
        TodoItemStatus.UNKNOWN,
        TodoItemStatus::value,
    )
}

/**
 * An item of the list, as the server keeps it.
 *
 * @property origin `plan` or `discovered`.
 * @property originReason Why a discovered item was added.
 * @property proof The last verification, if any.
 * @property dropReason Why a drop was asked.
 * @property dropWaitId The wait that asks the owner about the drop.
 * @property unverified Every check of the condition is `attested`: nothing verifies it.
 */
@Serializable
public data class TodoItem(
    val id: String,
    val name: String,
    val description: String,
    val doneWhen: TodoDoneWhen,
    val dependsOn: List<String> = emptyList(),
    val origin: TodoItemOrigin,
    val originReason: String? = null,
    val status: TodoItemStatus,
    val proof: TodoProof? = null,
    val notes: List<TodoNote> = emptyList(),
    val dropReason: String? = null,
    val dropWaitId: String? = null,
    val links: List<TodoLink> = emptyList(),
    val unverified: Boolean = false,
)

/** The state of a list. */
@Serializable(with = TodoStatus.Serializer::class)
public enum class TodoStatus(
    /** The JSON value. */
    public val value: String,
) {
    ACTIVE("active"),

    /** Every item is done or dropped and the final verification held. */
    DONE("done"),
    ABANDONED("abandoned"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoStatus>(
        "run.ostrom.core.api.models.TodoStatus",
        TodoStatus.entries,
        TodoStatus.UNKNOWN,
        TodoStatus::value,
    )
}

/** What the Stop hook recorded about itself. */
@Serializable(with = TodoHookEventKind.Serializer::class)
public enum class TodoHookEventKind(
    /** The JSON value. */
    public val value: String,
) {
    /** The third stop without progress opened a wait for the owner. */
    BLOCKED("blocked"),

    /** The hook failed open (`recordTodoHookError`). */
    HOOK_ERROR("hook_error"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoHookEventKind>(
        "run.ostrom.core.api.models.TodoHookEventKind",
        TodoHookEventKind.entries,
        TodoHookEventKind.UNKNOWN,
        TodoHookEventKind::value,
    )
}

/** An event of the Stop hook kept on the list (the newest 20). */
@Serializable
public data class TodoHookEvent(
    val at: String,
    val kind: TodoHookEventKind,
    val text: String,
)

/**
 * A session's todo.
 *
 * @property sessionId The session's root id: a reopened segment (`~r<n>`) keeps its list.
 * @property stall Blocked stops in a row without progress; at 3 a wait asks the owner.
 * @property items Every item, in order; nothing is deleted.
 * @property nextItemId The first `todo` item whose dependencies are done (or dropped), or null.
 * @property hookEvents What the Stop hook recorded about itself, oldest first.
 */
@Serializable
public data class Todo(
    val id: String,
    val sessionId: String,
    val reference: String,
    val refs: List<WaitRef> = emptyList(),
    val status: TodoStatus,
    val stall: Int = 0,
    val items: List<TodoItem>,
    val createdAt: String,
    val updatedAt: String,
    val nextItemId: String? = null,
    val hookEvents: List<TodoHookEvent> = emptyList(),
)

/**
 * The answer of a read or a change.
 *
 * @property todo The list; null while the session has none.
 */
@Serializable
public data class SessionTodo(
    val todo: Todo?,
)

/**
 * The answer of `startTodoItem`.
 *
 * @property marker `<!-- ostrom-todo: <first 8 characters of the list id>/<item id> -->`, to put in the body of the
 *   item's pull request so it is found whatever its number.
 */
@Serializable
public data class StartTodoItemResponse(
    val todo: Todo,
    val marker: String,
)

/**
 * The reminder block of a session (after a compaction, on resume).
 *
 * @property text The reference, the item in progress (else the next one) and the list; null without an active list.
 */
@Serializable
public data class TodoContext(
    val text: String?,
)

/** The phase of a stop the Stop hook asks about. */
@Serializable(with = TodoStopPhase.Serializer::class)
public enum class TodoStopPhase(
    /** The JSON value. */
    public val value: String,
) {
    /** Nothing run yet. */
    BEGIN("begin"),

    /** With the outcome of the cheap checks and the pull requests the session opened. */
    VERIFY("verify"),

    /** With the outcome of every check of the done items. */
    SETTLE("settle"),

    /** A value this version does not know; the server refuses it with `invalid_stop`. Never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoStopPhase>(
        "run.ostrom.core.api.models.TodoStopPhase",
        TodoStopPhase.entries,
        TodoStopPhase.UNKNOWN,
        TodoStopPhase::value,
    )
}

/**
 * The outcome of the checks the Stop hook ran for one item.
 *
 * @property checks By position in the item's stored condition.
 */
@Serializable
public data class TodoStopItemReport(
    val itemId: String,
    val checks: List<TodoCheckReport> = emptyList(),
)

/**
 * One stop of the agent, asked by the Stop hook (`ostrom hook todo-stop`) in up to three phases.
 *
 * @property phase `begin` (when absent), `verify` or `settle`.
 * @property background A background task or a scheduled wake-up of the session still runs: the stop passes.
 * @property items `verify`: the checks of the item in progress and of the `todo` items whose checks are all cheap;
 *   `settle`: every check of the done items. Each item once, at most 500.
 * @property orphans `verify`: the https addresses of the pull requests the session opened (`gh pr create`), at most 5.
 * @property lastMessage The agent's last message, kept to 2 000 characters (shown in the stall wait, 600).
 */
@Serializable
public data class TodoStopRequest(
    val sessionId: String? = null,
    val phase: TodoStopPhase = TodoStopPhase.BEGIN,
    val background: Boolean = false,
    val items: List<TodoStopItemReport> = emptyList(),
    val orphans: List<String> = emptyList(),
    val lastMessage: String? = null,
)

/** What the Stop hook does next. */
@Serializable(with = TodoStopAction.Serializer::class)
public enum class TodoStopAction(
    /** The JSON value. */
    public val value: String,
) {
    /** The stop passes. */
    ALLOW("allow"),

    /** Run the cheap checks of the items of the list and ask again with `verify`. */
    VERIFY("verify"),

    /** Run every check of the done items and ask again with `settle`. */
    SETTLE("settle"),

    /** Refuse the stop and give the agent the reason. */
    BLOCK("block"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<TodoStopAction>(
        "run.ostrom.core.api.models.TodoStopAction",
        TodoStopAction.entries,
        TodoStopAction.UNKNOWN,
        TodoStopAction::value,
    )
}

/**
 * The server's answer to a stop.
 *
 * @property todo The list as it stands; the hook reads from it the checks to run for `verify` and `settle`.
 * @property reason `block`: the resume message to give the agent, at most 8 000 characters.
 * @property why `allow`: `no_list`, `hook_disabled`, `finished`, `background_task`, `wait_open`, `settled` or
 *   `escalated`.
 * @property escalated The third stop without progress opened a wait for the owner.
 */
@Serializable
public data class TodoStopDecision(
    val action: TodoStopAction,
    val todo: Todo? = null,
    val reason: String? = null,
    val why: String? = null,
    val escalated: Boolean = false,
)

/**
 * Clears the session's closed todo (`resetTodo`): the answer is a [SessionTodo] whose `todo` is null.
 *
 * @property sessionId The session, named by its owner or by its agent token.
 */
@Serializable
public data class ResetTodoRequest(
    val sessionId: String? = null,
)

/**
 * A failure of the Stop hook, recorded on the list so a broken hook shows.
 *
 * @property text What failed, kept to 500 characters.
 */
@Serializable
public data class TodoHookErrorRequest(
    val sessionId: String? = null,
    val text: String,
)

/** Turns the todo Stop hook on or off for a member. */
@Serializable
public data class TodoStopHookRequest(
    val enabled: Boolean,
)

/**
 * The todo Stop hook setting of a member; a member without a setting has it on.
 *
 * @property memberId The member's id (`Member.id`).
 */
@Serializable
public data class TodoStopHook(
    val memberId: String,
    val enabled: Boolean,
)

/**
 * The members whose todo Stop hook is off.
 *
 * @property members Every member of the organization with the hook off, `enabled` false.
 */
@Serializable
public data class TodoStopHooks(
    val members: List<TodoStopHook>,
)
