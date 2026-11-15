// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.DiscordChannels
import run.ostrom.core.api.models.DiscordLink
import run.ostrom.core.api.models.DiscordLinkStart
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordModelsTest {
    @Test
    fun aLinkWithoutAnAccountOmitsItsOptionalFields() {
        val link = DiscordLink(memberId = "m1", configured = true, linked = false)
        val text = Json.encodeToString(DiscordLink.serializer(), link)
        assertEquals("""{"memberId":"m1","configured":true,"linked":false}""", text)
        assertEquals(link, Json.decodeFromString(DiscordLink.serializer(), text))
    }

    @Test
    fun aStartCarriesTheAuthorizeUrl() {
        val start = DiscordLinkStart("https://discord.com/oauth2/authorize?state=s")
        val text = Json.encodeToString(DiscordLinkStart.serializer(), start)
        assertEquals("""{"authorizeUrl":"https://discord.com/oauth2/authorize?state=s"}""", text)
        assertEquals(start, Json.decodeFromString(DiscordLinkStart.serializer(), text))
    }

    @Test
    fun aLinkedAccountKeepsItsFields() {
        val link = DiscordLink("m1", true, true, "80351110224678912", "nelly", "2026-11-15T10:00:00Z")
        val text = Json.encodeToString(DiscordLink.serializer(), link)
        val expected = """{"memberId":"m1","configured":true,"linked":true,"discordUserId":"80351110224678912",""" +
            """"discordUsername":"nelly","linkedAt":"2026-11-15T10:00:00Z"}"""
        assertEquals(expected, text)
        assertEquals(link, Json.decodeFromString(DiscordLink.serializer(), text))
    }

    @Test
    fun theChannelAndErrorKeysAreTheContracts() {
        val keys = listOf(
            DiscordChannels.DISCORD,
            DiscordChannels.RETURN_PAGE,
            DiscordChannels.RETURN_PARAMETER,
            DiscordChannels.LINKED,
            DiscordChannels.NOT_CONFIGURED,
            DiscordChannels.USER_TAKEN,
            DiscordChannels.NOT_IN_GUILD,
            DiscordChannels.LINK_NOT_FOUND,
            DiscordChannels.LINK_BROWSER_MISMATCH,
            DiscordChannels.LINK_DENIED,
            DiscordChannels.UNREACHABLE,
        )
        val expected = listOf(
            "discord", "/me/notifications", "discord", "linked", "discord_not_configured", "discord_user_taken",
            "discord_not_in_guild", "discord_link_not_found", "discord_link_browser_mismatch", "discord_link_denied",
            "discord_unreachable",
        )
        assertEquals(expected, keys)
    }
}
