// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpanContextTest {
    // The example of the W3C Trace Context specification, section 3.2.
    private val example = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

    @Test
    fun theSpecificationExampleIsParsed() {
        val context = SpanContext.parseTraceparent(example)

        assertEquals(SpanContext(0x4bf92f3577b34da6L, -0x5c316d62f1f1b8caL, 0x00f067aa0ba902b7L, true), context)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context?.traceIdHex)
        assertEquals("00f067aa0ba902b7", context?.spanIdHex)
    }

    @Test
    fun formattingThenParsingGivesTheSameContext() {
        val context = SpanContext(-1L, 1L, Long.MIN_VALUE, sampled = false)

        assertEquals("00-ffffffffffffffff0000000000000001-8000000000000000-00", context.toTraceparent())
        assertEquals(context, SpanContext.parseTraceparent(context.toTraceparent()))
        assertEquals(example, SpanContext.parseTraceparent(example)?.toTraceparent())
    }

    @Test
    fun surroundingSpacesAndUpperCaseAreTolerated() {
        assertEquals(SpanContext.parseTraceparent(example), SpanContext.parseTraceparent("  ${example.uppercase()} "))
    }

    @Test
    fun onlyTheSampledBitOfTheFlagsCounts() {
        assertEquals(true, SpanContext.parseTraceparent(example.dropLast(2) + "03")?.sampled)
        assertEquals(false, SpanContext.parseTraceparent(example.dropLast(2) + "02")?.sampled)
    }

    @Test
    fun aFutureVersionIsReadForItsKnownFields() {
        val context = SpanContext.parseTraceparent("cc" + example.drop(2) + "-what-comes-next")

        assertEquals(SpanContext.parseTraceparent(example), context)
        assertEquals(SpanContext.parseTraceparent(example), SpanContext.parseTraceparent("cc" + example.drop(2)))
    }

    @Test
    fun malformedHeadersStartANewTrace() {
        val malformed = listOf(
            null,
            "",
            "garbage",
            example + "-extra",
            "ff" + example.drop(2),
            example.replace('-', '_'),
            example.dropLast(1),
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4bf92f3577b34da6a3ce929d0e0e473g-00f067aa0ba902b7-01",
        )

        malformed.forEach { header -> assertNull(SpanContext.parseTraceparent(header), "Rejected: $header") }
    }

    @Test
    fun validityNeedsANonZeroTraceIdAndSpanId() {
        assertTrue(SpanContext(0, 1, 1, true).isValid)
        assertTrue(SpanContext(1, 0, 1, true).isValid)
        assertFalse(SpanContext(0, 0, 1, true).isValid)
        assertFalse(SpanContext(1, 1, 0, true).isValid)
    }
}
