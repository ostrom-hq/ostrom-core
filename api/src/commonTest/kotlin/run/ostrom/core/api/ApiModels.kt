// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.Account
import run.ostrom.core.api.models.AccountEmail
import run.ostrom.core.api.models.ActivityActor
import run.ostrom.core.api.models.ActivityItem
import run.ostrom.core.api.models.ActivityPage
import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.AnswerApprovalRequest
import run.ostrom.core.api.models.AnswerWaitRequest
import run.ostrom.core.api.models.ApiError
import run.ostrom.core.api.models.Approval
import run.ostrom.core.api.models.ApprovalAction
import run.ostrom.core.api.models.ApprovalCallerView
import run.ostrom.core.api.models.ApprovalCheck
import run.ostrom.core.api.models.ApprovalCommit
import run.ostrom.core.api.models.ApprovalQueue
import run.ostrom.core.api.models.ApprovalView
import run.ostrom.core.api.models.ApprovalViewer
import run.ostrom.core.api.models.ApprovalWhat
import run.ostrom.core.api.models.ApprovalWho
import run.ostrom.core.api.models.ApproverGate
import run.ostrom.core.api.models.ApproverLine
import run.ostrom.core.api.models.ApproversGraph
import run.ostrom.core.api.models.Artifact
import run.ostrom.core.api.models.ArtifactConflict
import run.ostrom.core.api.models.ArtifactPage
import run.ostrom.core.api.models.ArtifactTicket
import run.ostrom.core.api.models.ArtifactTicketRequest
import run.ostrom.core.api.models.ArtifactVersion
import run.ostrom.core.api.models.ArtifactVersionPage
import run.ostrom.core.api.models.AuthToken
import run.ostrom.core.api.models.AuthUser
import run.ostrom.core.api.models.Automation
import run.ostrom.core.api.models.AutomationAction
import run.ostrom.core.api.models.AutomationTrigger
import run.ostrom.core.api.models.AutomationVariable
import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.CheckRun
import run.ostrom.core.api.models.CompiledHook
import run.ostrom.core.api.models.CompiledNode
import run.ostrom.core.api.models.CompiledNotify
import run.ostrom.core.api.models.CompiledScript
import run.ostrom.core.api.models.CompiledTrigger
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.Decision
import run.ostrom.core.api.models.DecisionSubject
import run.ostrom.core.api.models.DeliverRequest
import run.ostrom.core.api.models.DeliverSessionRequest
import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.Dev
import run.ostrom.core.api.models.DeviceCode
import run.ostrom.core.api.models.DeviceLoginStart
import run.ostrom.core.api.models.DeviceTokenRequest
import run.ostrom.core.api.models.DiffStats
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.EndSessionRequest
import run.ostrom.core.api.models.ErrorDetail
import run.ostrom.core.api.models.Evidence
import run.ostrom.core.api.models.ExceededBudget
import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.GateAskRequest
import run.ostrom.core.api.models.GateCi
import run.ostrom.core.api.models.GatePullRequest
import run.ostrom.core.api.models.GatePush
import run.ostrom.core.api.models.GatePushRef
import run.ostrom.core.api.models.GateRef
import run.ostrom.core.api.models.GateRequest
import run.ostrom.core.api.models.GateResponse
import run.ostrom.core.api.models.KeyRotation
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.Me
import run.ostrom.core.api.models.Member
import run.ostrom.core.api.models.MemberInvite
import run.ostrom.core.api.models.MobileToken
import run.ostrom.core.api.models.MobileTokenRequest
import run.ostrom.core.api.models.MobileUnlock
import run.ostrom.core.api.models.MobileUnlockRequest
import run.ostrom.core.api.models.MyOrganization
import run.ostrom.core.api.models.MyOrganizationList
import run.ostrom.core.api.models.NodeCondition
import run.ostrom.core.api.models.NodePosition
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeRunOn
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.NodeWhen
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.NotificationPreferences
import run.ostrom.core.api.models.Organization
import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.core.api.models.PackageFile
import run.ostrom.core.api.models.PackageHarness
import run.ostrom.core.api.models.PackageMcp
import run.ostrom.core.api.models.PackageMcpServer
import run.ostrom.core.api.models.PackageMcpTool
import run.ostrom.core.api.models.PackageWorkflow
import run.ostrom.core.api.models.Produced
import run.ostrom.core.api.models.PublicKey
import run.ostrom.core.api.models.PushDevice
import run.ostrom.core.api.models.PushPayload
import run.ostrom.core.api.models.NotificationSetting
import run.ostrom.core.api.models.NotificationSettings
import run.ostrom.core.api.models.NotificationSettingChange
import run.ostrom.core.api.models.PushTestResult
import run.ostrom.core.api.models.PushTestResults
import run.ostrom.core.api.models.PushRelayRegisterRequest
import run.ostrom.core.api.models.PushRelayRegisterResponse
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse
import run.ostrom.core.api.models.QuietHours
import run.ostrom.core.api.models.Receipt
import run.ostrom.core.api.models.ReceiptDecision
import run.ostrom.core.api.models.ReceiptDraft
import run.ostrom.core.api.models.ReceiptKeyList
import run.ostrom.core.api.models.ReceiptNode
import run.ostrom.core.api.models.ReceiptPackage
import run.ostrom.core.api.models.ReceiptPage
import run.ostrom.core.api.models.ReceiptRule
import run.ostrom.core.api.models.ReceiptSubject
import run.ostrom.core.api.models.RegisterPushDeviceRequest
import run.ostrom.core.api.models.ReportCheckRequest
import run.ostrom.core.api.models.ResetTodoRequest
import run.ostrom.core.api.models.Rule
import run.ostrom.core.api.models.RuleGate
import run.ostrom.core.api.models.RuleGateCheck
import run.ostrom.core.api.models.RuleSource
import run.ostrom.core.api.models.ScriptApproval
import run.ostrom.core.api.models.ScriptSuggestion
import run.ostrom.core.api.models.Session
import run.ostrom.core.api.models.SessionGateAnswer
import run.ostrom.core.api.models.SessionPackage
import run.ostrom.core.api.models.SessionResponse
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.SignInMethods
import run.ostrom.core.api.models.Spending
import run.ostrom.core.api.models.SpendingDay
import run.ostrom.core.api.models.SpendingLine
import run.ostrom.core.api.models.StartSessionRequest
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.StringListSuggestion
import run.ostrom.core.api.models.StringSuggestion
import run.ostrom.core.api.models.SuggestedChannel
import run.ostrom.core.api.models.Team
import run.ostrom.core.api.models.Todo
import run.ostrom.core.api.models.TodoCheck
import run.ostrom.core.api.models.TodoCheckReport
import run.ostrom.core.api.models.TodoContext
import run.ostrom.core.api.models.TodoDoneWhen
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoHookEvent
import run.ostrom.core.api.models.TodoItem
import run.ostrom.core.api.models.TodoItemInput
import run.ostrom.core.api.models.TodoLink
import run.ostrom.core.api.models.TodoNote
import run.ostrom.core.api.models.TodoProof
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopHook
import run.ostrom.core.api.models.TodoStopHookRequest
import run.ostrom.core.api.models.TodoStopHooks
import run.ostrom.core.api.models.TodoStopItemReport
import run.ostrom.core.api.models.TodoStopRequest
import run.ostrom.core.api.models.Verification
import run.ostrom.core.api.models.Wait
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitAnswer
import run.ostrom.core.api.models.WaitEventReceived
import run.ostrom.core.api.models.WaitEventRequest
import run.ostrom.core.api.models.WaitField
import run.ostrom.core.api.models.WaitPage
import run.ostrom.core.api.models.WaitRef
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WaitWake
import run.ostrom.core.api.models.WaitWakeRequest
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowEditors
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowLimits
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowPendingDeliverable
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowSessionPending
import run.ostrom.core.api.models.WorkflowSettings
import run.ostrom.core.api.models.OrgEvent
import run.ostrom.core.api.models.EventGap
import run.ostrom.core.api.models.Webhook
import run.ostrom.core.api.models.WebhookList
import run.ostrom.core.api.models.CreatedWebhook
import run.ostrom.core.api.models.CreateWebhookRequest
import run.ostrom.core.api.models.UpdateWebhookRequest
import run.ostrom.core.api.models.WebhookDelivery
import run.ostrom.core.api.models.WebhookDeliveryList
import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryCuration
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemoryProposals
import run.ostrom.core.api.models.MemoryReview
import run.ostrom.core.api.models.MemoryReviews
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.SetMemoryCurationRequest
import run.ostrom.core.api.models.SupersededMemories
import run.ostrom.core.api.models.SupersededMemory
import run.ostrom.core.api.models.UpdateMemoryRequest
import kotlinx.serialization.KSerializer

