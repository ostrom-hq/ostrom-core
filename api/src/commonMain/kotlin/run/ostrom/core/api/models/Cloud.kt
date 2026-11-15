// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import run.ostrom.core.api.UnknownFallbackEnumSerializer

// Cloud agent sessions (`runners.cloud.*`), as the companion app reads, starts, pauses and resumes them, with the
// preparation recipe of `runners.cloud.environment.v1` (TeamCode's CloudEnvironment, docs/cloud-sessions.md).
// Statuses, phases and failure codes stay strings: a newer server may add one, which must not break a decode.

/**
 * A cloud agent session: one pod (or container) per execution, with a workspace kept across pauses.
 *
 * @property agent `claude`, `codex`, `opencode`, `gemini` or `shell`.
 * @property repo The legacy single repository cloned at the first start (`https://github.com/<owner>/<name>`).
 * @property status `creating`, `running`, `pausing`, `paused`, `resuming`, `transferring`, `terminated` or `failed`
 *   (`transferring`: the owner changes at a paused boundary, `runners.cloud.transfer.v1`).
 * @property desiredStatus What the owner asked for: `running`, `paused` or `terminated`.
 * @property phase An open label of the current step; while preparing, `securing_workspace` (after a transfer),
 *   `configuring_git`, `cloning_repositories`, `fetching_repositories` or `running_setup`.
 * @property epoch The execution number, raised at each start or resume.
 * @property failure Why it failed or paused on its own.
 * @property environment Its preparation recipe, fixed at creation.
 * @property githubAccess `owner`, `shared` or `none` (`runners.cloud.github.v1`).
 * @property expiresAt When a paused session is terminated with its workspace (`runners.cloud.retention.v1`).
 * @property terminatedReason `deleted` or `expired`, on a terminated session.
 * @property workspaceDeletion The proof its workspace is gone, on Kubernetes.
 * @property mode `terminal` (null: a PTY) or `conversation` (`runners.cloud.conversation.v1`), see [CloudSessionModes].
 * @property effort The agent's effort (reasoning level), if chosen (`runners.cloud.effort.v1`).
 * @property transferId The accepted transfer the session is moving toward, from its acceptance until the owner
 *   changed or the transfer ended (`runners.cloud.transfer.v1`).
 * @property permissionBypass The owner's standing approval of tool uses is on (`runners.cloud.permissions.bypass.v1`).
 * @property waiting Why the agent waits for a person (`runners.cloud.permissions.v1`); null when it does not.
 * @property kind `cloud` (null: a pod the server starts), `remote` (a member's device, `runners.cloud.remote.v1`) or
 *   `local` (the terminal of a member's `ostrom <agent>`, `runners.cloud.local.v1`), see [CloudSessionKinds].
 * @property host Where a remote session runs; null for a cloud or a local session.
 * @property launchId The `ostrom <agent>` launch a local session is attached to (`runners.cloud.local.v1`); null for
 *   every other kind.
 * @property createdBy Who created it (`runners.cloud.onbehalf.v1`); null for a session created before the feature.
 */
@Serializable
public data class CloudSession(
    val id: String,
    val name: String,
    val owner: String,
    val agent: String,
    val model: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val status: String,
    val desiredStatus: String,
    val phase: String? = null,
    val epoch: Long,
    val failure: CloudSessionFailure? = null,
    val limits: CloudSessionLimits,
    val updatedAt: String,
    val readyAt: String? = null,
    val lastActivityAt: String? = null,
    val pausedAt: String? = null,
    val expiresAt: String? = null,
    val terminatedReason: String? = null,
    val workspaceDeletion: CloudWorkspaceDeletion? = null,
    val runnerId: String? = null,
    val environment: CloudEnvironment? = null,
    val profile: CloudSessionProfile? = null,
    val image: String? = null,
    val githubAccess: String = "owner",
    val mode: String? = null,
    val effort: String? = null,
    val permissionBypass: Boolean? = null,
    val transferId: String? = null,
    val waiting: CloudSessionWaiting? = null,
    val automationId: String? = null,
    val createdAt: String,
    val endedAt: String? = null,
    val kind: String? = null,
    val host: CloudSessionHost? = null,
    val createdBy: CloudAttribution? = null,
    val launchId: String? = null,
)

