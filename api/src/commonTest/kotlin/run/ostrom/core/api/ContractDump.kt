package run.ostrom.core.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Describes the JSON shape of each contract model from its serial descriptor: for each class its properties (JSON
 * name, type, required, nullable), for each enum its values, for each sealed hierarchy the discriminator and the schema
 * of each value (a variant is named after its hierarchy and its serial name: `TodoCheck` + `pr_merged` is
 * `TodoCheckPrMerged`). `ContractTest` compares this description with `api/contract/shapes.json`, derived from the
 * schemas of `api/openapi.yaml`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ContractDump {

    private const val DISCRIMINATOR = "type"

    fun dump(serializers: List<KSerializer<*>>): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        // A variant is queued with its schema name; any other descriptor is named after its serial name.
        val variants = ArrayDeque<Pair<SerialDescriptor, String>>()
        val queue = ArrayDeque(serializers.map { it.descriptor })
        while (queue.isNotEmpty() || variants.isNotEmpty()) {
            val (descriptor, name) = variants.removeFirstOrNull()
                ?: queue.removeFirst().let { it to schemaName(it) }
            if (name in out) continue
            out[name] = when (descriptor.kind) {
                SerialKind.ENUM -> buildJsonObject {
                    val names = (0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }
                    put("enum", JsonArray(names))
                }
                PolymorphicKind.SEALED -> buildJsonObject {
                    val values = descriptor.getElementDescriptor(1)
                    put("oneOf", buildJsonObject {
                        put("property", DISCRIMINATOR)
                        put("mapping", JsonObject((0 until values.elementsCount).associate { i ->
                            val variant = values.getElementDescriptor(i)
                            val variantName = name + pascal(variant.serialName)
                            variants.addLast(variant to variantName)
                            values.getElementName(i) to JsonPrimitive(variantName)
                        }))
                    })
                }
                else -> buildJsonObject {
                    put("properties", JsonObject((0 until descriptor.elementsCount).associate { i ->
                        val element = descriptor.getElementDescriptor(i)
                        descriptor.getElementName(i) to buildJsonObject {
                            put("type", typeOf(element, queue))
                            put("required", !descriptor.isElementOptional(i))
                            put("nullable", element.isNullable)
                        }
                    }))
                }
            }
        }
        return JsonObject(out)
    }

    private fun pascal(serialName: String): String =
        serialName.split('_').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

    private fun schemaName(descriptor: SerialDescriptor): String =
        descriptor.serialName.removeSuffix("?").substringAfterLast('.')

    private fun typeOf(descriptor: SerialDescriptor, queue: ArrayDeque<SerialDescriptor>): JsonElement {
        if (descriptor.serialName.startsWith("kotlinx.serialization.json.Json")) return JsonPrimitive("any")
        return when (val kind = descriptor.kind) {
            PrimitiveKind.STRING -> JsonPrimitive("string")
            PrimitiveKind.BOOLEAN -> JsonPrimitive("boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> JsonPrimitive("integer")
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive("number")
            StructureKind.LIST -> buildJsonObject { put("array", typeOf(descriptor.getElementDescriptor(0), queue)) }
            StructureKind.MAP -> buildJsonObject { put("map", typeOf(descriptor.getElementDescriptor(1), queue)) }
            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                queue.addLast(descriptor)
                buildJsonObject { put("ref", schemaName(descriptor)) }
            }
            PolymorphicKind.OPEN, SerialKind.CONTEXTUAL, PrimitiveKind.CHAR -> JsonPrimitive("unsupported:$kind")
        }
    }
}
