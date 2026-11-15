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
        "ActivityItem.approvalId" to "the activity feed links an approval item to its approval",
        "Approval.taskId" to "TeamCode's approval names its task",
        "Approval.commitDetails" to "the app shows the commits of a push approval",
        "Approval.checks" to "the app shows the checks of a push approval",
        "Approval.expiresAt" to "an approval expires",
        "Approval.action" to "the gated action of an approval",
        "Approval.diffStats" to "the app shows the size of the diff",
        "Approval.diffTruncated" to "the app says the diff is cut",
        "ApprovalView.rules" to "the view shows the rules behind the gate",
        "ApprovalView.diff" to "the view shows the diff",
        "ApprovalView.note" to "the view shows the approver's note",
        "ApprovalView.verification" to "the view verifies the receipt",
        "ApprovalWho.agent" to "the view names the agent that asked",
        "ApprovalWho.model" to "the view names the agent's model",
        "ApprovalViewer.name" to "the queue shows the caller's display name",
        "Member.teams" to "a member's teams",
        "Member.slackUserId" to "a member's Slack link",
        "Member.discordUserId" to "a member's Discord link",
        "Organization.description" to "an organization's description",
        "Organization.createdAt" to "an organization's creation time",
        "CloudSession.runId" to "the workflow run a session belongs to",
        "CloudSession.automationId" to "the automation that started a session",
        "DevicePage.next" to "the device list is paged",
        "Device.cliVersion" to "the CLI version a device last reported",
        "EnvironmentValues.secrets" to "the secret variables of the caller's environment",
        "EnvironmentValues.oneTime" to "the one-time variables of the caller's environment",
        "Session.devVia" to "how the developer of a session was identified",
        "Session.runId" to "the workflow run a session belongs to",
        "Spending.byWorkflow" to "spending by workflow",
        "ExceededBudget.label" to "the label of an exceeded budget",
        "StartWorkflowRunRequest.repository" to "a manual run names its agent nodes' repository",
    )

    /** `client.call?parameter`: a query parameter a client call sends that the document's operation lacks. */
    val queryParameters: Map<String, String> = mapOf(
        "cloudSessions.list?status" to "the cloud sessions list filters by status",
    )
}
