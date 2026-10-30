package dev.ostrom.core.api

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
 * name, type, required, nullable), for each enum its values. `ContractTest` compares this description
 * with `api/contract/shapes.json`, derived from the schemas of `api/openapi.yaml`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object ContractDump {

    fun dump(serializers: List<KSerializer<*>>): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        val queue = ArrayDeque(serializers.map { it.descriptor })
        while (queue.isNotEmpty()) {
            val descriptor = queue.removeFirst()
            val name = schemaName(descriptor)
            if (name in out) continue
            out[name] = when (descriptor.kind) {
                SerialKind.ENUM -> buildJsonObject {
                    val names = (0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }
                    put("enum", JsonArray(names))
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
            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT -> {
                queue.addLast(descriptor)
                buildJsonObject { put("ref", schemaName(descriptor)) }
            }
            is PolymorphicKind, SerialKind.CONTEXTUAL, PrimitiveKind.CHAR -> JsonPrimitive("unsupported:$kind")
        }
    }
}