/** Every model of the API contract: the schemas of `api/openapi.yaml` that have a Kotlin model. */
internal val apiModels: List<KSerializer<*>> = listOf(
    Account.serializer(),
    AccountEmail.serializer(),
    ActivityActor.serializer(),
    ActivityItem.serializer(),
    ActivityPage.serializer(),
    AddTodoItemRequest.serializer(),
    AnswerApprovalRequest.serializer(),
    AnswerWaitRequest.serializer(),
    ApiError.serializer(),
    Approval.serializer(),
    ApprovalAction.serializer(),
    ApprovalCallerView.serializer(),
    ApprovalCheck.serializer(),
    ApprovalCommit.serializer(),
    ApprovalQueue.serializer(),
    ApprovalView.serializer(),
    ApprovalViewer.serializer(),
    ApprovalWhat.serializer(),
    ApprovalWho.serializer(),
    ApproverGate.serializer(),
    ApproverLine.serializer(),
    ApproversGraph.serializer(),
    Artifact.serializer(),
    ArtifactConflict.serializer(),
    ArtifactPage.serializer(),
    ArtifactTicket.serializer(),
    ArtifactTicketRequest.serializer(),
    ArtifactVersion.serializer(),
    ArtifactVersionPage.serializer(),
    AuthToken.serializer(),
    AuthUser.serializer(),
    Automation.serializer(),
    AutomationAction.serializer(),
    AutomationTrigger.serializer(),
    AutomationVariable.serializer(),
    Capabilities.serializer(),
    CheckRun.serializer(),
    CompiledHook.serializer(),
    CompiledNode.serializer(),
    CompiledNotify.serializer(),
    CompiledScript.serializer(),
    CompiledTrigger.serializer(),
    CompleteTodoItemRequest.serializer(),
    CreateTodoRequest.serializer(),
    Decision.serializer(),
    DecisionSubject.serializer(),
    DeliverRequest.serializer(),
    DeliverSessionRequest.serializer(),
    Deliverable.serializer(),
    Dev.serializer(),
    DeviceCode.serializer(),
    DeviceLoginStart.serializer(),
    DeviceTokenRequest.serializer(),
    DiffStats.serializer(),
    DropTodoItemRequest.serializer(),
    EndSessionRequest.serializer(),
    ErrorDetail.serializer(),
    Evidence.serializer(),
    ExceededBudget.serializer(),
    GateAction.serializer(),
    GateAskRequest.serializer(),
    GateCi.serializer(),
    GatePullRequest.serializer(),
    GatePush.serializer(),
    GatePushRef.serializer(),
    GateRef.serializer(),
    GateRequest.serializer(),
    GateResponse.serializer(),
    KeyRotation.serializer(),
    LinkTodoItemRequest.serializer(),
    Me.serializer(),
    Member.serializer(),
    MemberInvite.serializer(),
    MobileToken.serializer(),
    MobileTokenRequest.serializer(),
    MobileUnlock.serializer(),
    MobileUnlockRequest.serializer(),
    MyOrganization.serializer(),
    MyOrganizationList.serializer(),
    NodeCondition.serializer(),
    NodePosition.serializer(),
    NodeRun.serializer(),
    NodeRunOn.serializer(),
    NodeSuggestions.serializer(),
    NodeVisit.serializer(),
    NodeWhen.serializer(),
    NoteTodoItemRequest.serializer(),
    Notification.serializer(),
    NotificationPreferences.serializer(),
    Organization.serializer(),
    PackageEnvelope.serializer(),
    PackageFile.serializer(),
    PackageHarness.serializer(),
    PackageMcp.serializer(),
    PackageMcpServer.serializer(),
    PackageMcpTool.serializer(),
    PackageWorkflow.serializer(),
    Produced.serializer(),
    PublicKey.serializer(),
    PushDevice.serializer(),
    PushPayload.serializer(),
    NotificationSetting.serializer(),
    NotificationSettings.serializer(),
    NotificationSettingChange.serializer(),
    PushTestResult.serializer(),
    PushTestResults.serializer(),
    PushRelayRegisterRequest.serializer(),
    PushRelayRegisterResponse.serializer(),
    PushRelaySendRequest.serializer(),
    PushRelaySendResponse.serializer(),
    QuietHours.serializer(),
    Receipt.serializer(),
    ReceiptDecision.serializer(),
    ReceiptDraft.serializer(),
    ReceiptKeyList.serializer(),
    ReceiptNode.serializer(),
    ReceiptPackage.serializer(),
    ReceiptPage.serializer(),
    ReceiptRule.serializer(),
    ReceiptSubject.serializer(),
    RegisterPushDeviceRequest.serializer(),
    ReportCheckRequest.serializer(),
    ResetTodoRequest.serializer(),
    Rule.serializer(),
    RuleGate.serializer(),
    RuleGateCheck.serializer(),
    RuleSource.serializer(),
    ScriptApproval.serializer(),
    ScriptSuggestion.serializer(),
    Session.serializer(),
    SessionGateAnswer.serializer(),
    SessionPackage.serializer(),
    SessionResponse.serializer(),
    SessionTodo.serializer(),
    SignInMethods.serializer(),
    Spending.serializer(),
    SpendingDay.serializer(),
    SpendingLine.serializer(),
    StartSessionRequest.serializer(),
    StartTodoItemRequest.serializer(),
    StartTodoItemResponse.serializer(),
    StringListSuggestion.serializer(),
    StringSuggestion.serializer(),
    SuggestedChannel.serializer(),
    Team.serializer(),
    Todo.serializer(),
    TodoCheck.Serializer,
    TodoCheckReport.serializer(),
    TodoContext.serializer(),
    TodoDoneWhen.serializer(),
    TodoHookErrorRequest.serializer(),
    TodoHookEvent.serializer(),
    TodoItem.serializer(),
    TodoItemInput.serializer(),
    TodoLink.serializer(),
    TodoNote.serializer(),
    TodoProof.serializer(),
    TodoStopDecision.serializer(),
    TodoStopHook.serializer(),
    TodoStopHookRequest.serializer(),
    TodoStopHooks.serializer(),
    TodoStopItemReport.serializer(),
    TodoStopRequest.serializer(),
    Verification.serializer(),
    Wait.serializer(),
    WaitAction.serializer(),
    WaitAnswer.serializer(),
    WaitEventReceived.serializer(),
    WaitEventRequest.serializer(),
    WaitField.serializer(),
    WaitPage.serializer(),
    WaitRef.serializer(),
    WaitSpec.serializer(),
    WaitWake.serializer(),
    OrgEvent.serializer(),
    EventGap.serializer(),
    Webhook.serializer(),
    WebhookList.serializer(),
    CreatedWebhook.serializer(),
    CreateWebhookRequest.serializer(),
    UpdateWebhookRequest.serializer(),
    WebhookDelivery.serializer(),
    WebhookDeliveryList.serializer(),
    WaitWakeRequest.serializer(),
    Workflow.serializer(),
    WorkflowEdge.serializer(),
    WorkflowEditors.serializer(),
    WorkflowInput.serializer(),
    WorkflowLimits.serializer(),
    WorkflowNode.serializer(),
    WorkflowPendingDeliverable.serializer(),
    WorkflowRun.serializer(),
    WorkflowSessionPending.serializer(),
    WorkflowSettings.serializer(),
    CreateMemoryRequest.serializer(),
    MemoryCuration.serializer(),
    MemoryEntry.serializer(),
    MemoryIndexEntry.serializer(),
    MemoryIndexPage.serializer(),
    MemoryProposals.serializer(),
    MemoryReview.serializer(),
    MemoryReviews.serializer(),
    MemorySearchResult.serializer(),
    SetMemoryCurationRequest.serializer(),
    SupersededMemories.serializer(),
    SupersededMemory.serializer(),
    UpdateMemoryRequest.serializer(),
)
