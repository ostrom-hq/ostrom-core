package run.ostrom.core.api.models

import run.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What starts an automation. */
@Serializable(with = AutomationTriggerKind.Serializer::class)
public enum class AutomationTriggerKind(
    /** The JSON value. */
    public val value: String,
) {
    CRON("cron"),
    WEBHOOK("webhook"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<AutomationTriggerKind>(
        "run.ostrom.core.api.models.AutomationTriggerKind",
        AutomationTriggerKind.entries,
        AutomationTriggerKind.UNKNOWN,
        AutomationTriggerKind::value,
    )
}

/**
 * The trigger of an automation.
 *
 * @property kind Cron or webhook.
 * @property cron Cron expression.
 * @property timezone IANA time zone of the cron.
 * @property catchup `skip` or `once` after downtime.
 * @property catchupGraceMinutes How late a missed run may still start.
 */
@Serializable
public data class AutomationTrigger(
    val kind: AutomationTriggerKind,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: String? = null,
    val catchupGraceMinutes: Int? = null,
)

/**
 * What an automation starts: a workflow run, or a cloud agent session with a prompt.
 *
 * @property workflowId Workflow to run.
 * @property prompt Prompt of a cloud agent session (`{{payload.x}}` variables).
 * @property agent Coding agent.
 * @property model Model.
 * @property effort Effort.
 * @property maxRuntimeMinutes Runtime limit.
 */
@Serializable
public data class AutomationAction(
    val workflowId: String? = null,
    val prompt: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int = 60,
)

/**
 * A variable read from a webhook payload.
 *
 * @property name Name.
 * @property path JSON path in the payload.
 * @property type `string`, `int`, `number`, `boolean` or `enum`.
 * @property values Values of an enum.
 * @property maxLength Maximum length.
 * @property required Required.
 * @property sensitive Never logged or shown.
 */
@Serializable
public data class AutomationVariable(
    val name: String,
    val path: String,
    val type: String,
    val values: List<String> = emptyList(),
    val maxLength: Int? = null,
    val required: Boolean = false,
    val sensitive: Boolean = false,
)

/**
 * An automation: a cron or a signed webhook that starts a workflow run or an agent session.
 * Webhooks are signed `Ostrom-Signature: v1=<hex HMAC-SHA256 of "<timestamp>.<raw body>">`, at most 5 minutes old.
 *
 * @property id Automation id.
 * @property name Name.
 * @property owner Login of the owner, in whose name runs start.
 * @property enabled Enabled.
 * @property trigger Trigger.
 * @property action What it starts.
 * @property variables Webhook variables.
 * @property samplePayload Sample webhook payload, for the editor.
 * @property overlap `skip`, `queue` or `allow` when a run is still going.
 * @property maxRunsPerDay Runs per day at most.
 * @property webhookUrl URL to call, for a webhook automation.
 * @property version Version, raised at each save.
 * @property createdAt ISO creation.
 * @property updatedAt ISO last save.
 * @property nextRunAt ISO next cron slot, while enabled.
 * @property previousSecretValidUntil ISO end of the former webhook secret's grace after a rotation.
 * @property secret The webhook secret, only in the answer to its creation.
 */
@Serializable
public data class Automation(
    val id: String,
    val name: String,
    val owner: String,
    val enabled: Boolean,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val variables: List<AutomationVariable> = emptyList(),
    val samplePayload: JsonElement? = null,
    val overlap: String = "skip",
    val maxRunsPerDay: Int = 24,
    val webhookUrl: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val nextRunAt: String? = null,
    val previousSecretValidUntil: String? = null,
    val secret: String? = null,
)

/** Status of a workflow run. */
@Serializable(with = RunStatus.Serializer::class)
public enum class RunStatus(
    /** The JSON value. */
    public val value: String,
) {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING("waiting"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<RunStatus>(
        "run.ostrom.core.api.models.RunStatus",
        RunStatus.entries,
        RunStatus.UNKNOWN,
        RunStatus::value,
    )
}

/**
 * A workflow run.
 *
 * @property id Run id.
 * @property workflowId Workflow.
 * @property workflowName Workflow name.
 * @property workflowVersion Version run (the definition is copied at start).
 * @property status Status.
 * @property startedBy Login.
 * @property source `manual`, `cron`, `webhook`, `test`, `workflow` (child run).
 * @property automationId Automation that started it.
 * @property inputs Inputs.
 * @property currentNodeId Node being visited.
 * @property parentRunId Parent run.
 * @property sessionId Agent session of the run.
 * @property visits Visits, in order.
 * @property failure Why it failed.
 * @property output Output.
 * @property receiptId Receipt of the run.
 * @property startedAt ISO start.
 * @property endedAt ISO end.
 */
@Serializable
public data class WorkflowRun(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val workflowVersion: Int,
    val status: RunStatus,
    val startedBy: String,
    val source: String,
    val automationId: String? = null,
    val inputs: Map<String, String> = emptyMap(),
    val currentNodeId: String? = null,
    val parentRunId: String? = null,
    val sessionId: String? = null,
    val visits: List<NodeVisit> = emptyList(),
    val failure: String? = null,
    val output: String? = null,
    val receiptId: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
)

/**
 * A visit of a node by a run.
 *
 * @property seq Order.
 * @property nodeId Node.
 * @property kind Node kind.
 * @property status `running`, `waiting`, `done`, `failed`.
 * @property outcome Outcome that chose the next edge.
 * @property output Output.
 * @property sessionId Agent session.
 * @property waitId Wait.
 * @property gateId Gate request.
 * @property childRunId Child run.
 * @property startedAt ISO start.
 * @property endedAt ISO end.
 */
@Serializable
public data class NodeVisit(
    val seq: Int,
    val nodeId: String,
    val kind: WorkflowNodeKind,
    val status: String,
    val outcome: String? = null,
    val output: String? = null,
    val sessionId: String? = null,
    val waitId: String? = null,
    val gateId: String? = null,
    val childRunId: String? = null,
    val startedAt: String,
    val endedAt: String? = null,
)

/** Status of a wait. */
@Serializable(with = WaitStatus.Serializer::class)
public enum class WaitStatus(
    /** The JSON value. */
    public val value: String,
) {
    OPEN("open"),
    RESOLVED("resolved"),
    CANCELLED("cancelled"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<WaitStatus>(
        "run.ostrom.core.api.models.WaitStatus",
        WaitStatus.entries,
        WaitStatus.UNKNOWN,
        WaitStatus::value,
    )
}

/**
 * A wait: a question to a person, asked by an agent (`ask_human`) or by a wait node of a run. First answer wins.
 *
 * @property id Wait id.
 * @property kind `question`, `permission`, `workflow`, `turn_failed`, `info`.
 * @property status Status.
 * @property sessionId Session that asked.
 * @property runId Run that asked.
 * @property assignee Login asked.
 * @property createdBy Login that asked.
 * @property question The question.
 * @property answer The answer, once resolved.
 * @property createdAt ISO creation.
 * @property resolvedAt ISO resolution.
 */
@Serializable
public data class Wait(
    val id: String,
    val kind: String,
    val status: WaitStatus,
    val sessionId: String? = null,
    val runId: String? = null,
    val assignee: String,
    val createdBy: String? = null,
    val question: WaitSpec,
    val answer: WaitAnswer? = null,
    val createdAt: String,
    val resolvedAt: String? = null,
)

/**
 * The answer to a wait.
 *
 * @property action Action id chosen.
 * @property values Field values.
 * @property author Login who answered.
 * @property answeredAt ISO time.
 */
@Serializable
public data class WaitAnswer(
    val action: String,
    val values: Map<String, JsonElement> = emptyMap(),
    val author: String,
    val answeredAt: String,
)

/**
 * How long the server may hold a wake of a session's waits back (`POST /api/v1/waits/wake`).
 *
 * @property waitMillis 0 to 30 000; 25 000 when absent.
 */
@Serializable
public data class WaitWakeRequest(val waitMillis: Long? = null)

/**
 * What the waits of a session have to say to its Stop hook.
 *
 * @property open A wait asked by the session is still open: the hook keeps holding the agent.
 * @property messages The answers that arrived, oldest first, each handed over once (`[Ostrom wait answered] …`).
 */
@Serializable
public data class WaitWake(val open: Boolean, val messages: List<String>)