/**
 * Who created a cloud session (`runners.cloud.onbehalf.v1`, TeamCode's attribution).
 *
 * @property initiatorId The member who made the request.
 * @property credentialId The id of the credential they used (a browser session, a token, a service key), never the
 *   token itself; null when the server knows none.
 * @property effectiveMemberId The member it was created for, its first owner: the initiator, unless
 *   [run.ostrom.core.api.OnBehalfOf.HEADER] named another member.
 */
@Serializable
public data class CloudAttribution(
    val initiatorId: String,
    val credentialId: String? = null,
    val effectiveMemberId: String,
)

/**
 * The proof that a session's workspace is gone, provider volume included (`runners.cloud.retention.v1`).
 *
 * @property state `none`, `pending`, `proven` or `blocked`.
 * @property volumeHandle The provider's volume handle, kept as evidence.
 * @property problem Why the deletion is blocked (`volume_retained`, `volume_deletion_stalled`).
 */
@Serializable
public data class CloudWorkspaceDeletion(
    val state: String,
    val claimDeletedAt: String? = null,
    val volumeDeletedAt: String? = null,
    val volumeHandle: String? = null,
    val problem: CloudSessionFailure? = null,
)

/**
 * Why a cloud session failed or paused on its own.
 *
 * @property code `start_failed`, `execution_lost`, `idle_timeout`, `max_duration`, `hours_exhausted`,
 *   `preparation_failed`, `cluster_unavailable`, `namespace_not_allowed`, `storage_class_missing` or
 *   `storage_class_retains_volumes`.
 * @property retryable Whether trying again may succeed (a clone or a fetch), for `preparation_failed`: resuming tries
 *   again; otherwise the recipe or the variables must be fixed.
 */
@Serializable
public data class CloudSessionFailure(
    val code: String,
    val message: String,
    val retryable: Boolean? = null,
)

/** The bounds a cloud session runs under, from its organization's plan. */
@Serializable
public data class CloudSessionLimits(
    val idleTimeoutMinutes: Int,
    val maxRunMinutes: Int,
    val restrictedEgress: Boolean,
)

/** The cloud profile a session runs on, at the version it was created with (`runners.cloud.profiles.v1`). */
@Serializable
public data class CloudSessionProfile(
    val id: String,
    val version: Int,
)

/**
 * The preparation recipe of a cloud session (`runners.cloud.environment.v1`): what the runner does in the workspace
 * before the agent starts, the same on every execution. It holds no secret.
 *
 * @property repositories Cloned on the first execution into `/workspace/<path>`, fetched afterwards (at most 16).
 * @property workingDirectory Relative to `/workspace`, where the setup and the agent start; the first repository's
 *   directory when null and there is one.
 * @property requiredVariables Environment variable names the owner must be assigned (at most 64).
 * @property requireGitHub Preparation fails when the owner has no usable GitHub link.
 * @property gitEmailDomain The domain of the Git e-mail address of an owner without a GitHub link.
 */
@Serializable
public data class CloudEnvironment(
    val repositories: List<CloudRepository> = emptyList(),
    val workingDirectory: String? = null,
    val setup: CloudSetup = CloudSetup(),
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean = false,
    val gitEmailDomain: String? = null,
)

/**
 * A repository the runner clones into the workspace.
 *
 * @property url `https://github.com/<owner>/<name>[.git]`, without credentials, query or fragment.
 * @property path Relative to `/workspace`; the repository's name when null.
 * @property ref The branch, tag or commit checked out; the default branch when null.
 * @property branch A local branch created at [ref] after cloning.
 */
