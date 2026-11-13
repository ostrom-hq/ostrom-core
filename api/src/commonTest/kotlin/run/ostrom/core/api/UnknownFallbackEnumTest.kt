// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.AgentEvent
import run.ostrom.core.api.models.Approval
import run.ostrom.core.api.models.ApprovalActionKind
import run.ostrom.core.api.models.ApprovalActionRisk
import run.ostrom.core.api.models.ApprovalStatus
import run.ostrom.core.api.models.DiffStats
import run.ostrom.core.api.models.ArtifactType
import run.ostrom.core.api.models.AutomationTriggerKind
import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.CheckOutcome
import run.ostrom.core.api.models.CheckState
import run.ostrom.core.api.models.GateMode
import run.ostrom.core.api.models.GateStatus
import run.ostrom.core.api.models.GitHubConnection
import run.ostrom.core.api.models.MemberRole
import run.ostrom.core.api.models.NodeConditionOp
import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.core.api.models.PackageFileMode
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.ReceiptSubjectKind
import run.ostrom.core.api.models.RiskLevel
import run.ostrom.core.api.models.RuleStatus
import run.ostrom.core.api.models.RuleStrength
import run.ostrom.core.api.models.RunLocation
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.TodoHookEventKind
import run.ostrom.core.api.models.TodoItemOrigin
import run.ostrom.core.api.models.TodoItemStatus
import run.ostrom.core.api.models.TodoLinkKind
import run.ostrom.core.api.models.TodoStatus
import run.ostrom.core.api.models.TodoStopAction
import run.ostrom.core.api.models.TodoStopPhase
import run.ostrom.core.api.models.Wait
import run.ostrom.core.api.models.WaitStatus
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnknownFallbackEnumTest {

    private fun <T : Enum<T>> check(
        entries: List<T>,
        serializer: KSerializer<T>,
        unknown: T,
        valueOf: (T) -> String,
    ) {
        for (entry in entries.filter { it != unknown }) {
            val encoded = OstromJson.encodeToJsonElement(serializer, entry)
            assertEquals(JsonPrimitive(valueOf(entry)), encoded, "$entry")
            assertEquals(entry, OstromJson.decodeFromJsonElement(serializer, encoded))
        }
        assertEquals(unknown, OstromJson.decodeFromJsonElement(serializer, JsonPrimitive("added-by-a-newer-server")))
        assertFailsWith<SerializationException> { OstromJson.encodeToJsonElement(serializer, unknown) }
        val descriptor = serializer.descriptor
        val values = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        assertEquals(entries.filter { it != unknown }.map(valueOf), values)
    }

    @Test
    fun everySharedEnumReadsAnUnknownValueAsUnknownAndNeverWritesIt() {
        check(AgentEvent.entries, AgentEvent.Serializer, AgentEvent.UNKNOWN, AgentEvent::value)
        check(ApprovalStatus.entries, ApprovalStatus.Serializer, ApprovalStatus.UNKNOWN, ApprovalStatus::value)
        check(
            ApprovalActionKind.entries, ApprovalActionKind.Serializer, ApprovalActionKind.UNKNOWN,
            ApprovalActionKind::value,
        )
        check(
            ApprovalActionRisk.entries, ApprovalActionRisk.Serializer, ApprovalActionRisk.UNKNOWN,
            ApprovalActionRisk::value,
        )
        check(ArtifactType.entries, ArtifactType.Serializer, ArtifactType.UNKNOWN, ArtifactType::value)
        check(
            AutomationTriggerKind.entries, AutomationTriggerKind.Serializer, AutomationTriggerKind.UNKNOWN,
            AutomationTriggerKind::value,
        )
        check(CheckAttestation.entries, CheckAttestation.Serializer, CheckAttestation.UNKNOWN, CheckAttestation::value)
        check(CheckOutcome.entries, CheckOutcome.Serializer, CheckOutcome.UNKNOWN, CheckOutcome::value)
        check(CheckState.entries, CheckState.Serializer, CheckState.UNKNOWN, CheckState::value)
        check(GateMode.entries, GateMode.Serializer, GateMode.UNKNOWN, GateMode::value)
        check(GateStatus.entries, GateStatus.Serializer, GateStatus.UNKNOWN, GateStatus::value)
        check(GitHubConnection.entries, GitHubConnection.Serializer, GitHubConnection.UNKNOWN, GitHubConnection::value)
        check(MemberRole.entries, MemberRole.Serializer, MemberRole.UNKNOWN, MemberRole::value)
        check(NodeConditionOp.entries, NodeConditionOp.Serializer, NodeConditionOp.UNKNOWN, NodeConditionOp::value)
        check(
            NotificationStatus.entries, NotificationStatus.Serializer, NotificationStatus.UNKNOWN,
            NotificationStatus::value,
        )
        check(PackageFileMode.entries, PackageFileMode.Serializer, PackageFileMode.UNKNOWN, PackageFileMode::value)
        check(PackageMode.entries, PackageMode.Serializer, PackageMode.UNKNOWN, PackageMode::value)
        check(
            ReceiptSubjectKind.entries, ReceiptSubjectKind.Serializer, ReceiptSubjectKind.UNKNOWN,
            ReceiptSubjectKind::value,
        )
        check(RiskLevel.entries, RiskLevel.Serializer, RiskLevel.UNKNOWN, RiskLevel::value)
        check(RuleStatus.entries, RuleStatus.Serializer, RuleStatus.UNKNOWN, RuleStatus::value)
        check(RuleStrength.entries, RuleStrength.Serializer, RuleStrength.UNKNOWN, RuleStrength::value)
        check(RunLocation.entries, RunLocation.Serializer, RunLocation.UNKNOWN, RunLocation::value)
        check(RunStatus.entries, RunStatus.Serializer, RunStatus.UNKNOWN, RunStatus::value)
        check(
            TodoHookEventKind.entries, TodoHookEventKind.Serializer, TodoHookEventKind.UNKNOWN,
            TodoHookEventKind::value,
        )
        check(TodoItemOrigin.entries, TodoItemOrigin.Serializer, TodoItemOrigin.UNKNOWN, TodoItemOrigin::value)
        check(TodoItemStatus.entries, TodoItemStatus.Serializer, TodoItemStatus.UNKNOWN, TodoItemStatus::value)
        check(TodoLinkKind.entries, TodoLinkKind.Serializer, TodoLinkKind.UNKNOWN, TodoLinkKind::value)
        check(TodoStatus.entries, TodoStatus.Serializer, TodoStatus.UNKNOWN, TodoStatus::value)
        check(TodoStopAction.entries, TodoStopAction.Serializer, TodoStopAction.UNKNOWN, TodoStopAction::value)
        check(TodoStopPhase.entries, TodoStopPhase.Serializer, TodoStopPhase.UNKNOWN, TodoStopPhase::value)
        check(WaitStatus.entries, WaitStatus.Serializer, WaitStatus.UNKNOWN, WaitStatus::value)
        check(WorkflowNodeKind.entries, WorkflowNodeKind.Serializer, WorkflowNodeKind.UNKNOWN, WorkflowNodeKind::value)
        check(WorkflowStatus.entries, WorkflowStatus.Serializer, WorkflowStatus.UNKNOWN, WorkflowStatus::value)
    }

    @Test
    fun aMessageWithANewStatusStillDecodes() {
        val wait = OstromJson.decodeFromString(
            Wait.serializer(),
            """{"id":"w1","kind":"question","status":"snoozed","assignee":"ana",""" +
                """"question":{"title":"Ship?"},"createdAt":"t"}""",
        )
        assertEquals(WaitStatus.UNKNOWN, wait.status)
    }

    @Test
    fun anApprovalCarriesItsActionAndAnUnknownKindStillDecodes() {
        val approval = OstromJson.decodeFromString(
            Approval.serializer(),
            """{"id":"a1","title":"Merge #12","requestedFrom":"ana","channel":"app","status":"pending",""" +
                """"shownDigest":"d","action":{"kind":"release","repo":"o/r","ref":"main","headSha":"h",""" +
                """"risk":"high"},"diffStats":{"files":3,"additions":40,"deletions":2},"diffTruncated":true}""",
        )
        val action = approval.action ?: error("no action")
        assertEquals(ApprovalActionKind.UNKNOWN, action.kind)
        assertEquals(ApprovalActionRisk.HIGH, action.risk)
        assertEquals(null, action.baseSha)
        assertEquals(DiffStats(files = 3, additions = 40, deletions = 2), approval.diffStats)
        assertEquals(true, approval.diffTruncated)
    }
}
