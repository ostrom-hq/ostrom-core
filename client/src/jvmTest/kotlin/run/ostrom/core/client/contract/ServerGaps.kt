// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client.contract

/**
 * What the core models and the client carry that the server's document does not serve yet. Each entry is a server gap
 * reported to the coordinator, to be ported to ostrom-server, not a model to delete: [ContractTest] lets these
 * differences through and fails once one is served (the entry is then stale and goes, with the bump of
 * `client/openapi.version`). Any other difference fails the check.
 */
internal object ServerGaps {

    /** `Model.property`: a property of a core model that the document's schema lacks, with why it stays. */
    val properties: Map<String, String> = mapOf(
        "ApprovalView.note" to "the view shows the approver's comment (the server will return it as note)",
        "CloudSession.automationId" to "the automation that started a session",
        "Session.runId" to "the workflow run a session belongs to: the app opens it from the session",
    )

    /** `client.call?parameter`: a query parameter a client call sends that the document's operation lacks. */
    val queryParameters: Map<String, String> = mapOf(
        "cloudSessions.list?status" to "the cloud sessions list filters by status",
    )
}
