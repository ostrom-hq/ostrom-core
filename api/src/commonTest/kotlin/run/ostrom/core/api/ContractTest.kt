package run.ostrom.core.api

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    private fun contract(): JsonObject {
        val text = SystemFileSystem.source(Path(CONTRACT_SHAPES_FILE)).buffered().use { it.readString() }
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun everyContractSchemaHasAKotlinModelAndEveryModelIsInTheContract() {
        val kotlin = ContractDump.dump(apiModels)
        val contract = contract()
        assertEquals(contract.keys.sorted(), kotlin.keys.sorted())
    }

    @Test
    fun everyModelHasTheShapeOfItsSchema() {
        val kotlin = ContractDump.dump(apiModels)
        val problems = contract().flatMap { (name, schema) ->
            kotlin[name]?.let { differences(name, schema.jsonObject, it.jsonObject) } ?: emptyList()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun differences(name: String, schema: JsonObject, model: JsonObject): List<String> {
        val values = schema["enum"]
        val union = schema["oneOf"]
        return when {
            values != null -> enumDifferences(name, values, model["enum"])
            union != null -> unionDifferences(name, union, model["oneOf"])
            else -> objectDifferences(name, schema, model)
        }
    }

    private fun unionDifferences(name: String, union: JsonElement, actual: JsonElement?): List<String> =
        if (union == actual) emptyList() else listOf("$name: union $union in the contract, $actual in Kotlin")

    private fun enumDifferences(name: String, values: JsonElement, actual: JsonElement?): List<String> =
        if (values == actual) emptyList() else listOf("$name: enum $values in the contract, $actual in Kotlin")

    private fun objectDifferences(name: String, schema: JsonObject, model: JsonObject): List<String> {
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
            expected["type"] != actual["type"] ->
                "$name.$property: type ${expected["type"]} in the contract, ${actual["type"]} in Kotlin"
            required != actual.flag("required") -> "$name.$property: required=$required in the contract, not in Kotlin"
            required && nullable != actual.flag("nullable") ->
                "$name.$property: nullable=$nullable in the contract, not in Kotlin"
            !required && nullable && !actual.flag("nullable") ->
                "$name.$property: nullable in the contract, not in Kotlin"
            else -> null
        }
    }

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
}
