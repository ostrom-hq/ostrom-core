// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A page of the organization's activity (`GET /api/v1/activity`).
 *
 * @property items The lines of the page.
 * @property next The cursor of the following page, absent on the last one.
 */
@Serializable
public data class ActivityPage(
    val items: List<ActivityItem>,
    val next: String? = null,
)

/**
 * A line of the activity feed.
 *
 * @property id Line id.
 * @property at ISO time.
 * @property kind `session.started`, `session.ended`, `gate.requested`, `gate.decided`, `check.passed`,
 *   `check.failed`, `workflow.changed`; an open list, so a client shows a kind it does not know as such.
 * @property actor Who did it.
 * @property summary A sentence for a person.
 * @property repo The repository.
 * @property costUsd The cost in US dollars.
 * @property approvalId The approval it is about.
 * @property gateId The gate it is about.
 * @property sessionId The session it is about.
 */
@Serializable
public data class ActivityItem(
    val id: String,
    val at: String,
    val kind: String,
    val actor: ActivityActor,
    val summary: String,
    val repo: String? = null,
    val costUsd: Double? = null,
    val approvalId: String? = null,
    val gateId: String? = null,
    val sessionId: String? = null,
)

/**
 * Who did an activity.
 *
 * @property kind What it is (a person, an agent…).
 * @property name Its name.
 */
@Serializable
public data class ActivityActor(
    val kind: String,
    val name: String,
)
