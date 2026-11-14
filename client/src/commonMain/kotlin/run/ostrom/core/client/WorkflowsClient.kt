// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowPage
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowRunPage

/**
 * The organization's workflows and their runs (`/api/v1/workflows`, `/api/v1/runs`), read only. Reached through
 * [OstromClient.workflows].
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
}
