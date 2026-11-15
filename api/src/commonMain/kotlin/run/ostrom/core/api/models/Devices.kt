// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

// The workstations where a member signed in with the CLI (`devices.v1`), as TeamCode's devices (docs/devices.md): one
// device per installation of the CLI, kept across sign-ins, renamed by its owner, removed with all its sign-ins.

/**
 * A workstation where the CLI is signed in (`GET /api/v1/devices`).
 *
 * With `devices.v1`, a registered installation: [id] is the device's, kept across its sign-ins, and removing it signs
 * every one of them out. A token from a CLI older than that (no installation) is listed on its own, [id] being the
 * token's, as are the elevated tokens of `ostrom login --admin` ([kind] `admin`).
 *
 * @property name Its name, set by its first sign-in (`--device`) or renamed by its owner.
 * @property platform `macos`, `windows`, `linux`, `ios` or `android` as reported at its latest sign-in; null until one
 *   did.
 * @property osVersion The system's version reported with [platform].
 * @property cliVersion The CLI's version at its latest call.
 * @property lastSeenAt Its latest call.
 * @property kind `admin` for the elevated token of `ostrom login --admin`; `cli` when absent.
 * @property orgId The organization an elevated token is bound to.
 * @property scopes What an elevated token may do.
 * @property expiresAt When an elevated token expires.
 * @property createdAt When it was registered (`devices.v1`).
 * @property lastLoginAt Its latest sign-in (`devices.v1`).
 * @property activeSessions How many live sign-ins it holds (`devices.v1`).
 * @property current Whether the call came from it (`devices.v1`).
 * @property daemon The device's daemon (`runners.cloud.remote.daemon.v1`); null when it never polled or said it
 *   stopped.
 */
@Serializable
public data class Device(
    val id: String,
    val name: String,
    val platform: String? = null,
    val cliVersion: String? = null,
    val lastSeenAt: String? = null,
    val kind: String? = null,
    val orgId: String? = null,
    val scopes: List<String>? = null,
    val expiresAt: String? = null,
    val osVersion: String? = null,
    val createdAt: String? = null,
    val lastLoginAt: String? = null,
    val activeSessions: Int? = null,
    val current: Boolean? = null,
    val daemon: DaemonPresence? = null,
)

/** A page of devices; [next] is the cursor of the following page. */
@Serializable
public data class DevicePage(
    val items: List<Device>,
    val next: String? = null,
)

/** A device's new name (`PATCH /api/v1/devices/{id}`), [DeviceLimits.MAX_NAME] characters, no control character. */
@Serializable
public data class RenameDeviceRequest(
    val name: String,
)

/** The values of [Device.platform] and [DeviceLoginStart.platform]. */
public object DevicePlatforms {
    /** macOS. */
    public const val MACOS: String = "macos"

    /** Windows. */
    public const val WINDOWS: String = "windows"

    /** Linux. */
    public const val LINUX: String = "linux"

    /** iOS. */
    public const val IOS: String = "ios"

    /** Android. */
    public const val ANDROID: String = "android"

    /** Every platform, in contract order. */
    public val ALL: List<String> = listOf(MACOS, WINDOWS, LINUX, IOS, ANDROID)
}

/** The bounds of devices, as TeamCode's. */
public object DeviceLimits {
    /** The longest device name. */
    public const val MAX_NAME: Int = 120

    /** The longest system version. */
    public const val MAX_OS_VERSION: Int = 64

    /** The most devices a member registers; another sign-in from a new installation is refused past it. */
    public const val MAX_DEVICES: Int = 50

