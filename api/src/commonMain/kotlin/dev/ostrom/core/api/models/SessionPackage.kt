package dev.ostrom.core.api.models

import dev.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.Serializable

/**
 * The envelope a session package is served in (`ostrom.package/1`). [payload] is the canonical text of a
 * [SessionPackage]; the launcher verifies [digest] and [signature] on its exact bytes and never re-canonicalizes it.
 *
 * @property format Always `ostrom.package/1`.
 * @property version `p<yyyyMMddHHmm of compiledAt>-<8 first hex of contentDigest>`.
 * @property repo Repository the package was compiled for, or null for every repository.
 * @property digest SHA-256 hex of the UTF-8 bytes of [payload].
 * @property contentDigest Digest of the content without the server, the harness reduced to its digest and the files
 * reduced to `{p, m, s}`: two compilations of the same content share it.
 * @property alg Always `ed25519`.
 * @property kid Key id of the signing key.
 * @property signature Base64 Ed25519 signature of the payload bytes, without the `ed25519;…` prefix of receipts.
 * @property payload Canonical text of the [SessionPackage].
 * @property pinned True when this version is the one pinned for the repository.
 * @property revoked True when the version was revoked: launchers refuse it.
 */
@Serializable
public data class PackageEnvelope(
    val format: String,
    val version: String,
    val repo: String?,
    val digest: String,
    val contentDigest: String,
    val alg: String,
    val kid: String,
    val signature: String,
    val payload: String,
    val pinned: Boolean,
    val revoked: Boolean,
)

/**
 * A compiled session package: the harness a coding agent session runs under (hooks, scripts, skills, sub-agents, MCP
 * servers, session context), compiled from the accepted workflows of a repository.
 *
 * @property format Always `ostrom.package/1`.
 * @property compiler Version of the compiler.
 * @property compiledAt ISO compilation time.
 * @property version Package version (see [PackageEnvelope.version]).
 * @property contentDigest See [PackageEnvelope.contentDigest].
 * @property repo Repository, or null.
 * @property server Base URL of the Ostrom server the hooks call.
 * @property harness The harness of rules the package includes.
 * @property workflows The workflows compiled in, with their decision.
 * @property nodes What each node produces.
 * @property hooks The hook rules, in order.
 * @property scripts The scripts check nodes run.
 * @property notifications The notifications notify nodes send.
 * @property mcp MCP servers and tools the session gets.
 * @property context Text added to the session context at start.
 * @property files Every file of the package (skills, sub-agents, scripts, plugin manifest), sorted by path.
 */
@Serializable
public data class SessionPackage(
    val format: String,
    val compiler: String,
    val compiledAt: String,
    val version: String,
    val contentDigest: String,
    val repo: String?,
    val server: String,
    val harness: PackageHarness,
    val workflows: List<PackageWorkflow>,
    val nodes: List<CompiledNode>,
    val hooks: List<CompiledHook>,
    val scripts: List<CompiledScript>,
    val notifications: List<CompiledNotify>,
    val mcp: PackageMcp,
    val context: String,
    val files: List<PackageFile>,
)

/**
 * The harness of rules included in a package.
 *
 * @property version Harness version, `h<yyyyMMddHHmm>-<8 hex>`.
 * @property digest Digest of the harness.
 * @property receiptId Receipt of the harness (`subject.kind` `harness`), when signed.
 */
@Serializable
public data class PackageHarness(
    val version: String,
    val digest: String,
    val receiptId: String? = null,
)

/**
 * A workflow compiled into a package.
 *
 * @property id Workflow id.
 * @property name Workflow name.
 * @property status `proposed` (compiled in shadow only), `accepted` or `edited`.
 * @property digest Digest of the workflow as compiled.
 * @property decidedBy Login of the last signed decision on the workflow.
 * @property decisionReceipt Receipt of that decision.
 */
@Serializable
public data class PackageWorkflow(
    val id: String,
    val name: String,
    val status: WorkflowStatus,
    val digest: String,
    val decidedBy: String? = null,
    val decisionReceipt: String? = null,
)

