// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compares the JSON a Kotlin model reads or writes (its serial descriptor) with a schema of the generated document:
 * the same properties, each of the same type, required and nullable alike, and the same enum values. The port of the
 * former `ContractTest` and `ContractDump` (the shapes of `api/openapi.yaml`), now against the server's own document.
 *
 * A Kotlin enum may stand for a schema that is a plain string (the server's DTO keeps it a string); when the schema
 * lists values, they are the enum's known values. A `JsonElement` matches anything.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SchemaComparison(
    private val document: OpenApiDocument,
    private val gaps: Set<String> = ServerGaps.properties.keys,
) {

    /** The entries of [gaps] a comparison met: the others are stale. */
    val usedGaps: MutableSet<String> = mutableSetOf()

    private val compared = mutableSetOf<Pair<String, String>>()

    /** The differences between [descriptor] and [schema], each prefixed with [at] (`CloudSession.status`). */
    fun differences(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val (target, nullable) = unwrapNullable(schema)
        val problems = mutableListOf<String>()
        if (descriptor.isNullable != nullable) {
            problems += "$at: nullable=$nullable in the document, ${descriptor.isNullable} in Kotlin"
        }
        problems += shape(at, descriptor.unwrapInline(), target)
        return problems
    }

    private fun shape(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val resolved = document.resolve(schema)
        val types = OpenApiDocument.types(resolved)
        val type = types.singleOrNull() ?: types.firstOrNull { it != "null" }
        return if (comparable(descriptor, resolved)) {
            when (descriptor.kind) {
                is PrimitiveKind -> primitive(at, descriptor.kind as PrimitiveKind, type)
                SerialKind.ENUM -> expect(at, "string", type).ifEmpty { enumValues(at, descriptor, resolved) }
                StructureKind.LIST, StructureKind.MAP -> collection(at, descriptor, resolved, type)
                StructureKind.CLASS, StructureKind.OBJECT ->
                    once(descriptor, resolved) { objectDifferences(at, descriptor, resolved) }
                PolymorphicKind.SEALED -> once(descriptor, resolved) { sealedDifferences(at, descriptor, resolved) }
                else -> listOf("$at: ${descriptor.kind} cannot be compared")
            }
        } else {
            emptyList()
        }
    }

    /**
     * False when there is nothing to compare: a `JsonElement` in Kotlin, any value in the document, or a free JSON
     * object (a route whose answer the server builds by hand documents no properties).
     */
    private fun comparable(descriptor: SerialDescriptor, schema: JsonObject): Boolean =
        !descriptor.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.Json") &&
            !(schema.isEmpty() || schema.keys == setOf("title", "description")) &&
            !(descriptor.kind == StructureKind.CLASS && schema.isFreeObject())

    private fun primitive(at: String, kind: PrimitiveKind, type: String?): List<String> = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> expect(at, "string", type)
        PrimitiveKind.BOOLEAN -> expect(at, "boolean", type)
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> if (type == "integer") emptyList() else expect(at, "number", type)
        else -> expect(at, "integer", type)
    }

    private fun collection(at: String, descriptor: SerialDescriptor, schema: JsonObject, type: String?): List<String> =
        if (descriptor.kind == StructureKind.LIST) {
            expect(at, "array", type).ifEmpty {
                schema["items"]?.jsonObject?.let { differences("$at[]", descriptor.getElementDescriptor(0), it) }
                    .orEmpty()
            }
        } else {
            expect(at, "object", type).ifEmpty {
                val values = schema["additionalProperties"] as? JsonObject
                if (values == null) {
                    listOf("$at: a map in Kotlin, an object without additionalProperties in the document")
                } else {
                    differences("$at{}", descriptor.getElementDescriptor(1), values)
                }
            }
        }

    private fun expect(at: String, kotlin: String, document: String?): List<String> =
        if (kotlin == document) emptyList() else listOf("$at: $document in the document, $kotlin in Kotlin")

    private fun enumValues(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val values = (schema["enum"] as? JsonArray ?: return emptyList())
            .filter { it != JsonNull }.map { it.jsonPrimitive.content }.toSet()
        val kotlin = descriptor.elementNames.toSet()
        return listOfNotNull(
            (values - kotlin).takeIf { it.isNotEmpty() }?.let { "$at: enum values $it in the document, not in Kotlin" },
            (kotlin - values).takeIf { it.isNotEmpty() }?.let { "$at: enum values $it in Kotlin, not in the document" },
        )
    }

    private fun objectDifferences(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
        if (OpenApiDocument.types(schema).let { it.isNotEmpty() && "object" !in it }) {
            return listOf("$at: ${OpenApiDocument.types(schema)} in the document, an object in Kotlin")
        }
        val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        val names = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        val missing = (properties.keys - names.keys).map { "$at.$it: in the document, not in Kotlin" }
        val model = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
        val extra = (names.keys - properties.keys)
            .filterNot { name -> "$model.$name".let { gap -> (gap in gaps).also { if (it) usedGaps += gap } } }
            .map { "$at.$it: in Kotlin, not in the document" }
        val mismatched = names.filterKeys { it in properties }.flatMap { (name, index) ->
            val documentRequired = name in required
            // The generator never lists a nullable property as required: a nullable Kotlin property without a default
            // (always written, null included) is what such a schema describes too.
            val element = descriptor.getElementDescriptor(index)
            val kotlinRequired = !descriptor.isElementOptional(index) && !element.isNullable
            val requiredness = if (documentRequired == kotlinRequired) {
                emptyList()
            } else {
                listOf("$at.$name: required=$documentRequired in the document, $kotlinRequired in Kotlin")
            }
            requiredness + differences(
                "$at.$name",
                descriptor.getElementDescriptor(index),
                properties.getValue(name).jsonObject,
            )
        }
        return missing + extra + mismatched
    }

    private fun sealedDifferences(at: String, descriptor: SerialDescriptor, schema: JsonObject): List<String> {
        val mapping = schema["discriminator"]?.jsonObject?.get("mapping")?.jsonObject
            ?: return listOf("$at: a sealed hierarchy in Kotlin, no discriminator in the document")
        val variants = descriptor.getElementDescriptor(1)
        val kotlin = (0 until variants.elementsCount).associate {
            variants.getElementName(it) to variants.getElementDescriptor(it)
        }
        val missing = (mapping.keys - kotlin.keys).map { "$at: variant $it in the document, not in Kotlin" }
        val extra = (kotlin.keys - mapping.keys).map { "$at: variant $it in Kotlin, not in the document" }
        val mismatched = kotlin.filterKeys { it in mapping }.flatMap { (name, variant) ->
            val variantSchema = document.resolve(JsonObject(mapOf("\$ref" to mapping.getValue(name))))
            // The variant's schema also lists the discriminator, which the Kotlin class does not declare.
            val properties = variantSchema["properties"]?.jsonObject.orEmpty() - DISCRIMINATOR
            val required = (variantSchema["required"] as? JsonArray).orEmpty().filter {
                it.jsonPrimitive.content != DISCRIMINATOR
            }
            val withoutDiscriminator = mapOf("properties" to JsonObject(properties), "required" to JsonArray(required))
            objectDifferences("$at<$name>", variant, JsonObject(variantSchema + withoutDiscriminator))
        }
        return missing + extra + mismatched
    }

    /** Compares a class with a schema once: models refer to each other, and some to themselves. */
    private fun once(descriptor: SerialDescriptor, schema: JsonObject, block: () -> List<String>): List<String> {
        val key = descriptor.serialName to (document.nameOf(schema) ?: schema.toString())
        return if (compared.add(key)) block() else emptyList()
    }

    /** [schema] without its `null` alternative, and whether it had one. */
    private fun unwrapNullable(schema: JsonObject): Pair<JsonObject, Boolean> {
        val anyOf = schema["anyOf"]?.jsonArray?.map { it.jsonObject }
        if (anyOf != null) {
            val others = anyOf.filter { OpenApiDocument.types(it) != listOf("null") }
            if (others.size == 1 && others.size < anyOf.size) return others.single() to true
        }
        val types = OpenApiDocument.types(schema)
        return schema to ("null" in types)
    }

    private fun JsonObject.isFreeObject(): Boolean {
        val values = this["additionalProperties"] as? JsonObject ?: return false
        return this["properties"] == null && document.resolve(values).keys.all { it == "title" || it == "description" }
    }

    private fun SerialDescriptor.unwrapInline(): SerialDescriptor =
        if (isInline) getElementDescriptor(0) else this

    private companion object {
        const val DISCRIMINATOR = "type"
    }
}
