// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.ServerFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitiesTest {

    private fun capabilities(apiVersion: Int, vararg features: String) =
        Capabilities(apiVersion = apiVersion, serverVersion = "1.0.0", features = features.toList())

    @Test
    fun aServerSpeakingThisApiVersionIsSupported() {
        assertTrue(capabilities(ServerFeatures.API_VERSION).isSupported)
    }

    @Test
    fun aServerSpeakingAnotherApiVersionIsNotSupported() {
        assertFalse(capabilities(ServerFeatures.API_VERSION + 1).isSupported)
        assertFalse(capabilities(0).isSupported)
    }

    @Test
    fun aFeatureIsServedOnlyWhenItsKeyIsListed() {
        val served = capabilities(1, ServerFeatures.SESSIONS, "future.v9")
        assertTrue(ServerFeatures.SESSIONS in served)
        assertFalse(ServerFeatures.BILLING in served)
    }

    @Test
    fun aServerAnswerWithUnknownFieldsAndKeysIsRead() {
        val text = """{"apiVersion":1,"serverVersion":"1.2.0","features":["gates.v1","later.v2"],"mode":"x"}"""
        val read = OstromJson.decodeFromString(Capabilities.serializer(), text)
        assertEquals(listOf("gates.v1", "later.v2"), read.features)
        assertTrue(read.isSupported)
    }

    @Test
    fun everyKeyIsDistinctAndVersioned() {
        assertEquals(ServerFeatures.ALL.size, ServerFeatures.ALL.toSet().size)
        assertTrue(ServerFeatures.ALL.all { Regex("""[a-z]+(\.[a-z]+)*\.v[0-9]+""").matches(it) })
    }
}
