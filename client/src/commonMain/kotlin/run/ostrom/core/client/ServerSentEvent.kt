// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0):
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/ServerSentEvents.kt.
package run.ostrom.core.client

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readUTF8Line

/** One dispatched server-sent event: its `event:` name (null when absent or empty) and its joined `data:` lines. */
internal class ServerSentEvent(val event: String?, val data: String)

/**
 * Reads the SSE frames of [channel] and hands each dispatched event to [onEvent] until it returns `false`. Comment
 * lines (keep-alives), `id:` and `retry:` are skipped (callers resume from the sequence in the decoded data); a
 * frame with neither a name nor data is not dispatched. Lines are bounded by [maxLine] characters and the whole data
 * of one event by [maxData] characters. Throws [OstromException] with status `0` when the stream closes, so the
 * caller reconnects from the last sequence it handled, and with type [OstromClient.MALFORMED_EVENT] when it breaks a
 * bound, which a reconnection would replay. Invalid UTF-8 becomes replacement characters; transport failures
 * propagate as the engine reports them.
 */
internal suspend fun readServerSentEvents(
    channel: ByteReadChannel,
    maxLine: Int = MAX_SSE_LINE,
    maxData: Int = MAX_SSE_DATA,
    onEvent: suspend (ServerSentEvent) -> Boolean,
) {
    var event: String? = null
    val data = StringBuilder()
    var hasData = false
    while (true) {
        val line = readLine(channel, maxLine)
        val field = line.substringBefore(':')
        // "field: value" loses exactly one leading space after the colon; a line without a colon is a field, no value.
        val value = line.substringAfter(':', "").removePrefix(" ")
        when {
            line.isEmpty() -> {
                if ((event != null || hasData) && !onEvent(ServerSentEvent(event, data.toString()))) return
                event = null
                data.clear()
                hasData = false
            }
            line.startsWith(":") -> Unit
            field == "event" -> event = value.trim().ifEmpty { null }
            field == "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
                if (data.length > maxData) {
                    throw OstromException(0, OstromClient.MALFORMED_EVENT, "An event of the stream exceeds the limit.")
                }
            }
        }
    }
}

/** The next line of [channel]; throws when it is over [maxLine] characters or when the stream closed. */
private suspend fun readLine(channel: ByteReadChannel, maxLine: Int): String = try {
    channel.readUTF8Line(maxLine)
} catch (_: TooLongLineException) {
    // Reconnecting replays the same line from the same cursor: as unreadable as an over-long event.
    throw OstromException(0, OstromClient.MALFORMED_EVENT, "A line of the event stream exceeds the limit.")
} ?: throw OstromException(0, null, "The event stream closed.")

/** The longest line of an event stream, in characters. */
internal const val MAX_SSE_LINE: Int = 256 * 1024

/** The largest data of one event, in characters. */
internal const val MAX_SSE_DATA: Int = 1024 * 1024
