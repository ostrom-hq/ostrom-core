// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * A member's Slack link (`GET`, `POST /api/v1/slack/link`, `slack.v1`): the Slack account their notifications (channel
 * `slack`) and yes requests go to as direct messages.
 *
 * @property memberId The member.
 * @property configured Whether the instance's Slack app is configured (a bot token and a signing secret).
 * @property linked Whether the member linked a Slack account.
 * @property slackUserId The Slack user (`U…` or `W…`).
 * @property linkedAt ISO time the member linked it.
 */
@Serializable
public data class SlackLink(
    val memberId: String,
    val configured: Boolean,
    val linked: Boolean,
    val slackUserId: String? = null,
    val linkedAt: String? = null,
)

/** The notification channel of Slack direct messages, and the errors of its link. */
public object SlackChannels {
    /** The id of the Slack notification channel, in `NotificationSettings.channels`. */
    public const val SLACK: String = "slack"

    /** 409 of `linkSlack`: the `slack` settings section has no bot token. */
    public const val NOT_CONFIGURED: String = "slack_not_configured"

    /** 404 of `linkSlack`: no active Slack person has the caller's proven e-mail address. */
    public const val USER_NOT_FOUND: String = "slack_user_not_found"
}
