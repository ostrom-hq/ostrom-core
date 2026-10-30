// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.api

import dev.ostrom.core.api.models.AnswerApprovalRequest
import dev.ostrom.core.api.models.ApiError
import dev.ostrom.core.api.models.Approval
import dev.ostrom.core.api.models.ApprovalAction
import dev.ostrom.core.api.models.ApprovalCheck
import dev.ostrom.core.api.models.ApprovalCommit
import dev.ostrom.core.api.models.ApproverGate
import dev.ostrom.core.api.models.ApproverLine
import dev.ostrom.core.api.models.ApproversGraph
import dev.ostrom.core.api.models.Automation
import dev.ostrom.core.api.models.AutomationAction
import dev.ostrom.core.api.models.AutomationTrigger
import dev.ostrom.core.api.models.AutomationVariable
import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.CompiledHook
import dev.ostrom.core.api.models.CompiledNode
import dev.ostrom.core.api.models.CompiledNotify
import dev.ostrom.core.api.models.CompiledScript
import dev.ostrom.core.api.models.CompiledTrigger
import dev.ostrom.core.api.models.Decision
import dev.ostrom.core.api.models.DecisionSubject
import dev.ostrom.core.api.models.Deliverable
import dev.ostrom.core.api.models.Dev
import dev.ostrom.core.api.models.DiffStats
import dev.ostrom.core.api.models.EndSessionRequest
import dev.ostrom.core.api.models.ErrorDetail
import dev.ostrom.core.api.models.Evidence
import dev.ostrom.core.api.models.GateAction
import dev.ostrom.core.api.models.GateAskRequest
import dev.ostrom.core.api.models.GateCi
import dev.ostrom.core.api.models.GatePullRequest
import dev.ostrom.core.api.models.GatePush
import dev.ostrom.core.api.models.GatePushRef
import dev.ostrom.core.api.models.GateRef
import dev.ostrom.core.api.models.GateRequest
import dev.ostrom.core.api.models.GateResponse
import dev.ostrom.core.api.models.Member
import dev.ostrom.core.api.models.MemberInvite
import dev.ostrom.core.api.models.NodePosition
import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.NodeRunOn
import dev.ostrom.core.api.models.NodeSuggestions
import dev.ostrom.core.api.models.NodeVisit
import dev.ostrom.core.api.models.NodeWhen
import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.Organization
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.PackageFile
import dev.ostrom.core.api.models.PackageHarness
import dev.ostrom.core.api.models.PackageMcp
import dev.ostrom.core.api.models.PackageMcpServer
import dev.ostrom.core.api.models.PackageMcpTool
import dev.ostrom.core.api.models.PackageWorkflow
import dev.ostrom.core.api.models.Produced
import dev.ostrom.core.api.models.Receipt
import dev.ostrom.core.api.models.ReceiptDecision
import dev.ostrom.core.api.models.ReceiptDraft
import dev.ostrom.core.api.models.ReceiptNode
import dev.ostrom.core.api.models.ReceiptPackage
import dev.ostrom.core.api.models.ReceiptRule
import dev.ostrom.core.api.models.ReceiptSubject
import dev.ostrom.core.api.models.ReportCheckRequest
import dev.ostrom.core.api.models.Rule
import dev.ostrom.core.api.models.RuleSource
import dev.ostrom.core.api.models.ScriptApproval
import dev.ostrom.core.api.models.ScriptSuggestion
import dev.ostrom.core.api.models.Session
import dev.ostrom.core.api.models.SessionPackage
import dev.ostrom.core.api.models.SessionResponse
import dev.ostrom.core.api.models.StartSessionRequest
import dev.ostrom.core.api.models.StringListSuggestion
import dev.ostrom.core.api.models.StringSuggestion
import dev.ostrom.core.api.models.SuggestedChannel
import dev.ostrom.core.api.models.Team
import dev.ostrom.core.api.models.Wait
import dev.ostrom.core.api.models.WaitAction
import dev.ostrom.core.api.models.WaitAnswer
import dev.ostrom.core.api.models.WaitField
import dev.ostrom.core.api.models.WaitRef
import dev.ostrom.core.api.models.WaitSpec
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowEdge
import dev.ostrom.core.api.models.WorkflowInput
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowRun
import kotlinx.serialization.KSerializer

/** Every model of the API contract: the schemas of `api/openapi.yaml` that have a Kotlin model. */
internal val apiModels: List<KSerializer<*>> = listOf(
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
    CheckRun.serializer(),
    CompiledHook.serializer(),
    CompiledNode.serializer(),
    CompiledNotify.serializer(),
    CompiledScript.serializer(),
    CompiledTrigger.serializer(),
    Decision.serializer(),
    DecisionSubject.serializer(),
    Deliverable.serializer(),
    Dev.serializer(),
    DiffStats.serializer(),
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
    Member.serializer(),
    MemberInvite.serializer(),
    NodePosition.serializer(),
    NodeRun.serializer(),
    NodeRunOn.serializer(),
    NodeSuggestions.serializer(),
    NodeVisit.serializer(),
    NodeWhen.serializer(),
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
    RuleSource.serializer(),
    ScriptApproval.serializer(),
    ScriptSuggestion.serializer(),
    Session.serializer(),
    SessionPackage.serializer(),
    SessionResponse.serializer(),
    StartSessionRequest.serializer(),
    StringListSuggestion.serializer(),
    StringSuggestion.serializer(),
    SuggestedChannel.serializer(),
    Team.serializer(),
    Wait.serializer(),
    WaitAction.serializer(),
    WaitAnswer.serializer(),
    WaitField.serializer(),
    WaitRef.serializer(),
    WaitSpec.serializer(),
    Workflow.serializer(),
    WorkflowEdge.serializer(),
    WorkflowInput.serializer(),
    WorkflowNode.serializer(),
    WorkflowRun.serializer(),
)
