// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.Spending

/** What the organization's agents cost. Reached through [OstromClient.spending]. */
public class SpendingClient internal constructor(private val transport: Transport) {
    /** Spending over [period] (`GET /api/v1/spending?period=`), the server's default period when null. */
    public suspend fun report(period: String? = null): Spending =
        transport.json(HttpMethod.Get, "/api/v1/spending", query("period" to period))
}
