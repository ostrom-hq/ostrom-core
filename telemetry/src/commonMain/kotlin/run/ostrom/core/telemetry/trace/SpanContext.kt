// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.trace

/**
 * The identity of one span inside a trace, as the W3C Trace Context carries it between processes: the CLI, the server
 * and the inference gateway share [traceIdHigh]/[traceIdLow] for one user action.
 *
 * @property traceIdHigh The first 8 bytes of the 16-byte trace id.
 * @property traceIdLow The last 8 bytes of the trace id.
 * @property spanId The 8-byte span id.
 * @property sampled True when the trace is recorded and exported.
 */
public data class SpanContext(
    val traceIdHigh: Long,
    val traceIdLow: Long,
    val spanId: Long,
    val sampled: Boolean,
) {
    /** The trace id as 32 lower-case hex characters. */
    public val traceIdHex: String get() = Hex.long(traceIdHigh) + Hex.long(traceIdLow)

    /** The span id as 16 lower-case hex characters. */
    public val spanIdHex: String get() = Hex.long(spanId)

    /** True when neither id is all zeros, which W3C forbids. */
    public val isValid: Boolean get() = (traceIdHigh != 0L || traceIdLow != 0L) && spanId != 0L

    /**
     * Formats the `traceparent` header (W3C Trace Context level 1, version 00).
     *
     * @return For example `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`.
     */
    public fun toTraceparent(): String = "00-$traceIdHex-$spanIdHex-${if (sampled) "01" else "00"}"

    /** Parsing of the header. */
    public companion object {
        /** The header name, lower case as HTTP/2 requires. */
        public const val TRACEPARENT_HEADER: String = "traceparent"

        private const val SAMPLED_FLAG = 1
        private const val HEX_RADIX = 16

        // version-traceid-spanid-flags, then anything after a dash for a future version (W3C level 1, section 3.2).
        private val TRACEPARENT =
            Regex("^([0-9a-f]{2})-([0-9a-f]{16})([0-9a-f]{16})-([0-9a-f]{16})-([0-9a-f]{2})(-.*)?$")

        /**
         * Parses a `traceparent` header. A malformed value starts a new trace instead of failing the request, as the
         * specification asks; a future version is read for its known fields.
         *
         * @param header The header value, possibly null when the caller sent none.
         * @return The remote span context, or null when absent or invalid.
         */
        public fun parseTraceparent(header: String?): SpanContext? {
            val groups = header?.let { TRACEPARENT.matchEntire(it.trim().lowercase()) }?.groupValues
            val context = if (groups != null && isAcceptable(version = groups[1], rest = groups[6])) {
                SpanContext(
                    traceIdHigh = groups[2].toULong(HEX_RADIX).toLong(),
                    traceIdLow = groups[3].toULong(HEX_RADIX).toLong(),
                    spanId = groups[4].toULong(HEX_RADIX).toLong(),
                    sampled = (groups[5].toInt(HEX_RADIX) and SAMPLED_FLAG) == SAMPLED_FLAG,
                )
            } else {
                null
            }
            return context?.takeIf { it.isValid }
        }

        // Version ff is forbidden; version 00 has nothing after the flags.
        private fun isAcceptable(version: String, rest: String): Boolean =
            version != "ff" && (version != "00" || rest.isEmpty())
    }
}

/** Lower-case hexadecimal helpers for ids. */
internal object Hex {
    private const val DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0xFL
    private const val LONG_NIBBLES = 16

    /** Formats [value] as 16 hex characters, big-endian. */
    fun long(value: Long): String {
        val chars = CharArray(LONG_NIBBLES)
        for (index in 0 until LONG_NIBBLES) {
            val shift = (LONG_NIBBLES - 1 - index) * NIBBLE_BITS
            chars[index] = DIGITS[((value ushr shift) and NIBBLE_MASK).toInt()]
        }
        return chars.concatToString()
    }
}