    /** An installation id: a lowercase UUID. */
    public val INSTALLATION_ID: Regex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

// The device's daemon (`runners.cloud.remote.daemon.v1`), as TeamCode's (docs/cloud-sessions.md, Sessions started by a
// device's daemon): the local service of a signed-in CLI polls for the remote sessions its owner starts on it from
// elsewhere, in the folders it publishes, and lists the agent conversations of a folder it could continue
// (`runners.cloud.remote.resume.v1`). Every route acts on the caller's own device only.

/**
 * A device's daemon as the server last saw it.
 *
 * @property online It polled less than [DaemonLimits.ONLINE_TTL_MILLIS] ago; only an online device accepts a session
 *   started from elsewhere.
 * @property lastSeenAt Its latest poll.
 * @property version The CLI's version it announced.
 * @property features What it announced at its latest poll ([DaemonFeatures]); empty for a daemon older than them.
 */
@Serializable
public data class DaemonPresence(
    val online: Boolean,
    val lastSeenAt: String,
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/** What a daemon announces in [DaemonPollRequest.features]. */
public object DaemonFeatures {
    /** It lists the conversations of a folder and starts a session from one (`runners.cloud.remote.resume.v1`). */
    public const val RESUME: String = "resume.v1"
}

/**
 * A folder a device's daemon offers: [id] is the daemon's own key ([DaemonLimits.FOLDER_ID]), [name] the folder's last
 * segment, [label] how its owner recognizes it (for example `~/git/ostrom`). Never a full path the server relies on.
 */
@Serializable
public data class DeviceFolder(
    val id: String,
    val name: String,
    val label: String? = null,
)

/** The folders the caller's device offers, replacing the previous list (`PUT /api/v1/devices/current/folders`). */
@Serializable
public data class PublishDeviceFoldersRequest(
    val folders: List<DeviceFolder>,
)

/** A device's daemon and published folders (`GET /api/v1/devices/{id}/folders`). */
@Serializable
public data class DeviceFolders(
    val deviceId: String,
    val daemon: DaemonPresence? = null,
    val folders: List<DeviceFolder>,
)

/**
 * A daemon's long poll (`POST /api/v1/devices/current/daemon/poll`): wait up to [waitMillis] (at most
 * [DaemonLimits.MAX_WAIT_MILLIS]) for an order, announcing the CLI's [version] and its [features].
 */
@Serializable
public data class DaemonPollRequest(
    val waitMillis: Long = 0,
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/**
 * A session the daemon is asked to host: start it in [folderId] (checking it against its own configuration first) with
 * `POST /api/v1/cloud/sessions/{sessionId}/host`, or refuse it.
 *
 * @property status `creating`, `resuming` or `preparing`.
 * @property permissionMode `ask` or `bypass` ([RemotePermissionModes]).
 * @property resumeSessionId The conversation of [folderId] the session continues (`runners.cloud.remote.resume.v1`):
 *   a Claude Code session id the daemon listed, which it checks again against that folder.
 */
@Serializable
public data class DaemonOrder(
    val sessionId: String,
    val folderId: String,
    val directoryName: String,
    val agent: String,
    val permissionMode: String,
    val status: String,
    val requestedAt: String,
    val resumeSessionId: String? = null,
)

/**
 * A poll's answer, to poll again at once: the [orders] (oldest first, at most [DaemonLimits.ORDER_BATCH]), how long the
 * device stays online without a poll ([onlineTtlMillis]), the longest wait ([maxWaitMillis]) and the [listings]
 * members wait for, each answered with `POST /api/v1/devices/current/daemon/listings/{requestId}`.
 */
@Serializable
public data class DaemonPoll(
    val orders: List<DaemonOrder>,
    val onlineTtlMillis: Long,
    val maxWaitMillis: Long,
    val listings: List<DaemonListing> = emptyList(),
)

/** List at most [limit] conversations of [folderId], the most recent first, after the cursor [before] when set. */
@Serializable
public data class DaemonListing(
    val requestId: String,
    val folderId: String,
    val limit: Int,
    val before: String? = null,
)

/**
 * An agent conversation a session can continue (`runners.cloud.remote.resume.v1`): its id, a short title (its name,
 * its summary or the start of its first message, at most [ResumableSessionLimits.TITLE_LENGTH] characters), when it was
 * last active and how many messages the member sent in it. Nothing else of the conversation leaves the device.
 */
@Serializable
public data class ResumableSession(
    val id: String,
    val title: String,
    val lastActiveAt: String,
    val turns: Int,
)

/**
 * A page of a folder's conversations (`GET /api/v1/devices/{id}/folders/{folderId}/resumable`); [next] is the `before`
 * of the following page.
 */
@Serializable
public data class ResumableSessionPage(
    val items: List<ResumableSession>,
    val next: String? = null,
)

/** The daemon's answer to a [DaemonListing]: the page, or [refused] with a reason code (`folder_not_configured`...). */
@Serializable
public data class DaemonListingAnswer(
    val items: List<ResumableSession> = emptyList(),
    val next: String? = null,
    val refused: String? = null,
)

/** Why the daemon does not host an order (`POST /api/v1/devices/current/daemon/sessions/{id}/refuse`). */
@Serializable
public data class RefuseDaemonOrderRequest(
    val reason: String,
)

/**
 * What `ostrom remote resumable DIRECTORY --json` prints: the directory's conversations, read on this device and never
 * sent to the server, or [unavailable], a [LocalResumeReasons] code, when this device cannot continue one.
 */
@Serializable
public data class LocalResumableSessions(
    val items: List<ResumableSession> = emptyList(),
    val next: String? = null,
    val unavailable: String? = null,
)

/**
 * What `ostrom remote folders --json` prints: the folders this device knows, read from its own files and never sent
 * to the server.
 */
@Serializable
public data class LocalKnownFolders(
    val folders: List<LocalKnownFolder> = emptyList(),
)

/** A folder this device knows: its full [path] (it stays on the device), [lastUsedAt] its latest session there. */
@Serializable
public data class LocalKnownFolder(
    val path: String,
    val lastUsedAt: String? = null,
)

/** Why this device cannot continue one of its own conversations in a new session (`ostrom remote start --resume`). */
public object LocalResumeReasons {
    /** The server does not announce `runners.cloud.remote.resume.v1`. */
    public const val SERVER_UNSUPPORTED: String = "server_unsupported"

    /** The device's daemon hosts no session on this system (Windows). */
    public const val PLATFORM_UNSUPPORTED: String = "platform_unsupported"

    /** The device's daemon is not running. */
    public const val DAEMON_OFFLINE: String = "daemon_offline"

    /** The running daemon did not announce [DaemonFeatures.RESUME]: an older CLI until it restarts. */
    public const val DAEMON_TOO_OLD: String = "daemon_too_old"
}

/** The bounds of a device's daemon, as TeamCode's. */
public object DaemonLimits {
    /** A device stays online this long after its latest poll. */
    public const val ONLINE_TTL_MILLIS: Long = 60_000

    /** The longest wait of a poll. */
    public const val MAX_WAIT_MILLIS: Long = 20_000

    /** The most orders one poll answers. */
    public const val ORDER_BATCH: Int = 16

    /** The most folders a device publishes. */
    public const val MAX_FOLDERS: Int = 64

    /** The most features a daemon announces. */
    public const val MAX_FEATURES: Int = 8

    /** How long a member waits for a daemon's listing before `504 device_no_answer`. */
    public const val LISTING_WAIT_MILLIS: Long = 10_000

    /** A folder's id, the daemon's own key. */
    public val FOLDER_ID: Regex = Regex("[A-Za-z0-9_-]{1,64}")

    /** A feature the daemon announces. */
    public val FEATURE: Regex = Regex("[a-z0-9._-]{1,32}")

    /** The CLI version the daemon announces. */
    public val VERSION: Regex = Regex("[A-Za-z0-9._+-]{1,32}")

    /** A refusal's reason code. */
    public val REASON: Regex = Regex("[a-z_]{1,64}")
}

/** The bounds of a folder's conversations (`runners.cloud.remote.resume.v1`), as TeamCode's. */
public object ResumableSessionLimits {
    /** The longest title. */
    public const val TITLE_LENGTH: Int = 80

    /** The page size when none is asked. */
    public const val DEFAULT_LIMIT: Int = 20

    /** The largest page. */
    public const val MAX_LIMIT: Int = 50

    /** A Claude Code session id: a lowercase UUID, the name of its transcript. */
    public val ID: Regex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

/** The bounds of the folders `ostrom remote folders` lists. */
public object LocalKnownFolderLimits {
    /** The list's length when none is asked. */
    public const val DEFAULT_LIMIT: Int = 20

    /** The longest list. */
    public const val MAX_LIMIT: Int = 50
}