/** Whether a compiled element acts (`enforce`) or is only logged (`shadow`). */
@Serializable(with = PackageMode.Serializer::class)
public enum class PackageMode(
    /** The JSON value. */
    public val value: String,
) {
    ENFORCE("enforce"),
    SHADOW("shadow"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<PackageMode>(
        "dev.ostrom.core.api.models.PackageMode",
        entries,
        UNKNOWN,
        PackageMode::value,
    )
}

/** Agent events, with Claude Code's names (each launcher adapter translates them). */
@Serializable(with = AgentEvent.Serializer::class)
public enum class AgentEvent(
    /** The JSON value. */
    public val value: String,
) {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    STOP("Stop"),
    SESSION_END("SessionEnd"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<AgentEvent>(
        "dev.ostrom.core.api.models.AgentEvent",
        entries,
        UNKNOWN,
        AgentEvent::value,
    )
}

/** Where a script or a notification runs. */
@Serializable(with = RunLocation.Serializer::class)
public enum class RunLocation(
    /** The JSON value. */
    public val value: String,
) {
    /** In a sandbox on the developer's machine. */
    WORKSTATION("workstation"),

    /** On the Ostrom server (the secret never reaches the machine). */
    OSTROM("ostrom"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<RunLocation>(
        "dev.ostrom.core.api.models.RunLocation",
        entries,
        UNKNOWN,
        RunLocation::value,
    )
}

/**
 * A hook rule: when the agent event fires on a matching tool call, run the check, ask the gate or send the notification.
 *
 * @property id Hook id.
 * @property nodeId Workflow node it comes from.
 * @property workflowId Workflow of the node.
 * @property kind `check`, `gate` or `notify`.
 * @property event `PreToolUse` (check, gate) or `PostToolUse` (notify).
 * @property tools Tools the hook watches (`Bash`, `Write`, `mcp`…); empty = the default.
 * @property patterns Regular expressions (JavaScript syntax, case-insensitive) on the command.
 * @property classes Action classes of the gate catalog (`push-main`, `merge`, `deploy`…).
 * @property mode Enforced or shadow.
 * @property why Why the hook exists, shown when it blocks.
 * @property scriptId Script a check hook runs.
 * @property notifyId Notification a notify hook sends.
 * @property order Order among hooks of the same event.
 * @property suggested True when the hook comes from an analysis suggestion nobody took (always shadow).
 */
@Serializable
public data class CompiledHook(
    val id: String,
    val nodeId: String,
    val workflowId: String,
    val kind: WorkflowNodeKind,
    val event: AgentEvent,
    val tools: List<String>,
    val patterns: List<String>,
    val classes: List<String>,
    val mode: PackageMode,
    val why: String? = null,
    val scriptId: String? = null,
    val notifyId: String? = null,
    val order: Int,
    val suggested: Boolean? = null,
)

/**
 * A script a check node runs.
 *
 * @property id Script id.
 * @property nodeId Node it comes from.
 * @property workflowId Workflow of the node.
 * @property label Label of the check.
 * @property where Where it runs.
 * @property path Path of the script file in the package.
 * @property sha256 SHA-256 of the script file.
 * @property command The command run in the repository.
 * @property timeoutSec Timeout in seconds.
 * @property network True when the sandbox allows the network.
 * @property mode Enforced or shadow.
 * @property approval Who published this code.
 * @property suggested Evidence when the command comes from an analysis suggestion (never run).
 */
@Serializable
public data class CompiledScript(
    val id: String,
    val nodeId: String,
    val workflowId: String,
    val label: String,
    val where: RunLocation,
    val path: String,
    val sha256: String,
    val command: String,
    val timeoutSec: Int,
    val network: Boolean,
    val mode: PackageMode,
    val approval: ScriptApproval,
    val suggested: ScriptSuggestion? = null,
)

/**
 * Who published a script: the last signed decision on its workflow, if its author may publish code.
 *
 * @property by Login of the decider.
 * @property at ISO time of the decision.
 * @property decisionId Decision id.
 * @property receiptId Receipt of the decision.
 * @property allowed True when the script may run.
 * @property reason Why it may or may not.
 */
@Serializable
public data class ScriptApproval(
    val by: String? = null,
    val at: String? = null,
    val decisionId: String? = null,
    val receiptId: String? = null,
    val allowed: Boolean,
    val reason: String,
)

/**
 * The evidence of a command suggested by the analysis.
 *
 * @property evidence `repo/path:line` references.
 * @property excerpt The cited line.
 */
@Serializable
public data class ScriptSuggestion(
    val evidence: List<String>,
    val excerpt: String? = null,
)

/**
 * A notification a notify node sends, from the server.
 *
 * @property id Notification id.
 * @property nodeId Node it comes from.
 * @property workflowId Workflow of the node.
 * @property channel `log:<name>`, `slack:#channel` or `discord:#channel`.
 * @property message Text, with `{dev}`, `{repo}`, `{command}`, `{session}` placeholders.
 * @property mode Enforced or shadow.
 * @property suggestedChannel A channel the analysis saw, shown to people, never used to send.
 */
@Serializable
public data class CompiledNotify(
    val id: String,
    val nodeId: String,
    val workflowId: String,
    val channel: String,
    val message: String,
    val mode: PackageMode,
    val suggestedChannel: SuggestedChannel? = null,
)

/**
 * A notification channel suggested by the analysis.
 *
 * @property channel The channel.
 * @property evidence Where it was seen.
 */
@Serializable
public data class SuggestedChannel(
    val channel: String,
    val evidence: List<String>,
)

/**
 * What a workflow node produces in the session.
 *
 * @property id Node id.
 * @property workflowId Workflow id.
 * @property workflowName Workflow name.
 * @property kind Node kind.
 * @property label Node label.
 * @property mode Enforced or shadow.
 * @property produces The artifacts it produces.
 * @property trigger The action that triggers a check or a notify, resolved to patterns.
 * @property artifacts Paths and hook ids of what was produced.
 */
@Serializable
public data class CompiledNode(
    val id: String,
    val workflowId: String,
    val workflowName: String,
    val kind: WorkflowNodeKind,
    val label: String,
    val mode: PackageMode,
    val produces: List<Produced>,
    val trigger: CompiledTrigger? = null,
    val artifacts: List<String>,
)

/** The kinds of artifacts a node produces. */
@Serializable(with = ArtifactType.Serializer::class)
public enum class ArtifactType(
    /** The JSON value. */
    public val value: String,
) {
    SKILL("skill"),
    CONTEXT("context"),
    HOOK("hook"),
    SCRIPT("script"),
    MCP("mcp"),
    APPROVAL("approval"),
    NOTIFY("notify"),
    SUBAGENT("subagent"),
    RECEIPT("receipt"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<ArtifactType>(
        "dev.ostrom.core.api.models.ArtifactType",
        entries,
        UNKNOWN,
        ArtifactType::value,
    )
}

/**
 * One artifact a node produces.
 *
 * @property type Artifact type.
 * @property event Agent event, for hooks and context.
 * @property where Where a script or notification runs.
 * @property shadow True when it only logs (proposed workflow).
 * @property suggested True when it comes from an analysis suggestion nobody took.
 * @property note Explanation shown in the editor.
 */
@Serializable
public data class Produced(
    val type: ArtifactType,
    val event: AgentEvent? = null,
    val where: RunLocation? = null,
    val shadow: Boolean? = null,
    val suggested: Boolean? = null,
    val note: String? = null,
)

/**
 * The trigger of a check or notify node.
 *
 * @property from `node` (declared), `gate` (from a linked gate), `words` (from the node text), `suggested` or `none`.
 * @property gateNodeId The linked gate, when `from` is `gate`.
 * @property classes Action classes.
 * @property patterns Extra patterns.
 * @property resolved The final patterns the hook matches.
 */
@Serializable
public data class CompiledTrigger(
    val from: String,
    val gateNodeId: String? = null,
    val classes: List<String>,
    val patterns: List<String>,
    val resolved: List<String>,
)

/**
 * MCP servers and tools of a session.
 *
 * @property servers Servers, relative to the Ostrom server.
 * @property tools Tools announced in the context.
 */
@Serializable
public data class PackageMcp(
    val servers: List<PackageMcpServer>,
    val tools: List<PackageMcpTool>,
)

/**
 * An MCP server served by Ostrom.
 *
 * @property name Server name.
 * @property path Path on the Ostrom server.
 */
@Serializable
public data class PackageMcpServer(val name: String, val path: String)

/**
 * An MCP tool.
 *
 * @property name Tool name.
 * @property description Description.
 * @property server Server that serves it.
 */
@Serializable
public data class PackageMcpTool(val name: String, val description: String, val server: String)

/**
 * A file of a package.
 *
 * @property path Relative path (skills/…/SKILL.md, agents/…, scripts/…).
 * @property mode `file` or `executable`.
 * @property sha256 SHA-256 of the content.
 * @property content UTF-8 content.
 */
@Serializable
public data class PackageFile(
    val path: String,
    val mode: PackageFileMode,
    val sha256: String,
    val content: String,
)

/** File modes of a package. */
@Serializable(with = PackageFileMode.Serializer::class)
public enum class PackageFileMode(
    /** The JSON value. */
    public val value: String,
) {
    FILE("file"),
    EXECUTABLE("executable"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<PackageFileMode>(
        "dev.ostrom.core.api.models.PackageFileMode",
        entries,
        UNKNOWN,
        PackageFileMode::value,
    )
}
