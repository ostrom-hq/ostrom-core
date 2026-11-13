// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A page of artifacts (`GET /api/v1/artifacts`).
 *
 * @property items The artifacts of the page.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class ArtifactPage(
    val items: List<Artifact>,
    val next: String? = null,
)

/**
 * A published page or file set, behind the login.
 *
 * @property id Artifact id.
 * @property title Title.
 * @property owner Who published it.
 * @property access `personal`, `team` or `link`.
 * @property relation What the caller is to it: `owner`, `viewer` or `team`; only the owner changes it.
 * @property latestVersion The newest version's number.
 * @property url The console's page for it.
 * @property publicUrl Its public link, for `link` access.
 * @property createdAt ISO creation time.
 * @property updatedAt ISO time of the last change.
 */
@Serializable
public data class Artifact(
    val id: String,
    val title: String,
    val owner: String,
    val access: String,
    val relation: String? = null,
    val latestVersion: Int,
    val url: String,
    val publicUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * A page of the versions of an artifact (`GET /api/v1/artifacts/{id}/versions`).
 *
 * @property items The versions of the page.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class ArtifactVersionPage(
    val items: List<ArtifactVersion>,
    val next: String? = null,
)

/**
 * A version of an artifact.
 *
 * @property number Its number.
 * @property files The paths of its files.
 * @property entry The page a reader opens first.
 * @property createdAt ISO publication time.
 * @property createdBy Who published it.
 * @property digest The SHA-256 of its canonical manifest, when known.
 * @property baseVersion The version its publisher started from, when known.
 * @property replayed True when a commit was the same as one made within 10 minutes, answered with that version.
 */
@Serializable
public data class ArtifactVersion(
    val number: Int,
    val files: List<String>,
    val entry: String,
    val createdAt: String,
    val createdBy: String,
    val digest: String? = null,
    val baseVersion: Int? = null,
    val replayed: Boolean? = null,
)

/**
 * The refusal of a publication (TeamCode's `{error, current}`): an [ApiError] with, for `artifact_conflict`, the live
 * version, for the publisher to merge.
 *
 * @property error What was refused.
 * @property current The live version, for `artifact_conflict`.
 */
@Serializable
public data class ArtifactConflict(
    val error: ErrorDetail,
    val current: ArtifactVersion? = null,
)

/**
 * Which version a ticket opens (`POST /api/v1/artifacts/{id}/tickets`).
 *
 * @property version The version; the latest when absent.
 */
@Serializable
public data class ArtifactTicketRequest(
    val version: Int? = null,
)

/**
 * A reading ticket: a short-lived address of the artifact on the content origin, for a web view.
 *
 * @property artifactId The artifact.
 * @property version The version it opens.
 * @property url The entry page's address on the content origin.
 * @property contentUrl The same address, under TeamCode's name.
 * @property expiresAt When the ticket stops working.
 */
@Serializable
public data class ArtifactTicket(
    val artifactId: String,
    val version: Int,
    val url: String,
    val contentUrl: String,
    val expiresAt: String,
)
