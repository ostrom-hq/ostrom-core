// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

// Environment variants (`environment.v1`): a named value for one environment variable, assigned to members, as
// TeamCode's (docs/variants.md). A value is written once and never returned; only `getEnvironment` gives the caller
// their own values.

/**
 * An environment variant, never with its value.
 *
 * @property variable The environment variable it fills (`[A-Z_][A-Z0-9_]*`, at most 128 characters).
 * @property name The variant's name, unique per variable and owner.
 * @property binding `shared` (one value on the variant) or `perMember` (each assignment carries its own); a string so
 *   a new binding never breaks a decode.
 * @property owner The member a personal variant belongs to; null for a team variant.
 * @property hasValue A shared value is stored.
 * @property version Bumped at each change; sent back as [EnvironmentVariantUpdate.expectedVersion].
 */
@Serializable
public data class EnvironmentVariant(
    val id: String,
    val variable: String,
    val name: String,
    val binding: String,
    val owner: String? = null,
    val hasValue: Boolean,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Creates an environment variant; the variable and the binding never change afterwards.
 *
 * @property binding `shared` when null; a personal variant is always `shared`.
 * @property value The shared value (1 to 16,384 characters, without NUL), written once and never returned.
 */
@Serializable
public data class EnvironmentVariantCreate(
    val variable: String,
    val name: String,
    val binding: String? = null,
    val value: String? = null,
)

/**
 * Renames a variant or replaces its shared value.
 *
 * @property expectedVersion The [EnvironmentVariant.version] read; another one is 409 `variant_version_conflict`.
 * @property name A new name; null keeps it.
 * @property value A new shared value; null keeps the stored one.
 */
@Serializable
public data class EnvironmentVariantUpdate(
    val expectedVersion: Int,
    val name: String? = null,
    val value: String? = null,
)

/** Environment variants, by variable then name; always one page (at most 500). */
@Serializable
public data class EnvironmentVariantsPage(
    val items: List<EnvironmentVariant>,
)

/**
 * A member's environment variables with their values (`GET /api/v1/environment`): never logged, stored, or shown.
 *
 * @property variables The values, by name.
 * @property unavailable Assigned variables withheld because their value cannot be read now, by name only.
 */
@Serializable
public data class EnvironmentValues(
    val variables: Map<String, String>,
    val unavailable: List<String>,
)
