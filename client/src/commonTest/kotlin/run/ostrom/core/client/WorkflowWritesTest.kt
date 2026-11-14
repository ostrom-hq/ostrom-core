// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The write routes of the workflows client: the method, path and body of each call, and the refusals it surfaces. */
class WorkflowWritesTest {

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}"

    private val workflowJson = """{"id":"wf/1","name":"Release","domain":"eng","nodes":[],"edges":[],""" +
        """"status":"accepted","foundAt":"t"}"""
    private val workflowModel = Workflow(
        id = "wf/1",
        name = "Release",
        domain = "eng",
        nodes = emptyList(),
        edges = emptyList(),
        status = WorkflowStatus.ACCEPTED,
        foundAt = "t",
    )
    private val runJson = """{"id":"r1","workflowId":"wf/1","workflowName":"Release","workflowVersion":3,""" +
        """"status":"queued","startedBy":"ada","source":"manual","startedAt":"t"}"""
    private val pendingJson = """{"confirmationId":"c1","confirmUrl":"https://ostrom.example/confirm/c1",""" +
        """"expiresAt":"2026-11-14T00:15:00Z"}"""

    @Test
    fun aWorkflowIsCreatedSavedAndDeletedAndTheBodiesAreSent() = runTest {
        val server = ScriptedServer { request ->
            when (request.method) {
                HttpMethod.Post -> json(workflowJson, HttpStatusCode.Created)
                HttpMethod.Put -> json(workflowJson)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        server.client().use { client ->
            val created = assertIs<GovernedWrite.Applied<Workflow>>(client.workflows.create(workflowModel))
            assertEquals("Release", created.value.name)
            assertEquals(workflowModel, created.value)
            val saved = assertIs<GovernedWrite.Applied<Workflow>>(client.workflows.save("wf/1", workflowModel, 3))
            assertEquals(WorkflowStatus.ACCEPTED, saved.value.status)
            client.workflows.save("wf/1", workflowModel, null)
            client.workflows.save("wf/1", workflowModel.copy(version = 7))
            assertFailsWith<IllegalArgumentException> { client.workflows.save("other", workflowModel) }
            assertEquals(GovernedWrite.Applied(Unit), client.workflows.delete("wf/1"))
        }
        assertEquals(
            listOf(
                "POST /api/v1/workflows",
                "PUT /api/v1/workflows/wf%2F1",
                "PUT /api/v1/workflows/wf%2F1",
                "PUT /api/v1/workflows/wf%2F1",
                "DELETE /api/v1/workflows/wf%2F1",
            ),
            server.requests.map { it.call() },
        )
        assertJson(workflowJson, server.requests[0].bodyText())
        assertJson("""{"workflow":$workflowJson,"expectedVersion":3}""", server.requests[1].bodyText())
        assertJson("""{"workflow":$workflowJson}""", server.requests[2].bodyText())
        val versioned = workflowJson.dropLast(1) + ""","version":7}"""
        assertJson("""{"workflow":$versioned,"expectedVersion":7}""", server.requests[3].bodyText())
        assertEquals("", server.requests[4].bodyText())
    }

    @Test
    fun aGovernanceWriteMadeWithAnApiTokenIsHeldForConfirmation() = runTest {
        val server = ScriptedServer { json(pendingJson, HttpStatusCode.Accepted) }
        server.client().use { client ->
            val held = listOf(
                client.workflows.create(workflowModel),
                client.workflows.save("wf/1", workflowModel),
                client.workflows.delete("wf/1"),
            )
            for (write in held) {
                val pending = assertIs<GovernedWrite.Held>(write).confirmation
                assertEquals("c1", pending.confirmationId)
                assertEquals("https://ostrom.example/confirm/c1", pending.confirmUrl)
                assertEquals("2026-11-14T00:15:00Z", pending.expiresAt)
            }
        }
    }

    @Test
    fun aRefusedWorkflowWriteThrowsTheServersErrorKey() = runTest {
        val server = ScriptedServer { request ->
            val (status, key) = when (request.method) {
                HttpMethod.Post -> HttpStatusCode.TooManyRequests to "confirmation_limit"
                HttpMethod.Put -> HttpStatusCode.Conflict to "workflow_conflict"
                else -> HttpStatusCode.Conflict to "workflow_in_use"
            }
            json("""{"error":{"type":"$key","message":"No."}}""", status)
        }
        server.client().use { client ->
            val limited = assertFailsWith<OstromException> { client.workflows.create(workflowModel) }
            assertEquals(429, limited.status)
            assertEquals("confirmation_limit", limited.type)
            val stale = assertFailsWith<OstromException> { client.workflows.save("wf/1", workflowModel, 1) }
            assertEquals(409, stale.status)
            assertEquals("workflow_conflict", stale.type)
            val inUse = assertFailsWith<OstromException> { client.workflows.delete("wf/1") }
            assertEquals(409, inUse.status)
            assertEquals("workflow_in_use", inUse.type)
            assertFailsWith<IllegalArgumentException> { client.workflows.delete("") }
        }
    }

    @Test
    fun aRunIsStartedWithItsInputsAndCancelled() = runTest {
        val server = ScriptedServer { request ->
            if (request.url.encodedPath.endsWith("/cancel")) {
                json(runJson.replace("queued", "cancelled"))
            } else {
                json(runJson, HttpStatusCode.Created)
            }
        }
        server.client().use { client ->
            assertEquals(RunStatus.QUEUED, client.workflows.start("wf/1", mapOf("ticket" to "OS-1")).status)
            client.workflows.start("wf/1")
            assertEquals(RunStatus.CANCELLED, client.workflows.cancel("r/1").status)
        }
        assertEquals(
            listOf(
                "POST /api/v1/workflows/wf%2F1/runs",
                "POST /api/v1/workflows/wf%2F1/runs",
                "POST /api/v1/runs/r%2F1/cancel",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"inputs":{"ticket":"OS-1"}}""", server.requests[0].bodyText())
        assertJson("{}", server.requests[1].bodyText())
        assertEquals("", server.requests[2].bodyText())
    }

    @Test
    fun aStartCarriesItsIdempotencyKeyAndAReplayReturnsTheFirstRun() = runTest {
        val server = ScriptedServer { json(runJson, HttpStatusCode.OK, listOf(Idempotency.REPLAYED_HEADER to "true")) }
        server.client().use { client ->
            val key = Idempotency.newKey()
            assertEquals(RunStatus.QUEUED, client.workflows.start("wf/1", idempotencyKey = key).status)
            assertEquals(key, server.requests.single().headers[Idempotency.KEY_HEADER])
            assertFailsWith<IllegalArgumentException> { client.workflows.start("wf/1", idempotencyKey = "no spaces") }
        }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRefusedStartOrCancelThrowsTheServersErrorKey() = runTest {
        val server = ScriptedServer { request ->
            if (request.url.encodedPath.endsWith("/cancel")) {
                val body = """{"error":{"type":"conflict","message":"run_ended: the run already ended."}}"""
                json(body, HttpStatusCode.Conflict)
            } else {
                json("""{"error":{"type":"run_limit","message":"run_limit: wait."}}""", HttpStatusCode.Conflict)
            }
        }
        server.client().use { client ->
            val limited = assertFailsWith<OstromException> { client.workflows.start("wf/1") }
            assertEquals(409, limited.status)
            assertEquals("run_limit", limited.type)
            val ended = assertFailsWith<OstromException> { client.workflows.cancel("r1") }
            assertEquals(409, ended.status)
            assertEquals("conflict", ended.type)
            assertEquals(true, ended.message?.startsWith("run_ended"))
        }
    }

    @Test
    fun whoMayDecideWorkflowsIsReadAndChanged() = runTest {
        val answer = """{"editors":{"mode":"members","memberIds":["m1"]},"canEdit":true}"""
        val server = ScriptedServer { request ->
            if (request.method == HttpMethod.Put && request.bodyText().contains("nobody")) {
                json("""{"error":{"type":"invalid_editors","message":"Not a member."}}""", HttpStatusCode.BadRequest)
            } else {
                json(answer)
            }
        }
        server.client().use { client ->
            val read = client.workflowSettings.get()
            assertEquals(listOf("m1"), read.editors.memberIds)
            assertEquals(true, read.canEdit)
            client.workflowSettings.save(WorkflowEditors("members", listOf("m1")))
            client.workflowSettings.save(WorkflowEditors("admins"))
            val refused = assertFailsWith<OstromException> {
                client.workflowSettings.save(WorkflowEditors("members", listOf("nobody")))
            }
            assertEquals("invalid_editors", refused.type)
        }
        assertEquals(
            listOf(
                "GET /api/v1/workflows/settings",
                "PUT /api/v1/workflows/settings",
                "PUT /api/v1/workflows/settings",
                "PUT /api/v1/workflows/settings",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"mode":"members","memberIds":["m1"]}""", server.requests[1].bodyText())
        assertJson("""{"mode":"admins"}""", server.requests[2].bodyText())
    }
}
