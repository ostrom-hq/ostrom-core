// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.MyOrganization
import run.ostrom.core.api.models.MyOrganizationList

/** The caller's organizations, to choose the one `Ostrom-Org` names. Reached through [OstromClient.organizations]. */
public class OrganizationsClient internal constructor(private val transport: Transport) {
    /** The organizations the caller is an active member of (`GET /api/v1/me/organizations`); needs no `Ostrom-Org`. */
    public suspend fun mine(): List<MyOrganization> =
        transport.json<MyOrganizationList>(HttpMethod.Get, "/api/v1/me/organizations").items
}
