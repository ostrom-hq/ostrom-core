// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializer of an enum shared with clients that may lag behind the server (CONVENTIONS § 5): a value this version
 * does not know decodes to [unknown] instead of failing the whole message, so an older CLI or app keeps working when
 * the server adds a status. [unknown] itself is never written: sending it is a bug, so encoding it throws.
 *
 * The descriptor is an enum descriptor of the known values only: the contract test sees the values of the OpenAPI
 * schema, without the fallback.
 *
 * @param serialName Serial name of the enum (its qualified name).
 * @param entries Every value of the enum, [unknown] included.
 * @param unknown The fallback value.
 * @param valueOf The JSON value of a known entry.
 */
@OptIn(InternalSerializationApi::class)
public open class UnknownFallbackEnumSerializer<T : Enum<T>>(
    serialName: String,
    entries: List<T>,
    private val unknown: T,
    private val valueOf: (T) -> String,
) : KSerializer<T> {

    private val known = entries.filter { it != unknown }
    private val byValue = known.associateBy(valueOf)

    override val descriptor: SerialDescriptor = buildSerialDescriptor(serialName, SerialKind.ENUM) {
        for (entry in known) {
            val value = valueOf(entry)
            element(value, buildSerialDescriptor("$serialName.$value", StructureKind.OBJECT))
        }
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (value == unknown) throw SerializationException("$value stands for a value this version does not know")
        encoder.encodeString(valueOf(value))
    }

    override fun deserialize(decoder: Decoder): T = byValue[decoder.decodeString()] ?: unknown
}
