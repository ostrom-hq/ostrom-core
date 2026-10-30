package dev.ostrom.core.api.models

import dev.ostrom.core.api.UnknownFallbackEnumSerializer
import kotlinx.serialization.Serializable

/**
 * An organization: a GitHub organization connected to Ostrom.
 *
 * @property id Organization id (GitHub login of the organization).
 * @property name Display name.
 * @property avatarUrl Logo.
 * @property description Description.
 * @property connectedVia How GitHub is connected.
 * @property createdAt ISO creation time.
 */
@Serializable
public data class Organization(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val description: String? = null,
    val connectedVia: GitHubConnection,
    val createdAt: String? = null,
)

/** How an organization's GitHub is connected. */
@Serializable(with = GitHubConnection.Serializer::class)
public enum class GitHubConnection(
    /** The JSON value. */
    public val value: String,
) {
    GITHUB_APP("github-app"),
    GH_TOKEN("gh-token"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<GitHubConnection>(
        "dev.ostrom.core.api.models.GitHubConnection",
        GitHubConnection.entries,
        GitHubConnection.UNKNOWN,
        GitHubConnection::value,
    )
}

/**
 * A member of an organization.
 *
 * @property login GitHub login: the identity every receipt names.
 * @property name Display name.
 * @property avatarUrl Avatar.
 * @property email E-mail.
 * @property teams Slugs of the member's teams.
 * @property role Role in Ostrom.
 * @property slackUserId Slack user, once Slack is linked.
 * @property discordUserId Discord user, once Discord is linked.
 */
@Serializable
public data class Member(
    val login: String,
    val name: String? = null,
    val avatarUrl: String,
    val email: String? = null,
    val teams: List<String> = emptyList(),
    val role: MemberRole,
    val slackUserId: String? = null,
    val discordUserId: String? = null,
)

/** Roles of a member. An admin never answers a gate in an approver's place. */
@Serializable(with = MemberRole.Serializer::class)
public enum class MemberRole(
    /** The JSON value. */
    public val value: String,
) {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    APPROVER("approver"),
    INVITED("invited"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<MemberRole>(
        "dev.ostrom.core.api.models.MemberRole",
        MemberRole.entries,
        MemberRole.UNKNOWN,
        MemberRole::value,
    )
}

/**
 * A team.
 *
 * @property slug Team slug.
 * @property name Name.
 * @property members Logins.
 * @property repos Repositories the team owns.
 */
@Serializable
public data class Team(
    val slug: String,
    val name: String,
    val members: List<String> = emptyList(),
    val repos: List<String> = emptyList(),
)

/**
 * A developer as seen by the server: the person behind a token, on a workstation.
 *
 * @property login GitHub login.
 * @property verified True when the login was proven by GitHub (OAuth or device flow), not just declared.
 * @property via How the identity was established (`github-oauth`, `device`, `gh-token`…).
 * @property organizationId Organization the token belongs to.
 */
@Serializable
public data class Dev(
    val login: String,
    val verified: Boolean,
    val via: String,
    val organizationId: String? = null,
)

/**
 * A rule of the organization, learned by the analysis or written by the team.
 *
 * @property id Rule id.
 * @property statement The rule.
 * @property output Kind of output (`regle`, `metier`, `workflow`, `porte`, `decideur`, `commande`, `carte`).
 * @property group Display group.
 * @property scope Repositories or paths.
 * @property sources Where it was found.
 * @property why Why it exists.
 * @property strength Required or recommended.
 * @property confidence 0 to 1.
 * @property status Proposed, accepted, edited or rejected.
 * @property foundBy Agent that found it.
 * @property foundAt ISO time.
 * @property reviewBy ISO date to review it before.
 * @property decidedBy Who decided it.
 * @property decidedAt When.
 * @property origin `learned` (default) or `written` (imported as written by the team).
 */
@Serializable
public data class Rule(
    val id: String,
    val statement: String,
    val output: String,
    val group: String,
    val scope: List<String> = emptyList(),
    val sources: List<RuleSource> = emptyList(),
    val why: String? = null,
    val strength: RuleStrength,
    val confidence: Double,
    val status: RuleStatus,
    val foundBy: String,
    val foundAt: String,
    val reviewBy: String? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val origin: String? = null,
)

/** Strength of a rule. */
@Serializable(with = RuleStrength.Serializer::class)
public enum class RuleStrength(
    /** The JSON value. */
    public val value: String,
) {
    REQUIRED("required"),
    RECOMMENDED("recommended"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<RuleStrength>(
        "dev.ostrom.core.api.models.RuleStrength",
        RuleStrength.entries,
        RuleStrength.UNKNOWN,
        RuleStrength::value,
    )
}

/** Status of a rule. */
@Serializable(with = RuleStatus.Serializer::class)
public enum class RuleStatus(
    /** The JSON value. */
    public val value: String,
) {
    PROPOSED("proposed"),
    ACCEPTED("accepted"),
    EDITED("edited"),
    REJECTED("rejected"),

    /** A value this version does not know, sent by a newer server; never sent. */
    UNKNOWN(""),
    ;

    /** Reads a value this version does not know as [UNKNOWN]. */
    public object Serializer : UnknownFallbackEnumSerializer<RuleStatus>(
        "dev.ostrom.core.api.models.RuleStatus",
        RuleStatus.entries,
        RuleStatus.UNKNOWN,
        RuleStatus::value,
    )
}
