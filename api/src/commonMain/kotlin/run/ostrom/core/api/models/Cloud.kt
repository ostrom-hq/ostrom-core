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
 * @property status `creating`, `running`, `pausing`, `paused`, `resuming`, `terminated` or `failed`.
 * @property desiredStatus What the owner asked for: `running`, `paused` or `terminated`.
 * @property phase An open label of the current step; while preparing, `configuring_git`, `cloning_repositories`,
 *   `fetching_repositories` or `running_setup`.
 * @property epoch The execution number, raised at each start or resume.
 * @property failure Why it failed or paused on its own.
 * @property environment Its preparation recipe, fixed at creation.
 * @property githubAccess `owner`, `shared` or `none` (`runners.cloud.github.v1`).
 */
@Serializable
public data class CloudSession(
    val id: String,
    val name: String? = null,
    val owner: String,
    val agent: String,
    val model: String? = null,
    val repo: String? = null,
    val ref: String? = null,
    val status: String,
    val desiredStatus: String? = null,
    val phase: String? = null,
    val epoch: Long? = null,
    val failure: CloudSessionFailure? = null,
    val limits: CloudSessionLimits? = null,
    val updatedAt: String? = null,
    val readyAt: String? = null,
    val lastActivityAt: String? = null,
    val pausedAt: String? = null,
    val runnerId: String? = null,
    val environment: CloudEnvironment? = null,
    val profile: CloudSessionProfile? = null,
    val image: String? = null,
    val githubAccess: String? = null,
    val runId: String? = null,
    val automationId: String? = null,
    val createdAt: String,
    val endedAt: String? = null,
)

/**
 * Why a cloud session failed or paused on its own.
 *
 * @property code `start_failed`, `execution_lost`, `idle_timeout`, `max_duration`, `hours_exhausted`,
 *   `preparation_failed`, `cluster_unavailable` or `namespace_not_allowed`.
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
    val setup: CloudSetup? = null,
    val requiredVariables: List<String> = emptyList(),
    val requireGitHub: Boolean? = null,
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
    val image: String? = null,
)

/** A page of the caller's cloud sessions, newest first; [next] is the cursor of the following page. */
@Serializable
public data class CloudSessionPage(
    val items: List<CloudSession>,
    val next: String? = null,
)
