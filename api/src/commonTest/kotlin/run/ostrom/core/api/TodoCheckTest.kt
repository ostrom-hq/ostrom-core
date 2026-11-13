// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoItemStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TodoCheckTest {

    private fun write(check: TodoCheck): String = OstromJson.encodeToString(TodoCheck.Serializer, check)

    private fun read(text: String): TodoCheck = OstromJson.decodeFromString(TodoCheck.Serializer, text)

    @Test
    fun everyCheckIsWrittenWithItsTypeFirstAndReadBack() {
        val checks = mapOf(
            """{"type":"pr_merged","repo":"o/r"}""" to TodoCheck.PrMerged("o/r"),
            """{"type":"pr_closed","repo":"o/r","reason":"superseded"}""" to TodoCheck.PrClosed("o/r", "superseded"),
            """{"type":"release_contains","repo":"o/r","of":"fix"}""" to TodoCheck.ReleaseContains("o/r", of = "fix"),
            """{"type":"ci_green","repo":"o/r","ref":"main","workflow":"Build"}""" to
                TodoCheck.CiGreen("o/r", ref = "main", workflow = "Build"),
            """{"type":"command","run":"make test"}""" to TodoCheck.Command("make test"),
            """{"type":"command","run":"x","cwd":"/w","expectExit":1,"timeoutSeconds":5}""" to
                TodoCheck.Command("x", cwd = "/w", expectExit = 1, timeoutSeconds = 5),
            """{"type":"artifact_published","id":"a1","minVersion":2}""" to TodoCheck.ArtifactPublished("a1", 2),
            """{"type":"wait_answered","action":"yes"}""" to TodoCheck.WaitAnswered("yes"),
            """{"type":"attested","what":"the demo ran"}""" to TodoCheck.Attested("the demo ran"),
        )
        for ((text, check) in checks) {
            assertEquals(text, write(check))
            assertEquals(check, read(text))
        }
    }

    @Test
    fun aCommandTimesOutAfterAMinuteByDefault() {
        assertEquals(TodoCheck.Command.DEFAULT_TIMEOUT_SECONDS, TodoCheck.Command("make").timeoutSeconds)
        assertEquals(60, TodoCheck.Command.DEFAULT_TIMEOUT_SECONDS)
    }

    @Test
    fun aCheckOfATypeThisVersionDoesNotKnowIsKeptWithItsFieldsAndNeverWritten() {
        val check = read("""{"type":"deploy_healthy","url":"https://x"}""")
        val fields = JsonObject(mapOf("type" to JsonPrimitive("deploy_healthy"), "url" to JsonPrimitive("https://x")))
        assertEquals(TodoCheck.Unknown("deploy_healthy", fields), check)
        assertFailsWith<SerializationException> { write(check) }
    }

    @Test
    fun aCheckWithoutAStringTypeOrNotAnObjectIsRefused() {
        assertFailsWith<SerializationException> { read("""{"repo":"o/r"}""") }
        assertFailsWith<SerializationException> { read("""{"type":3,"repo":"o/r"}""") }
        assertFailsWith<SerializationException> { read("""{"type":{},"repo":"o/r"}""") }
        assertFailsWith<SerializationException> { read("""["pr_merged"]""") }
        assertFailsWith<SerializationException> { read("""{"type":"pr_merged"}""") }
    }

    @Test
    fun aListWithANewCheckTypeAndANewStatusStillDecodes() {
        val text = """{"todo":{"id":"l1","sessionId":"s1","reference":"plan","status":"active","items":[""" +
            """{"id":"ship","name":"Ship","description":"d","doneWhen":{"text":"merged","checks":[""" +
            """{"type":"pr_merged","repo":"o/r"},{"type":"later","x":1}]},"origin":"plan","status":"paused"}],""" +
            """"createdAt":"2026-11-13T00:00:00Z","updatedAt":"2026-11-13T00:00:00Z","nextItemId":"ship"}}"""
        val todo = OstromJson.decodeFromString(SessionTodo.serializer(), text).todo ?: error("no todo")
        val item = todo.items.single()
        assertEquals(TodoItemStatus.UNKNOWN, item.status)
        assertEquals(TodoCheck.PrMerged("o/r"), item.doneWhen.checks[0])
        assertEquals("later", (item.doneWhen.checks[1] as TodoCheck.Unknown).type)
        val written = OstromJson.encodeToJsonElement(SessionTodo.serializer(), SessionTodo(null)).jsonObject
        assertEquals(JsonObject(mapOf("todo" to JsonNull)), written)
    }
}
