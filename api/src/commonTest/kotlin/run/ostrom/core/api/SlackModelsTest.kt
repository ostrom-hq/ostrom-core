// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.SlackChannels
import run.ostrom.core.api.models.SlackLink
import kotlin.test.Test
import kotlin.test.assertEquals

class SlackModelsTest {
    @Test
    fun aLinkWithoutAnAccountOmitsItsOptionalFields() {
        val link = SlackLink(memberId = "m1", configured = true, linked = false)
        val text = Json.encodeToString(SlackLink.serializer(), link)
        assertEquals("""{"memberId":"m1","configured":true,"linked":false}""", text)
        assertEquals(link, Json.decodeFromString(SlackLink.serializer(), text))
    }

    @Test
    fun theChannelAndErrorKeysAreTheContracts() {
        val keys = listOf(
            SlackChannels.SLACK,
            SlackChannels.NOT_CONFIGURED,
            SlackChannels.EMAIL_UNPROVEN,
            SlackChannels.USER_NOT_FOUND,
            SlackChannels.USER_TAKEN,
        )
        val expected = listOf(
            "slack", "slack_not_configured", "slack_email_unproven", "slack_user_not_found", "slack_user_taken",
        )
        assertEquals(expected, keys)
    }
}
