// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable
import run.ostrom.core.api.UnknownFallbackEnumSerializer

/** The state of a member's GitHub link (`github.link.v1`). */
@Serializable(with = GitHubLinkState.Serializer::class)
public enum class GitHubLinkState(
    /** The JSON value. */
    public val value: String,
) {
    NOT_LINKED("not_linked"),
    LINKED("linked"),

    /** GitHub refused the stored tokens, or the instance's App changed: the member links again. */
    RECONNECT_REQUIRED("reconnect_required"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<GitHubLinkState>(
        "run.ostrom.core.api.models.GitHubLinkState",
        GitHubLinkState.entries,
        GitHubLinkState.UNKNOWN,
        GitHubLinkState::value,
    )
}

/**
 * A member's GitHub link, never with a token (`GET /api/v1/github/link`).
 *
 * @property memberId The member.
 * @property state Whether the member linked an account and whether it is still usable.
 * @property appConfigured Whether the instance's GitHub App can link accounts.
 * @property login The linked GitHub account.
 * @property userId Its id.
 * @property linkedAt ISO time the member linked it.
 * @property refreshedAt ISO time its tokens were last renewed.
 * @property accessExpiresAt ISO time its access token expires.
 */
@Serializable
public data class GitHubLink(
    val memberId: String,
    val state: GitHubLinkState,
    val appConfigured: Boolean,
    val login: String? = null,
    val userId: Long? = null,
    val linkedAt: String? = null,
    val refreshedAt: String? = null,
    val accessExpiresAt: String? = null,
)

/**
 * The links of every member of the organization (`GET /api/v1/github/links`, owners and admins).
 *
 * @property items The links, by GitHub login.
 */
@Serializable
public data class GitHubLinkPage(val items: List<GitHubLink>)

/**
 * A device link just started (`POST /api/v1/github/link/device`): show [userCode], open [verificationUri], and poll
 * with [id] every [interval] seconds until [expiresAt].
 *
 * @property id The attempt to poll.
 * @property userCode The code to enter on GitHub.
 * @property verificationUri Where to enter it.
 * @property expiresAt ISO time the attempt ends.
 * @property interval Seconds between two polls.
 */
@Serializable
public data class GitHubDeviceLink(
    val id: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAt: String,
    val interval: Int,
)

/** The outcome of one poll of a device link. */
@Serializable(with = GitHubDeviceLinkStatus.Serializer::class)
public enum class GitHubDeviceLinkStatus(
    /** The JSON value. */
    public val value: String,
) {
    PENDING("pending"),
    LINKED("linked"),
    EXPIRED("expired"),
    DENIED("denied"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<GitHubDeviceLinkStatus>(
        "run.ostrom.core.api.models.GitHubDeviceLinkStatus",
        GitHubDeviceLinkStatus.entries,
        GitHubDeviceLinkStatus.UNKNOWN,
        GitHubDeviceLinkStatus::value,
    )
}

/**
 * One poll of a device link (`POST /api/v1/github/link/device/{id}`).
 *
 * @property status Where the attempt is.
 * @property interval Seconds before the next poll, while pending.
 * @property link The link, once linked.
 */
@Serializable
public data class GitHubDeviceLinkPoll(
    val status: GitHubDeviceLinkStatus,
    val interval: Int? = null,
    val link: GitHubLink? = null,
)

/** The body of `PUT /api/v1/cloud/sessions/{id}/github`: `owner`, `shared` or `none`. */
@Serializable
public data class SetCloudGitHubAccessRequest(val access: String)
