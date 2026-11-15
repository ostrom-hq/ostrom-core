// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

// Remote sessions (`runners.cloud.remote.v1`), as TeamCode's (docs/cloud-sessions.md, Remote sessions): a cloud
// session whose runner is a member's own device, under the member's own sign-in instead of a pod's token, hosted by
// `ostrom remote` or by the device's daemon (`runners.cloud.remote.daemon.v1`, the models in Devices.kt).

/** The values of [CloudSession.kind] and [CreateCloudSessionRequest.kind]. */
public object CloudSessionKinds {
    /** A pod (or container) the server starts; a session without a kind is one. */
    public const val CLOUD: String = "cloud"

    /** A member's own device runs it (`runners.cloud.remote.v1`), always in `conversation` mode. */
    public const val REMOTE: String = "remote"

    /**
     * The terminal of a member's `ostrom <agent>` (`runners.cloud.local.v1`), attached to its launch: no pod, profile,
     * workspace or cap; it takes no message and is never paused, resumed or transferred by a person (409
     * `local_session`, `local_not_transferable`).
     */
    public const val LOCAL: String = "local"

    /** Every kind, in contract order. */
    public val ALL: List<String> = listOf(CLOUD, REMOTE, LOCAL)
}

/** The values of [CreateCloudSessionRequest.permissionMode] and [CloudSessionHost.permissionMode]. */
public object RemotePermissionModes {
    /**
     * Claude Code's permission requests come to the organization's clients (`runners.cloud.permissions.v1`); Codex
     * stays in its sandbox, limited to the directory. The default.
     */
    public const val ASK: String = "ask"

    /** Every tool use is approved in advance, as a pod's agent (sets [CloudSession.permissionBypass] at creation). */
    public const val BYPASS: String = "bypass"

    /** Every mode, in contract order. */
    public val ALL: List<String> = listOf(ASK, BYPASS)
}

/**
 * The device a remote session runs on.
 *
 * @property deviceId The [Device.id]; null once the device was removed.
 * @property deviceName The device's name as it is now.
 * @property platform The device's platform as it is now ([DevicePlatforms]), null until its installation reported it.
 * @property osVersion The system's version reported with [platform].
 * @property directoryName The last segment of the session's directory, never its path.
 * @property online The host runs the session now: an execution registered and heartbeating (a silent host is noticed
 *   within 90 s).
 * @property lastHeartbeatAt The host's latest heartbeat.
 * @property permissionMode `ask` or `bypass` ([RemotePermissionModes]), chosen at creation.
 * @property folderId The published folder the device's daemon started the session in
 *   (`runners.cloud.remote.daemon.v1`); null for a session started on the device itself.
 */
@Serializable
public data class CloudSessionHost(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val platform: String? = null,
    val osVersion: String? = null,
    val directoryName: String? = null,
    val online: Boolean = false,
    val lastHeartbeatAt: String? = null,
    val permissionMode: String? = null,
    val folderId: String? = null,
)

/**
 * The execution a remote session's host just started (`POST /api/v1/cloud/sessions/{id}/host`): the host then calls
 * the runner channel for [orgId], the session and [epoch] with the same sign-in, until it is fenced
 * (`409 execution_fenced`).
 */
@Serializable
public data class CloudHost(
    val orgId: String,
    val epoch: Long,
    val session: CloudSession,
)

/** The bounds of remote sessions, as TeamCode's. */
public object RemoteSessionLimits {
    /** The longest [CreateCloudSessionRequest.directoryName]. */
    public const val MAX_DIRECTORY_NAME: Int = 255

    /** The default of the organization setting `remoteIdleTimeoutMinutes`: a remote session idle that long pauses. */
    public const val DEFAULT_IDLE_TIMEOUT_MINUTES: Int = 60

    /** The longest `remoteIdleTimeoutMinutes`; 0 never pauses an idle remote session. */
    public const val MAX_IDLE_TIMEOUT_MINUTES: Int = 1440

    /**
     * Whether [name] is a directory's last segment: 1 to [MAX_DIRECTORY_NAME] characters, not `.` or `..`, no `/`,
     * `\` or control character (`400 invalid_directory_name` otherwise).
     */
    public fun isDirectoryName(name: String): Boolean =
        name.length in 1..MAX_DIRECTORY_NAME && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }
}
