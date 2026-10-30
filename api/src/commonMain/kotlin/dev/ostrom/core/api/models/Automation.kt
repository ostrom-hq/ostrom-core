package dev.ostrom.core.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** What starts an automation. */
@Serializable
public enum class AutomationTriggerKind {
    @SerialName("cron") CRON,
    @SerialName("webhook") WEBHOOK,
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
)

/** Status of a workflow run. */
@Serializable
public enum class RunStatus {
    @SerialName("queued") QUEUED,
    @SerialName("running") RUNNING,
    @SerialName("waiting") WAITING,
    @SerialName("succeeded") SUCCEEDED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
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
@Serializable
public enum class WaitStatus {
    @SerialName("open") OPEN,
    @SerialName("resolved") RESOLVED,
    @SerialName("cancelled") CANCELLED,
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
