// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.models.GovernanceConfirmationPending
import run.ostrom.core.api.models.SaveWorkflowRequest
import run.ostrom.core.api.models.StartWorkflowRunRequest
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowPage
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowRunPage

/**
 * The organization's workflows and their runs (`/api/v1/workflows`, `/api/v1/runs`): read, written (an owner, an admin
 * or an editor), started and cancelled (an owner or an admin). Reached through [OstromClient.workflows].
 *
 * A refusal throws [OstromException] with the server's `error.type`: `forbidden` or `workflow_editors_only` (403, not
 * an editor, or the workflow holds a gate node), `invalid_workflow`, `invalid_request` or `invalid_run` (400),
 * `not_found` (404), `conflict` (409), plus the keys named on each method.
 */
public class WorkflowsClient internal constructor(private val transport: Transport) {
    /**
     * The workflows (`GET /api/v1/workflows`).
     *
     * @param repo Only this repository's.
     * @param status Only those in this status (`proposed`, `accepted`…); every status when null.
     */
    public suspend fun list(repo: String? = null, status: String? = null): WorkflowPage =
        transport.json(HttpMethod.Get, "/api/v1/workflows", query("repo" to repo, "status" to status))

    /** One workflow (`GET /api/v1/workflows/{id}`). */
    public suspend fun get(id: String): Workflow = transport.json(HttpMethod.Get, "/api/v1/workflows/${segment(id)}")

    /**
     * A page of a workflow's runs, newest first (`GET /api/v1/workflows/{id}/runs`).
     *
     * @param status Only the runs in this status (`running`, `waiting`…); every status when null.
     * @param before The [WorkflowRunPage.next] of the previous page; the first page when null.
     */
    public suspend fun runs(id: String, status: String? = null, before: String? = null): WorkflowRunPage =
        transport.json(
            HttpMethod.Get,
            "/api/v1/workflows/${segment(id)}/runs",
            query("status" to status, "before" to before),
        )

    /**
     * A page of the caller's runs of every workflow, newest first (`GET /api/v1/runs`). Every member's runs for an
     * administrator (`all`) is planned, not served yet.
     *
     * @param status Only the runs in this status; every status when null.
     * @param before The [WorkflowRunPage.next] of the previous page; the first page when null.
     */
    public suspend fun allRuns(status: String? = null, before: String? = null): WorkflowRunPage =
        transport.json(HttpMethod.Get, "/api/v1/runs", query("status" to status, "before" to before))

    /** One run with its visits (`GET /api/v1/runs/{id}`). */
    public suspend fun run(id: String): WorkflowRun = transport.json(HttpMethod.Get, "/api/v1/runs/${segment(id)}")

    /**
     * Creates a workflow (`POST /api/v1/workflows`).
     *
     * Throws [OstromException] with `invalid_workflow` (400, malformed), `workflow_editors_only` (403, the caller may
     * not decide it), `conflict` (409, this id exists), `workflow_name_taken` (409), `workflow_limit` (409, the
     * organization's cap) or `confirmation_limit` (429, ten confirmations already wait).
     *
     * @return [GovernedWrite.Applied] with the stored workflow (`201`), or [GovernedWrite.Held] when a person's API
     *   token only opened a confirmation (`202`).
     */
    public suspend fun create(workflow: Workflow): GovernedWrite<Workflow> =
        transport.write(HttpMethod.Post, "/api/v1/workflows", workflow).governed()

    /**
     * Saves a workflow (`PUT /api/v1/workflows/{id}`); its id must be [id].
     *
     * Throws [OstromException] with `invalid_request` (400, the workflow's id is not [id]), `invalid_workflow` (400),
     * `workflow_editors_only` (403), `not_found` (404), `workflow_conflict` (409, [expectedVersion] is stale),
     * `workflow_name_taken` (409) or `confirmation_limit` (429).
     *
     * @param expectedVersion The version the editor read, by default the [workflow]'s own: a newer stored one is
     *   refused (`workflow_conflict`); null for no check.
     * @return [GovernedWrite.Applied] with the stored workflow (`200`), or [GovernedWrite.Held] (`202`).
     */
    public suspend fun save(
        id: String,
        workflow: Workflow,
        expectedVersion: Int? = workflow.version,
    ): GovernedWrite<Workflow> {
        require(workflow.id == id) { "The workflow's id is not the path's." }
        return transport.write(
            HttpMethod.Put,
            "/api/v1/workflows/${segment(id)}",
            SaveWorkflowRequest(workflow, expectedVersion),
        ).governed()
    }

