// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * Spending over a week or a month (`GET /api/v1/spending`).
 *
 * @property period The period read.
 * @property from ISO start of the period.
 * @property to ISO end of the period.
 * @property totalUsd Total in US dollars.
 * @property budgetUsd The budget of the period.
 * @property byAgent By agent.
 * @property byRepo By repository.
 * @property byPerson By person.
 * @property byWorkflow By workflow (planned on the server).
 * @property daily Day by day.
 * @property exceeded The budgets the reader sees spent past their limit today or this month; empty when none.
 */
@Serializable
public data class Spending(
    val period: String,
    val from: String,
    val to: String,
    val totalUsd: Double,
    val budgetUsd: Double? = null,
    val byAgent: List<SpendingLine>,
    val byRepo: List<SpendingLine>,
    val byPerson: List<SpendingLine>,
    val byWorkflow: List<SpendingLine> = emptyList(),
    val daily: List<SpendingDay>,
    val exceeded: List<ExceededBudget> = emptyList(),
)

/**
 * Spending of one agent, repository, person or workflow.
 *
 * @property key What it is (its id).
 * @property label Its name for a person.
 * @property usd US dollars.
 * @property sessions Sessions counted.
 */
@Serializable
public data class SpendingLine(
    val key: String,
    val label: String,
    val usd: Double,
    val sessions: Int,
)

/**
 * Spending of one day.
 *
 * @property date The day, `YYYY-MM-DD`.
 * @property usd US dollars.
 */
@Serializable
public data class SpendingDay(
    val date: String,
    val usd: Double,
)

/**
 * A budget spent past its limit in its current day or month.
 *
 * @property scope `organization`, `member`, or `workflow` (planned).
 * @property subject For `member`, the member's id; for `workflow`, the workflow's id.
 * @property label For `member`, the member's login (planned).
 * @property period `day` or `month`.
 * @property usd The limit in US dollars.
 * @property hardStop Whether it stops the calls; false, it only warns.
 * @property since When the server saw it reached.
 */
@Serializable
public data class ExceededBudget(
    val scope: String,
    val subject: String? = null,
    val label: String? = null,
    val period: String,
    val usd: Double,
    val hardStop: Boolean,
    val since: String,
)
