// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * The caller (`GET /api/v1/auth/me`).
 *
 * @property dev The person behind the token.
 * @property member Their membership of the organization the call acts in; absent outside one.
 * @property organization The organization the call acts in.
 * @property capabilities What the caller may do there.
 * @property csrfToken For a browser session, the token to send in `X-CSRF-Token` on state-changing requests.
 * @property instanceAdmin True when the caller is a person who administers the instance; absent from older servers.
 */
@Serializable
public data class Me(
    val dev: Dev,
    val member: Member? = null,
    val organization: Organization? = null,
    val capabilities: List<String>,
    val csrfToken: String? = null,
    val instanceAdmin: Boolean? = null,
)

/**
 * The signed-in person's account (`GET /api/v1/auth/me/account`).
 *
 * @property id Account id.
 * @property login The handle, unique on the instance.
 * @property displayName Display name.
 * @property primaryEmail The main e-mail; absent when there is none.
 * @property avatarUrl The picture; absent when there is none.
 * @property createdAt ISO creation time.
 * @property emails Every e-mail of the account.
 * @property providers How the person signs in: `github`, `google`, `email` or `oidc:<connection>`.
 */
@Serializable
public data class Account(
    val id: String,
    val login: String,
    val displayName: String,
    val primaryEmail: String? = null,
    val avatarUrl: String? = null,
    val createdAt: String,
    val emails: List<AccountEmail>,
    val providers: List<String>,
)

/**
 * One e-mail of an account.
 *
 * @property email The address.
 * @property verified True once the person proved it.
 */
@Serializable
public data class AccountEmail(
    val email: String,
    val verified: Boolean,
)

/**
 * One of the caller's organizations, to choose the one `Ostrom-Org` names.
 *
 * @property id Organization id, the value of `Ostrom-Org`.
 * @property slug Its slug.
 * @property name Its name.
 * @property avatarUrl The picture of the linked GitHub organization, empty when none.
 * @property role The caller's role there.
 */
@Serializable
public data class MyOrganization(
    val id: String,
    val slug: String,
    val name: String,
    val avatarUrl: String,
    val role: MemberRole,
)

/**
 * The organizations the caller is an active member of (`GET /api/v1/me/organizations`).
 *
 * @property items The organizations.
 */
@Serializable
public data class MyOrganizationList(
    val items: List<MyOrganization>,
)
