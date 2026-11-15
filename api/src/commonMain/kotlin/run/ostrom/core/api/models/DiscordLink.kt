// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A member's Discord link (`GET /api/v1/discord/link`, `discord.v1`): the Discord account their notifications (channel
 * `discord`) and yes requests go to as direct messages.
 *
 * @property memberId The member.
 * @property configured Whether the instance's Discord application is configured (bot token, application id, public
 *   key, client secret and server id).
 * @property linked Whether the member linked a Discord account.
 * @property discordUserId The Discord user (a snowflake).
 * @property discordUsername The account's Discord username when it was linked.
 * @property linkedAt ISO time the member linked it.
 */
@Serializable
public data class DiscordLink(
    val memberId: String,
    val configured: Boolean,
    val linked: Boolean,
    val discordUserId: String? = null,
    val discordUsername: String? = null,
    val linkedAt: String? = null,
)

/**
 * The start of a Discord link (`POST /api/v1/discord/link`): Discord's authorization URL (OAuth2, scope `identify`),
 * to open in the browser that asked for it; Discord returns to `GET /api/v1/discord/link/callback`.
 *
 * @property authorizeUrl Discord's authorization URL, with a single-use `state` valid 10 minutes.
 */
@Serializable
public data class DiscordLinkStart(
    val authorizeUrl: String,
)

/** The notification channel of Discord direct messages, and the errors of its link. */
public object DiscordChannels {
    /** The id of the Discord notification channel, in `NotificationSettings.channels`. */
    public const val DISCORD: String = "discord"

    /** The console page the link's callback returns to, with `discord=linked` or `discord=<error>`. */
    public const val RETURN_PAGE: String = "/me/notifications"

    /** The query parameter of [RETURN_PAGE] that carries the outcome. */
    public const val RETURN_PARAMETER: String = "discord"

    /** The value of [RETURN_PARAMETER] once the account is linked. */
    public const val LINKED: String = "linked"

    /** 409 of `linkDiscord`, or the callback's outcome: the `discord` settings section is incomplete. */
    public const val NOT_CONFIGURED: String = "discord_not_configured"

    /** The callback's outcome: another member of the organization linked that Discord account. */
    public const val USER_TAKEN: String = "discord_user_taken"

    /** The callback's outcome: the account is not in the instance's Discord server, so the bot cannot DM it. */
    public const val NOT_IN_GUILD: String = "discord_not_in_guild"

    /** The callback's outcome: no attempt has that state (unknown, used or more than 10 minutes old). */
    public const val LINK_NOT_FOUND: String = "discord_link_not_found"

    /** The callback's outcome: the state came back in another browser session than the one that started it. */
    public const val LINK_BROWSER_MISMATCH: String = "discord_link_browser_mismatch"

    /** The callback's outcome: the person declined on Discord's page, or Discord refused the code. */
    public const val LINK_DENIED: String = "discord_link_denied"

    /** The callback's outcome: Discord did not answer. */
    public const val UNREACHABLE: String = "discord_unreachable"
}
