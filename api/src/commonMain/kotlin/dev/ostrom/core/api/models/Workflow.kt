package dev.ostrom.core.api.models

import dev.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The kinds of workflow nodes. One model for everything Ostrom runs: the harness nodes compiled into session packages
 * (`trigger` to `agent`), and the nodes the server runs itself (`wait`, `workflow`), from TeamCode's workflow engine.
 */
@Serializable(with = WorkflowNodeKind.Serializer::class)
public enum class WorkflowNodeKind(
    /** The JSON value. */
    public val value: String,
) {
    /** Starts the workflow: a line of session context; with an [Automation], what launches a run. */
    TRIGGER("trigger"),

    /** Instructions and linked rules, delivered as a skill. */
    STEP("step"),

    /** A command run before an action, in a sandbox; a failure blocks the action. */
    CHECK("check"),

    /** A human yes before an action, bound to what is shown (repository, SHA, commits), then a signed receipt. */
    GATE("gate"),

    /** A message sent by the server after an action. */
    NOTIFY("notify"),

    /** A sub-agent of the session, or an agent session the server starts for a run. */
    AGENT("agent"),

    /** A question to a person (TeamCode wait): the run moves on with the answer. */
    WAIT("wait"),

    /** A child workflow run. */
    WORKFLOW("workflow"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<WorkflowNodeKind>(
        "dev.ostrom.core.api.models.WorkflowNodeKind",
        WorkflowNodeKind.entries,
        WorkflowNodeKind.UNKNOWN,
        WorkflowNodeKind::value,
    )
}

/** Status of a workflow: proposed by the analysis (shadow only) or decided by a person. */
@Serializable(with = WorkflowStatus.Serializer::class)
public enum class WorkflowStatus(
    /** The JSON value. */
    public val value: String,
) {
    PROPOSED("proposed"),
    ACCEPTED("accepted"),
    EDITED("edited"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<WorkflowStatus>(
        "dev.ostrom.core.api.models.WorkflowStatus",
        WorkflowStatus.entries,
        WorkflowStatus.UNKNOWN,
        WorkflowStatus::value,
    )
}

/**
 * A workflow.
 *
 * @property id Workflow id.
 * @property name Name, unique in the organization.
 * @property domain Domain it covers (`Backend`, `Mobile release`…).
 * @property description Free description.
 * @property version Version, raised at each save (optimistic locking).
 * @property repos Repositories the workflow applies to, `owner/name` where `*` is any part of a name; null for every
 *   repository of the organization. When present, the workflow applies to these only, observed or enforced.
 * @property nodes Nodes.
 * @property edges Edges between nodes.
 * @property start Id of the first node, when it is not the first `trigger`.
 * @property inputs Inputs of a run.
 * @property sources Where the analysis found it.
 * @property status Proposed or decided.
 * @property foundAt ISO time it was found or created.
 * @property updatedAt ISO time of the last save.
 * @property updatedBy Login of the last editor.
 */
@Serializable
public data class Workflow(
    val id: String,
    val name: String,
    val domain: String,
    val description: String? = null,
    val version: Int? = null,
    val repos: List<String>? = null,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val start: String? = null,
    val inputs: List<WorkflowInput> = emptyList(),
    val sources: List<RuleSource> = emptyList(),
    val status: WorkflowStatus,
    val foundAt: String,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/**
 * An edge. For a run, [on] is the outcome that follows it (`next`, `delivered`, a wait action id); [to] null ends.
 *
 * @property from Source node.
 * @property to Target node, null to end the run.
 * @property label Label shown in the editor.
 * @property on Outcome that takes this edge (default `next`).
 */
@Serializable
public data class WorkflowEdge(
    val from: String,
    val to: String?,
    val label: String? = null,
    val on: String? = null,
)

/**
 * An input of a workflow run.
 *
 * @property id Input id, used as `{{inputs.<id>}}`.
 * @property label Label.
 * @property required True when the run cannot start without it.
 * @property default Default value.
 * @property maxLength Maximum length.
 */
@Serializable
public data class WorkflowInput(
    val id: String,
    val label: String,
    val required: Boolean = false,
    val default: String? = null,
    val maxLength: Int = 4096,
)

/**
 * A workflow node.
 *
 * @property id Node id.
 * @property kind Kind.
 * @property label Label.
 * @property detail Instructions or details.
 * @property owner Login of the person responsible.
 * @property approvers Logins who may say yes (gate) or answer (wait).
 * @property approverReason Where the approvers come from.
 * @property when When to disturb people.
 * @property ruleIds Rules linked to the node.
 * @property position Position in the editor.
 * @property actions Boundary actions a gate covers (`covers` in the TeamCode survey): absent = guessed, empty = none.
 * @property actionsBy Who set [actions].
 * @property actionsAt When.
 * @property run What the node executes.
 * @property suggested What the analysis suggests, never executed until someone takes it.
 * @property wait The question of a wait node.
 * @property workflowId Child workflow of a `workflow` node.
 * @property arguments Arguments of the child workflow.
 * @property maxVisits How many times a run may visit the node.
 */
@Serializable
public data class WorkflowNode(
    val id: String,
    val kind: WorkflowNodeKind,
    val label: String,
    val detail: String? = null,
    val owner: String? = null,
    val approvers: List<String>? = null,
    val approverReason: String? = null,
    @SerialName("when") val whenToDisturb: NodeWhen? = null,
    val ruleIds: List<String>? = null,
    val position: NodePosition? = null,
    val actions: List<GateAction>? = null,
    val actionsBy: String? = null,
    val actionsAt: String? = null,
    val run: NodeRun? = null,
    val suggested: NodeSuggestions? = null,
    val wait: WaitSpec? = null,
    val workflowId: String? = null,
    val arguments: Map<String, String>? = null,
    val maxVisits: Int? = null,
)

/**
 * When to disturb people for a node.
 *
 * @property hours Hours (`09:00-19:00`).
 * @property days Days (`mon-fri`).
 * @property risk Risk level.
 */
@Serializable
public data class NodeWhen(
    val hours: String? = null,
    val days: String? = null,
    val risk: RiskLevel? = null,
)

/** Risk levels. */
@Serializable(with = RiskLevel.Serializer::class)
public enum class RiskLevel(
    /** The JSON value. */
    public val value: String,
) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<RiskLevel>(
        "dev.ostrom.core.api.models.RiskLevel",
        RiskLevel.entries,
        RiskLevel.UNKNOWN,
        RiskLevel::value,
    )
}

/**
 * Position of a node in the editor.
 *
 * @property x Horizontal position.
 * @property y Vertical position.
 */
@Serializable
public data class NodePosition(val x: Double, val y: Double)

/**
 * A boundary action a gate covers.
 *
 * @property cls `push-main`, `push-tag`, `merge`, `deploy`, `migration`, `mobile-release`, `client-message`, or
 * `custom-<slug>`.
 * @property label Label.
 * @property patterns Regular expressions on the command; empty = the class defaults.
 * @property repos Repositories where the gate covers it; empty = all.
 * @property tools Tools for a custom action (`Bash` by default, `Write`, `mcp`).
 */
@Serializable
public data class GateAction(
    @SerialName("class") val cls: String,
    val label: String,
    val patterns: List<String>,
    val repos: List<String>,
    val tools: List<String>? = null,
)

/**
 * What a node executes. Always a human setting: the analysis only suggests.
 *
 * @property on The action that triggers the node; absent = the closest gate in the graph.
 * @property command Check: the command run in the repository.
 * @property timeoutSec Check: timeout.
 * @property where Where the script runs.
 * @property network Check: network allowed in the sandbox.
 * @property channel Notify: `log:<name>`, `slack:#channel`, `discord:#channel`.
 * @property message Notify: text with `{dev}`, `{repo}`, `{command}`, `{session}`.
 * @property instructions Agent: instructions of the sub-agent.
 * @property agent Agent run by the server for a run (`claude-code`, `codex`…).
 * @property model Model of that agent.
 * @property deliverable What the agent must deliver for the run to move on.
 */
@Serializable
public data class NodeRun(
    val on: NodeRunOn? = null,
    val command: String? = null,
    val timeoutSec: Int? = null,
    val where: RunLocation? = null,
    val network: Boolean? = null,
    val channel: String? = null,
    val message: String? = null,
    val instructions: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val deliverable: Deliverable? = null,
)

/**
 * What triggers a node.
 *
 * @property classes Action classes.
 * @property patterns Patterns on the command.
 */
@Serializable
public data class NodeRunOn(
    val classes: List<String>? = null,
    val patterns: List<String>? = null,
)

/**
 * What an agent node delivers.
 *
 * @property type `artifact`, `url`, `text` or `json`.
 * @property label Label.
 * @property schema JSON schema of a `json` deliverable.
 */
@Serializable
public data class Deliverable(
    val type: String,
    val label: String? = null,
    val schema: JsonElement? = null,
)

/**
 * A string suggested by the analysis, with what proves it.
 *
 * @property value The value.
 * @property evidence `repo/path:line` references.
 * @property excerpt The cited line, re-read in the clone.
 * @property why Why.
 * @property by Always `analysis`.
 * @property at ISO time.
 */
@Serializable
public data class StringSuggestion(
    val value: String,
    val evidence: List<String>,
    val excerpt: String? = null,
    val why: String? = null,
    val by: String = "analysis",
    val at: String,
)

/**
 * A list of strings suggested by the analysis, with what proves it.
 *
 * @property value The values.
 * @property evidence `repo/path:line` references.
 * @property excerpt The cited line.
 * @property why Why.
 * @property by Always `analysis`.
 * @property at ISO time.
 */
@Serializable
public data class StringListSuggestion(
    val value: List<String>,
    val evidence: List<String>,
    val excerpt: String? = null,
    val why: String? = null,
    val by: String = "analysis",
    val at: String,
)

/**
 * What the analysis suggests for a node.
 *
 * @property command Check: the real CI or Makefile command.
 * @property on Check, notify: action classes.
 * @property actions Gate: action classes covered.
 * @property channel Notify: a channel seen in the CI or the docs.
 * @property ruleIds Rules matched to the node.
 * @property dismissed Fields a person dismissed: not suggested again.
 */
@Serializable
public data class NodeSuggestions(
    val command: StringSuggestion? = null,
    val on: StringListSuggestion? = null,
    val actions: StringListSuggestion? = null,
    val channel: StringSuggestion? = null,
    val ruleIds: StringListSuggestion? = null,
    val dismissed: List<String>? = null,
)

/**
 * Where a rule or a workflow was found.
 *
 * @property kind `code`, `ci`, `pr_review`, `history`, `doc` or `config`.
 * @property ref Reference (`repo/path:line`, PR…).
 * @property url Link.
 */
@Serializable
public data class RuleSource(
    val kind: String,
    val ref: String,
    val url: String? = null,
)

/**
 * The question of a wait node, or of a wait asked by an agent (TeamCode `ask_human`).
 *
 * @property title Title (200 characters at most).
 * @property body Body (32 000 characters at most).
 * @property fields Form fields.
 * @property actions Buttons; the action id is the outcome.
 * @property refs Links shown with the question.
 */
@Serializable
public data class WaitSpec(
    val title: String,
    val body: String? = null,
    val fields: List<WaitField> = emptyList(),
    val actions: List<WaitAction> = emptyList(),
    val refs: List<WaitRef> = emptyList(),
)

/**
 * A field of a wait.
 *
 * @property id Field id.
 * @property type `text`, `number`, `boolean`, `choice`, `info`…
 * @property label Label.
 * @property help Help text.
 * @property required Required.
 * @property default Default value.
 * @property options Options of a choice.
 * @property multiple Several options allowed.
 * @property allowOther Free value allowed.
 * @property maxLength Maximum length.
 */
@Serializable
public data class WaitField(
    val id: String,
    val type: String,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<String> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
)

/**
 * A button of a wait.
 *
 * @property id Action id.
 * @property label Label.
 * @property requires Fields required for this action.
 * @property style `primary`, `danger`…
 */
@Serializable
public data class WaitAction(
    val id: String,
    val label: String,
    val requires: List<String> = emptyList(),
    val style: String? = null,
)

/**
 * A link shown with a wait.
 *
 * @property kind `artifact`, `session` or `url`.
 * @property id Id of the artifact or session.
 * @property url URL.
 * @property title Title.
 */
@Serializable
public data class WaitRef(
    val kind: String,
    val id: String? = null,
    val url: String? = null,
    val title: String? = null,
)
