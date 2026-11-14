// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventTypes
import run.ostrom.core.api.models.CloudFeeds
import run.ostrom.core.api.models.CloudInputStatuses
import run.ostrom.core.api.models.CloudTasks
import run.ostrom.core.api.models.CloudTranscriptLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The constants a server, a runner and a client of a conversation session share. */
class CloudConversationModelsTest {

    @Test
    fun theEventTypesAndInputStatusesAreDistinctAndInOrder() {
        assertEquals(CloudEventTypes.all.size, CloudEventTypes.all.toSet().size)
        assertTrue(CloudEventTypes.PERMISSION_REQUEST in CloudEventTypes.all)
        assertEquals(listOf("queued", "delivered", "consumed", "cancelled", "rejected"), CloudInputStatuses.all)
    }

    @Test
    fun feedsAndTaskIdsAreBoundedAndReasoningKeysDropped() {
        assertTrue(CloudFeeds.FEED.matches("toolu_01:a.b-c"))
        assertFalse(CloudFeeds.FEED.matches("a".repeat(129)))
        assertFalse(CloudFeeds.FEED.matches("a b"))
        assertTrue(CloudTasks.TASK_ID.matches("b7x_1-z"))
        assertFalse(CloudTasks.TASK_ID.matches("b.1"))
        assertEquals(
            setOf("thinking", "signature", "reasoning", "redacted_thinking"),
            CloudTranscriptLimits.DROPPED_KEYS,
        )
    }

    @Test
    fun anEventOfTheMainAgentHasNoFeedAndKeepsUnknownData() {
        val event = OstromJson.decodeFromString(
            CloudEvent.serializer(),
            """{"seq":1,"type":"later.type","at":"t","epoch":0,"data":{"x":1},"extra":true}""",
        )
        assertEquals(null, event.feed)
        assertEquals(JsonObject(mapOf("x" to JsonPrimitive(1))), event.data)
    }
}
