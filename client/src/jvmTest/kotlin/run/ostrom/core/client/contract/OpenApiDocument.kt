// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The OpenAPI document ostrom-server generates from its routes (`GET /api/openapi.json`, published as
 * `@ostrom-hq/openapi`): the API contract this client and these models must follow. The build reads the version pinned
 * in `client/openapi.version`; [load] finds the file through `OSTROM_OPENAPI_DOCUMENT`.
 */
internal class OpenApiDocument(private val root: JsonObject) {

    /** One operation of the document: its path template, method and JSON object. */
    data class Operation(val path: String, val method: String, val json: JsonObject) {
        val key: String get() = "${method.uppercase()} $path"
    }

    val schemas: JsonObject = root["components"]?.jsonObject?.get("schemas")?.jsonObject ?: JsonObject(emptyMap())

    private val templates: List<Pair<String, List<String>>> =
        root.getValue("paths").jsonObject.keys.map { it to it.trim('/').split('/') }

    /**
     * The operation [method] [path] (a concrete, encoded path) calls: the template whose segments match, literal
     * segments preferred to parameters (`/devices/current/daemon` before `/devices/{id}/daemon`). Null when no path or
     * no such method is documented.
     */
    fun operation(method: String, path: String): Operation? {
        val segments = path.trim('/').split('/')
        val template = templates
            .filter { (_, parts) ->
                parts.size == segments.size &&
                    parts.zip(segments).all { (part, segment) -> part.isParameter() || part == segment }
            }
            .maxByOrNull { (_, parts) -> parts.count { !it.isParameter() } }
            ?.first ?: return null
        val json = root.getValue("paths").jsonObject.getValue(template).jsonObject[method.lowercase()]?.jsonObject
        return json?.let { Operation(template, method, it) }
    }

    /** The names of the query parameters [operation] declares. */
    fun queryParameters(operation: Operation): Set<String> =
        (operation.json["parameters"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it["in"]?.jsonPrimitive?.content == "query" }
            .map { it.getValue("name").jsonPrimitive.content }
            .toSet()

    /** The JSON schema of [operation]'s request body, or null when it takes none. */
    fun requestSchema(operation: Operation): JsonObject? =
        operation.json["requestBody"]?.jsonObject?.get("content")?.jsonObject?.get(JSON)?.jsonObject
            ?.get("schema")?.jsonObject

    /** The JSON schemas of [operation]'s successful answers, by status. */
    fun responseSchemas(operation: Operation): Map<String, JsonObject?> =
        operation.json["responses"]?.jsonObject.orEmpty()
            .filterKeys { it.startsWith("2") }
            .mapValues { (_, response) ->
                response.jsonObject["content"]?.jsonObject?.get(JSON)?.jsonObject?.get("schema")?.jsonObject
            }

    /** [schema] with its `$ref` followed. */
    fun resolve(schema: JsonObject): JsonObject {
        val ref = schema["\$ref"]?.jsonPrimitive?.content ?: return schema
        return schemas[ref.substringAfterLast('/')]?.jsonObject ?: error("Unresolved reference $ref")
    }

    /** The name a `$ref` points to, or the schema's title. */
    fun nameOf(schema: JsonObject): String? =
        schema["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/') ?: schema["title"]?.jsonPrimitive?.content

    /**
     * A value of [schema]: every property, the first enum value, an empty array. The scripted server answers it, so a
     * call that makes several requests (an upload, a read before a write) reaches the next one.
     */
    fun example(schema: JsonObject?, depth: Int = 0): JsonElement {
        if (schema == null || depth > MAX_EXAMPLE_DEPTH) return JsonObject(emptyMap())
        val resolved = resolve(schema)
        val alternatives = (resolved["anyOf"] ?: resolved["oneOf"])?.jsonArray
        val firstType = types(resolved).firstOrNull { it != "null" }
        return when {
            alternatives != null ->
                alternatives.map { it.jsonObject }.firstOrNull { types(it) != listOf("null") }
                    ?.let { example(it, depth + 1) } ?: JsonNull
            resolved["enum"] != null -> resolved.getValue("enum").jsonArray.first { it != JsonNull }
            firstType == "string" -> JsonPrimitive("example")
            firstType == "integer" -> JsonPrimitive(1)
            firstType == "number" -> JsonPrimitive(1.0)
            firstType == "boolean" -> JsonPrimitive(true)
            firstType == "array" -> JsonArray(emptyList())
            else -> JsonObject(
                resolved["properties"]?.jsonObject.orEmpty().mapValues { (_, property) ->
                    example(property.jsonObject, depth + 1)
                },
            )
        }
    }

    companion object {
        private const val JSON = "application/json"
        private const val MAX_EXAMPLE_DEPTH = 8

        /** The environment variable that names the document's file. */
        const val DOCUMENT_VARIABLE = "OSTROM_OPENAPI_DOCUMENT"

        /** Set to `true` in CI: a missing document fails the check instead of skipping it. */
        const val REQUIRED_VARIABLE = "OSTROM_OPENAPI_REQUIRED"

        /** The document named by [DOCUMENT_VARIABLE], or null when it is not set. */
        fun load(): OpenApiDocument? {
            val path = System.getenv(DOCUMENT_VARIABLE)?.takeIf { it.isNotBlank() }
            if (path == null) {
                check(System.getenv(REQUIRED_VARIABLE) != "true") {
                    "$DOCUMENT_VARIABLE must name the server's openapi.json (client/openapi.version)."
                }
                println("$DOCUMENT_VARIABLE is not set: the contract check is skipped.")
                return null
            }
            return OpenApiDocument(Json.parseToJsonElement(File(path).readText()).jsonObject)
        }

        /** The JSON types of [schema] (`type: "string"` or `type: ["string", "null"]`). */
        fun types(schema: JsonObject): List<String> = when (val type = schema["type"]) {
            null -> emptyList()
            is JsonArray -> type.map { it.jsonPrimitive.content }
            else -> listOf(type.jsonPrimitive.content)
        }

        private fun String.isParameter() = startsWith("{") && endsWith("}")
    }
}
