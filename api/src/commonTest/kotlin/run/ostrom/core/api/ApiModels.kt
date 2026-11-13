// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.AnswerApprovalRequest
import run.ostrom.core.api.models.ApiError
import run.ostrom.core.api.models.Approval
import run.ostrom.core.api.models.ApprovalAction
import run.ostrom.core.api.models.ApprovalCheck
import run.ostrom.core.api.models.ApprovalCommit
import run.ostrom.core.api.models.ApproverGate
import run.ostrom.core.api.models.ApproverLine
import run.ostrom.core.api.models.ApproversGraph
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
import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.Dev
import run.ostrom.core.api.models.DiffStats
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.EndSessionRequest
import run.ostrom.core.api.models.ErrorDetail
import run.ostrom.core.api.models.Evidence
import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.GateAskRequest
import run.ostrom.core.api.models.GateCi
import run.ostrom.core.api.models.GatePullRequest
import run.ostrom.core.api.models.GatePush
import run.ostrom.core.api.models.GatePushRef
import run.ostrom.core.api.models.GateRef
import run.ostrom.core.api.models.GateRequest
import run.ostrom.core.api.models.GateResponse
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.Member
import run.ostrom.core.api.models.MemberInvite
import run.ostrom.core.api.models.NodeCondition
import run.ostrom.core.api.models.NodePosition
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeRunOn
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.NodeVisit
import run.ostrom.core.api.models.NodeWhen
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.Organization
import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.core.api.models.PackageFile
import run.ostrom.core.api.models.PackageHarness
import run.ostrom.core.api.models.PackageMcp
import run.ostrom.core.api.models.PackageMcpServer
import run.ostrom.core.api.models.PackageMcpTool
import run.ostrom.core.api.models.PackageWorkflow
import run.ostrom.core.api.models.Produced
import run.ostrom.core.api.models.Receipt
import run.ostrom.core.api.models.ReceiptDecision
import run.ostrom.core.api.models.ReceiptDraft
import run.ostrom.core.api.models.ReceiptNode
import run.ostrom.core.api.models.ReceiptPackage
import run.ostrom.core.api.models.ReceiptRule
import run.ostrom.core.api.models.ReceiptSubject
import run.ostrom.core.api.models.ReportCheckRequest
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
import run.ostrom.core.api.models.Wait
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitAnswer
import run.ostrom.core.api.models.WaitField
import run.ostrom.core.api.models.WaitRef
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.WaitEventReceived
import run.ostrom.core.api.models.WaitEventRequest
import run.ostrom.core.api.models.WaitWake
import run.ostrom.core.api.models.WaitWakeRequest
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowRun
import kotlinx.serialization.KSerializer

/** Every model of the API contract: the schemas of `api/openapi.yaml` that have a Kotlin model. */
internal val apiModels: List<KSerializer<*>> = listOf(
    AddTodoItemRequest.serializer(),
    AnswerApprovalRequest.serializer(),
    ApiError.serializer(),
    Approval.serializer(),
    ApprovalAction.serializer(),
    ApprovalCheck.serializer(),
    ApprovalCommit.serializer(),
    ApproverGate.serializer(),
    ApproverLine.serializer(),
    ApproversGraph.serializer(),
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
    Deliverable.serializer(),
    Dev.serializer(),
    DiffStats.serializer(),
    DropTodoItemRequest.serializer(),
    EndSessionRequest.serializer(),
    ErrorDetail.serializer(),
    Evidence.serializer(),
    GateAction.serializer(),
    GateAskRequest.serializer(),
    GateCi.serializer(),
    GatePullRequest.serializer(),
    GatePush.serializer(),
    GatePushRef.serializer(),
    GateRef.serializer(),
    GateRequest.serializer(),
    GateResponse.serializer(),
    LinkTodoItemRequest.serializer(),
    Member.serializer(),
    MemberInvite.serializer(),
    NodeCondition.serializer(),
    NodePosition.serializer(),
    NodeRun.serializer(),
    NodeRunOn.serializer(),
    NodeSuggestions.serializer(),
    NodeVisit.serializer(),
    NodeWhen.serializer(),
    NoteTodoItemRequest.serializer(),
    Notification.serializer(),
    Organization.serializer(),
    PackageEnvelope.serializer(),
    PackageFile.serializer(),
    PackageHarness.serializer(),
    PackageMcp.serializer(),
    PackageMcpServer.serializer(),
    PackageMcpTool.serializer(),
    PackageWorkflow.serializer(),
    Produced.serializer(),
    Receipt.serializer(),
    ReceiptDecision.serializer(),
    ReceiptDraft.serializer(),
    ReceiptNode.serializer(),
    ReceiptPackage.serializer(),
    ReceiptRule.serializer(),
    ReceiptSubject.serializer(),
    ReportCheckRequest.serializer(),
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
    Wait.serializer(),
    WaitAction.serializer(),
    WaitAnswer.serializer(),
    WaitField.serializer(),
    WaitRef.serializer(),
    WaitSpec.serializer(),
    WaitEventReceived.serializer(),
    WaitEventRequest.serializer(),
    WaitWake.serializer(),
    WaitWakeRequest.serializer(),
    Workflow.serializer(),
    WorkflowEdge.serializer(),
    WorkflowInput.serializer(),
    WorkflowNode.serializer(),
    WorkflowRun.serializer(),
)
