package dev.ostrom.core.api

import dev.ostrom.core.api.models.AnswerApprovalRequest
import dev.ostrom.core.api.models.ApiError
import dev.ostrom.core.api.models.Approval
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
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin models and `api/openapi.yaml` describe the same JSON: same schemas, same properties, same types, same
 * required properties. An optional property may be nullable in Kotlin (`= null`, omitted when null) where the
 * contract only says it may be absent; a required one must agree on null.
 */
class ContractTest {

    private val models = listOf(
        AnswerApprovalRequest.serializer(),
        ApiError.serializer(),
        Approval.serializer(),
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

    private fun contract(): JsonObject {
        val text = SystemFileSystem.source(Path(CONTRACT_SHAPES_FILE)).buffered().use { it.readString() }
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun everyContractSchemaHasAKotlinModelAndEveryModelIsInTheContract() {
        val kotlin = ContractDump.dump(models)
        val contract = contract()
        assertEquals(contract.keys.sorted(), kotlin.keys.sorted())
    }

    @Test
    fun everyModelHasTheShapeOfItsSchema() {
        val kotlin = ContractDump.dump(models)
        val problems = contract().flatMap { (name, schema) ->
            kotlin[name]?.let { differences(name, schema.jsonObject, it.jsonObject) } ?: emptyList()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun differences(name: String, schema: JsonObject, model: JsonObject): List<String> {
        schema["enum"]?.let { values ->
            return if (values == model["enum"]) emptyList() else listOf("$name: enum $values in the contract, ${model["enum"]} in Kotlin")
        }
        val expected = schema.getValue("properties").jsonObject
        val actual = model["properties"]?.jsonObject ?: return listOf("$name: an object in the contract, not in Kotlin")
        val missing = (expected.keys - actual.keys).map { "$name.$it: in the contract, not in Kotlin" }
        val extra = (actual.keys - expected.keys).map { "$name.$it: in Kotlin, not in the contract" }
        val mismatched = expected.keys.intersect(actual.keys).mapNotNull { property ->
            property(name, property, expected.getValue(property).jsonObject, actual.getValue(property).jsonObject)
        }
        return missing + extra + mismatched
    }

    private fun property(name: String, property: String, expected: JsonObject, actual: JsonObject): String? {
        val required = expected.flag("required")
        val nullable = expected.flag("nullable")
        return when {
            expected["type"] != actual["type"] -> "$name.$property: type ${expected["type"]} in the contract, ${actual["type"]} in Kotlin"
            required != actual.flag("required") -> "$name.$property: required=$required in the contract, not in Kotlin"
            required && nullable != actual.flag("nullable") -> "$name.$property: nullable=$nullable in the contract, not in Kotlin"
            !required && nullable && !actual.flag("nullable") -> "$name.$property: nullable in the contract, not in Kotlin"
            else -> null
        }
    }

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
}
