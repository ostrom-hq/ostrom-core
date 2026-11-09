// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every model reads its complete form and its minimal form (required properties only), writes back what it read, and
 * refuses a document missing any required property. The documents are built from each model's own descriptor.
 */
class ModelRoundTripTest {

    @Test
    fun everyModelReadsItsCompleteFormAndWritesBackWhatItRead() {
        for (model in apiModels) {
            roundTrip(model, sample(model.descriptor, optional = true))
        }
    }

    @Test
    fun everyModelReadsItsMinimalFormAndWritesBackWhatItRead() {
        for (model in apiModels) {
            roundTrip(model, sample(model.descriptor, optional = false))
        }
    }

    @Test
    fun everyModelRefusesADocumentMissingARequiredProperty() {
        for (model in apiModels) {
            val minimal = sample(model.descriptor, optional = false) as? JsonObject ?: continue
            for (name in minimal.keys) {
                assertFailsWith<SerializationException>("${model.descriptor.serialName} without $name") {
                    OstromJson.decodeFromJsonElement(model, JsonObject(minimal - name))
                }
            }
        }
    }

    private fun roundTrip(model: KSerializer<*>, document: JsonElement) {
        @Suppress("UNCHECKED_CAST")
        val serializer = model as KSerializer<Any?>
        val decoded = OstromJson.decodeFromJsonElement(serializer, document)
        val written = OstromJson.encodeToJsonElement(serializer, decoded)
        assertEquals(decoded, OstromJson.decodeFromJsonElement(serializer, written), serializer.descriptor.serialName)
    }

    /** A valid document for [descriptor]: every property when [optional], the required ones otherwise. */
    private fun sample(descriptor: SerialDescriptor, optional: Boolean, depth: Int = 0): JsonElement {
        val deeper = depth < MAX_SAMPLE_DEPTH
        return when (descriptor.kind) {
            StructureKind.CLASS -> JsonObject(
                (0 until descriptor.elementsCount)
                    .filter { !descriptor.isElementOptional(it) || (optional && deeper) }
                    .associate {
                        descriptor.getElementName(it) to
                            sample(descriptor.getElementDescriptor(it), optional, depth + 1)
                    },
            )
            StructureKind.LIST -> JsonArray(
                if (deeper) listOf(sample(descriptor.getElementDescriptor(0), optional, depth + 1)) else emptyList(),
            )
            StructureKind.MAP -> JsonObject(
                if (deeper) {
                    mapOf("key" to sample(descriptor.getElementDescriptor(1), optional, depth + 1))
                } else {
                    mapOf()
                },
            )
            SerialKind.ENUM -> JsonPrimitive(descriptor.getElementName(0))
            PrimitiveKind.BOOLEAN -> JsonPrimitive(true)
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> JsonPrimitive(1)
            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive(1.5)
            // strings, and the free JSON values (JsonElement) the models carry
            else -> JsonPrimitive("text")
        }
    }

    private companion object {
        const val MAX_SAMPLE_DEPTH = 4
    }
}
