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
