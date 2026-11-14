// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.EnvironmentValues
import run.ostrom.core.api.models.EnvironmentVariant
import run.ostrom.core.api.models.EnvironmentVariantCreate
import run.ostrom.core.api.models.EnvironmentVariantUpdate
import run.ostrom.core.api.models.EnvironmentVariantsPage

/**
 * The caller's environment variables (`environment.v1`): which names they receive, and their own personal variants.
 * Reached through [OstromClient.environment]. A value is written once and never read back.
 */
public class EnvironmentClient internal constructor(private val transport: Transport) {
    /**
     * The names of the caller's environment variables (`GET /api/v1/environment`), never their values: the answer's
     * values are dropped as soon as it is decoded and never returned, so a caller cannot show, cache or log them. The
     * route has no names-only form, so the values still travel in the answer (`Cache-Control: no-store`).
     */
    public suspend fun names(): EnvironmentNames {
        val values: EnvironmentValues = transport.json(HttpMethod.Get, "/api/v1/environment")
        return EnvironmentNames(names = values.variables.keys.sorted(), unavailable = values.unavailable.sorted())
    }

    /** The caller's personal variants, metadata only (`GET /api/v1/me/environment/variants`). */
    public suspend fun listMine(): EnvironmentVariantsPage =
        transport.json(HttpMethod.Get, "/api/v1/me/environment/variants")

    /**
     * Declares a personal value for [EnvironmentVariantCreate.variable] (`POST /api/v1/me/environment/variants`),
     * assigned to the caller alone. 409 `slot_taken`, `variant_excluded` or `variant_limit` when refused.
     */
    public suspend fun createMine(variant: EnvironmentVariantCreate): EnvironmentVariant =
        transport.jsonBody(HttpMethod.Post, "/api/v1/me/environment/variants", variant)

    /**
     * Renames one of the caller's personal variants or replaces its value
     * (`PATCH /api/v1/me/environment/variants/{id}`); 409 `variant_version_conflict` when
     * [EnvironmentVariantUpdate.expectedVersion] is not the stored one.
     */
    public suspend fun updateMine(id: String, update: EnvironmentVariantUpdate): EnvironmentVariant =
        transport.jsonBody(HttpMethod.Patch, "/api/v1/me/environment/variants/${segment(id)}", update)

    /** Deletes one of the caller's personal variants (`DELETE /api/v1/me/environment/variants/{id}`). */
    public suspend fun deleteMine(id: String) {
        transport.noContent(HttpMethod.Delete, "/api/v1/me/environment/variants/${segment(id)}")
    }
}

/**
 * The environment variables a member receives, as names only.
 *
 * @property names The variables served now, sorted.
 * @property unavailable Assigned variables withheld because their value cannot be read now, sorted.
 */
public data class EnvironmentNames(val names: List<String>, val unavailable: List<String>)
