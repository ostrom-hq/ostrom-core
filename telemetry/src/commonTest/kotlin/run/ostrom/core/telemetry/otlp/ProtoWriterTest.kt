// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.otlp

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtoWriterTest {
    private fun bytes(write: ProtoWriter.() -> Unit): String =
        ProtoWriter(initialCapacity = 1).apply(write).toByteArray().joinToString(" ") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    @Test
    fun varintsUseSevenBitGroupsLittleEndian() {
        // The examples of the protobuf encoding guide: 150 is 96 01.
        assertEquals("08 96 01", bytes { varint(1, 150) })
        assertEquals("10 01", bytes { bool(2, true) })
        assertEquals("08 ff ff ff ff ff ff ff ff ff 01", bytes { varint(1, -1) })
    }

    @Test
    fun defaultValuesAreSkippedUnlessPresenceMatters() {
        assertEquals("", bytes {
            varint(1, 0)
            bool(2, false)
            fixed64(3, 0)
            fixed32(4, 0)
            string(5, "")
            packedFixed64(6, emptyList())
            packedDouble(7, emptyList())
        })
        assertEquals("08 00 21 00 00 00 00 00 00 00 00 0a 00", bytes {
            varintAlways(1, 0)
            doubleAlways(4, 0.0)
            stringAlways(1, "")
        })
    }

    @Test
    fun fixedWidthFieldsAreLittleEndian() {
        assertEquals("39 01 02 03 04 05 06 07 08", bytes { fixed64(7, 0x0807060504030201) })
        assertEquals("85 01 01 00 00 00", bytes { fixed32(16, 1) })
        // 1.0 is 0x3FF0000000000000.
        assertEquals("21 00 00 00 00 00 00 f0 3f", bytes { doubleAlways(4, 1.0) })
    }

    @Test
    fun lengthDelimitedFieldsCarryTheirLength() {
        assertEquals("2a 02 c3 a9", bytes { string(5, "é") })
        assertEquals("0a 02 01 02", bytes { bytes(1, byteArrayOf(1, 2)) })
        assertEquals("32 10 01 00 00 00 00 00 00 00 02 00 00 00 00 00 00 00", bytes { packedFixed64(6, listOf(1, 2)) })
        assertEquals("3a 08 00 00 00 00 00 00 f0 3f", bytes { packedDouble(7, listOf(1.0)) })
    }

    @Test
    fun nestedMessagesArePrefixedWithTheirSizeEvenWhenEmpty() {
        assertEquals("0a 05 12 03 08 96 01 1a 00", bytes {
            message(1) { message(2) { varint(1, 150) } }
            message(3) { }
        })
    }
}