    /**
     * Deletes a workflow (`DELETE /api/v1/workflows/{id}`).
     *
     * Throws [OstromException] with `workflow_editors_only` (403), `not_found` (404), `workflow_in_use` (409, another
     * workflow or an automation uses it) or `confirmation_limit` (429).
     *
     * @return [GovernedWrite.Applied] once deleted (`204`), or [GovernedWrite.Held] (`202`).
     */
    public suspend fun delete(id: String): GovernedWrite<Unit> {
        val response = transport.send(HttpMethod.Delete, "/api/v1/workflows/${segment(id)}")
        return if (response.status == HttpStatusCode.Accepted) {
            GovernedWrite.Held(response.body<GovernanceConfirmationPending>())
        } else {
            GovernedWrite.Applied(Unit)
        }
    }

    /**
     * Starts a run of an accepted workflow as the caller (`POST /api/v1/workflows/{id}/runs`).
     *
     * Throws [OstromException] with `invalid_request` (400, an undeclared input, a missing required one or one too
     * long), `forbidden` (403, not an owner or an admin, or an agent token), `not_found` (404), `conflict` (409, the
     * workflow is not accepted: `workflow_not_accepted` in the message) or `run_limit` (409, twenty unfinished runs).
     *
     * @param inputs The workflow's inputs, by [run.ostrom.core.api.models.WorkflowInput.id].
     * @param idempotencyKey One [Idempotency.newKey] per press, reused for each retry of it: a start repeated with the
     *   same key (an answer was lost) returns the first run (`200`, [Idempotency.REPLAYED_HEADER]) instead of starting
     *   a second one. It must satisfy [Idempotency.isValid], else an [IllegalArgumentException] is thrown before any
     *   request (the server answers a malformed key `invalid_request`, 400).
     * @return The run, `queued` or already moving.
     */
    public suspend fun start(
        id: String,
        inputs: Map<String, String> = emptyMap(),
        idempotencyKey: String? = null,
    ): WorkflowRun {
        require(idempotencyKey == null || Idempotency.isValid(idempotencyKey)) {
            "An idempotency key is 1 to ${Idempotency.MAX_LENGTH} characters of A-Za-z0-9._:-."
        }
        return transport.send(HttpMethod.Post, "/api/v1/workflows/${segment(id)}/runs") {
            contentType(ContentType.Application.Json)
            if (idempotencyKey != null) header(Idempotency.KEY_HEADER, idempotencyKey)
            setBody(StartWorkflowRunRequest(inputs))
        }.body()
    }

    /**
     * Cancels a run (`POST /api/v1/runs/{id}/cancel`).
     *
     * Throws [OstromException] with `forbidden` (403, not an owner or an admin), `not_found` (404), `conflict` (409,
     * `run_ended` in the message when it already succeeded, failed or was cancelled, `run_busy` when the engine played
     * it meanwhile: try again).
     *
     * @return The run as cancelled.
     */
    public suspend fun cancel(runId: String): WorkflowRun =
        transport.json(HttpMethod.Post, "/api/v1/runs/${segment(runId)}/cancel")
}

private suspend inline fun <reified B> Transport.write(method: HttpMethod, path: String, body: B): HttpResponse =
    send(method, path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

private suspend inline fun <reified R> HttpResponse.governed(): GovernedWrite<R> =
    if (status == HttpStatusCode.Accepted) {
        GovernedWrite.Held(body<GovernanceConfirmationPending>())
    } else {
        GovernedWrite.Applied(body<R>())
    }
