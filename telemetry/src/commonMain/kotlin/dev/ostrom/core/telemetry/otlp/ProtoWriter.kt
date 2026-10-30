// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.otlp

/**
 * A minimal Protocol Buffers writer: the four wire types OTLP uses, written into a growable array. Nested messages are
 * written into a child writer then copied with their length, which keeps the code simple; a batch is a few kilobytes
 * every few seconds, far from any hot path.
 *
 * Fields equal to their default (0, empty) are skipped, as protobuf encoders do.
 */
// One small function per protobuf wire shape: splitting the writer would only scatter the format.
@Suppress("TooManyFunctions")
internal class ProtoWriter(initialCapacity: Int = INITIAL_CAPACITY) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    /** @return The bytes written so far. */
    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** Writes a varint field (int32, int64, enum, bool) unless it is 0. */
    fun varint(field: Int, value: Long) {
        if (value != 0L) varintAlways(field, value)
    }

    /** Writes a varint field even when it is 0: a oneof member, where presence matters. */
    fun varintAlways(field: Int, value: Long) {
        tag(field, WIRE_VARINT)
        rawVarint(value)
    }

    /** Writes a bool field unless it is false. */
    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1L else 0L)

    /** Writes a fixed64 or sfixed64 field unless it is 0. */
    fun fixed64(field: Int, value: Long) {
        if (value == 0L) return
        tag(field, WIRE_FIXED64)
        rawFixed64(value)
    }

    /** Writes a double field even when it is 0: an `optional` field, or a oneof member, where presence matters. */
    fun doubleAlways(field: Int, value: Double) {
        tag(field, WIRE_FIXED64)
        rawFixed64(value.toRawBits())
    }

    /** Writes a fixed32 field unless it is 0. */
    fun fixed32(field: Int, value: Int) {
        if (value == 0) return
        tag(field, WIRE_FIXED32)
        for (index in 0 until Int.SIZE_BYTES) append((value ushr (index * Byte.SIZE_BITS)).toByte())
    }

    /** Writes a string field unless it is empty. */
    fun string(field: Int, value: String) {
        if (value.isNotEmpty()) stringAlways(field, value)
    }

    /** Writes a string field even when empty: a oneof member, where presence matters. */
    fun stringAlways(field: Int, value: String) = bytes(field, value.encodeToByteArray())

    /** Writes a bytes field, even when empty (callers skip empty ids themselves). */
    fun bytes(field: Int, value: ByteArray) {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        append(value)
    }

    /** Writes a packed repeated fixed64 field unless it is empty. */
    fun packedFixed64(field: Int, values: List<Long>) {
        if (values.isEmpty()) return
        tag(field, WIRE_LENGTH)
        rawVarint((values.size * Long.SIZE_BYTES).toLong())
        values.forEach { rawFixed64(it) }
    }

    /** Writes a packed repeated double field unless it is empty. */
    fun packedDouble(field: Int, values: List<Double>) = packedFixed64(field, values.map { it.toRawBits() })

    /** Writes an embedded message, always (an empty message still marks presence). */
    fun message(field: Int, write: ProtoWriter.() -> Unit) {
        val child = ProtoWriter().apply(write)
        tag(field, WIRE_LENGTH)
        rawVarint(child.size.toLong())
        append(child.buffer, child.size)
    }

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl TAG_SHIFT) or wireType).toLong())

    private fun rawVarint(value: Long) {
        var remaining = value
        while (remaining and VARINT_PAYLOAD.inv() != 0L) {
            append(((remaining and VARINT_PAYLOAD) or VARINT_CONTINUE).toByte())
            remaining = remaining ushr VARINT_BITS
        }
        append(remaining.toByte())
    }

    private fun rawFixed64(value: Long) {
        for (index in 0 until Long.SIZE_BYTES) append((value ushr (index * Byte.SIZE_BITS)).toByte())
    }

    private fun append(byte: Byte) {
        ensure(1)
        buffer[size++] = byte
    }

    private fun append(bytes: ByteArray, length: Int = bytes.size) {
        ensure(length)
        bytes.copyInto(buffer, size, 0, length)
        size += length
    }

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
        const val TAG_SHIFT = 3
        const val VARINT_BITS = 7
        const val VARINT_PAYLOAD = 0x7FL
        const val VARINT_CONTINUE = 0x80L
    }
}