@Serializable
public data class CloudRepository(
    val url: String,
    val path: String? = null,
    val ref: String? = null,
    val branch: String? = null,
)

/**
 * The setup commands, run with `/bin/sh -c` in the working directory.
 *
 * @property create Run once per workspace, in order, each marked done when it succeeds (at most 16).
 * @property start Run on every execution, after [create] (at most 16).
 */
@Serializable
public data class CloudSetup(
    val create: List<String> = emptyList(),
    val start: List<String> = emptyList(),
)

/** The agent a cloud session starts; `shell` is a plain shell, to prepare a workspace or debug an image. */
@Serializable(with = CloudAgentKind.Serializer::class)
public enum class CloudAgentKind(
    /** The JSON value. */
    public val value: String,
) {
    CLAUDE("claude"),
    CODEX("codex"),
    OPENCODE("opencode"),
    GEMINI("gemini"),
    SHELL("shell"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<CloudAgentKind>(
        "run.ostrom.core.api.models.CloudAgentKind",
        CloudAgentKind.entries,
        CloudAgentKind.UNKNOWN,
        CloudAgentKind::value,
    )
}

/**
 * The cloud session to start (`POST /api/v1/cloud/sessions`). Give either the legacy single [repo] (with [ref]) or an
 * [environment] recipe, which a server without `runners.cloud.environment.v1` ignores.
 *
 * @property prompt The agent's first message, up to 20,000 characters; an interactive agent when null.
 * @property profileId The organization's cloud profile (`runners.cloud.profiles.v1`, planned).
 * @property image An image instead of the profile's (planned).
 * @property mode `terminal` (null) or `conversation` (`runners.cloud.conversation.v1`, claude and codex only).
 * @property effort The agent's effort (reasoning level).
 * @property kind `remote` (`runners.cloud.remote.v1`) for a session on the caller's own device: claude or codex, always
 *   `conversation` mode, no repository, environment, profile or image; `cloud` or null for a pod.
 * @property directoryName With [kind] `remote`: the last segment of the directory the agent works in, never its path
 *   ([RemoteSessionLimits.isDirectoryName]). Omitted with [folderId], whose name it is.
 * @property permissionMode With [kind] `remote`: `ask` (null) or `bypass` ([RemotePermissionModes]).
 * @property deviceId With [kind] `remote` (`runners.cloud.remote.daemon.v1`): one of the caller's own devices, whose
 *   daemon starts the session in [folderId], one of the folders it published. Without it, the session runs on the
 *   device of the caller's sign-in.
 * @property folderId With [deviceId]: the published folder ([DeviceFolder.id]).
 * @property resumeSessionId With [deviceId] and [folderId] (`runners.cloud.remote.resume.v1`, claude only): the
 *   conversation of that folder the session continues, one the device's daemon listed.
 * @property launchId With [kind] `local` (`runners.cloud.local.v1`): the caller's own `ostrom <agent>` launch, still
 *   running, the session is attached to (send it as the `Idempotency-Key` too). A second request for the same launch
 *   returns the first session. Left out of the body when null.
 */
@Serializable
public data class CreateCloudSessionRequest(
    val agent: CloudAgentKind,
    val model: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val prompt: String? = null,
    val name: String? = null,
    val environment: CloudEnvironment? = null,
    val profileId: String? = null,
    val mode: String? = null,
    val effort: String? = null,
    val image: String? = null,
    val kind: String? = null,
    val directoryName: String? = null,
    val permissionMode: String? = null,
    val deviceId: String? = null,
    val folderId: String? = null,
    val resumeSessionId: String? = null,
    val launchId: String? = null,
)

/** A page of the caller's cloud sessions, newest first; [next] is the cursor of the following page. */
@Serializable
public data class CloudSessionPage(
    val items: List<CloudSession>,
    val next: String? = null,
)
