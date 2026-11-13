// SPDX-License-Identifier: Apache-2.0
// The caller-wide feed of teamcode's followTeamEvents (https://github.com/nathanfallet/teamcode, Apache-2.0,
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/TeamCodeClient.kt).
package run.ostrom.core.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import run.ostrom.core.api.OstromJson

/**
 * One event of the organization's feed: its type (the SSE `event:` name) and its JSON data. The feed's event schemas
 * are not in the contract yet (`streamEvents` is planned), so the data is handed over as JSON.
 *
 * @property type The event type; an open list, so an unknown type reaches the caller like the others.
 * @property data The event's data.
 */
public class StreamEvent(public val type: String, public val data: JsonElement) {
    override fun toString(): String = "StreamEvent($type)"
}

/** The shape of an event type: what `types` may name. */
private val EVENT_TYPE = Regex("[A-Za-z0-9_.:-]{1,128}")

/** The organization's live events (`GET /api/v1/events`, planned). Reached through [OstromClient.events]. */
public class EventsClient internal constructor(private val transport: Transport) {
    /**
     * Follows the feed after [after], calling [onEvent] for each event in order. [after] goes in `Last-Event-ID`:
     * `null` starts with what happens from now on, `0` replays every retained event. [types] keeps only those event
     * types (at most 64), sent as `?types=a,b`. [onGap] is called with the oldest retained sequence when events after
     * [after] were purged, before the events that follow, so the caller reloads what it shows. [onOpen] runs once the
     * server accepted the stream, before any frame.
     *
     * Returns [StreamEnd.DENIED] when the server stopped accepting the credential; the feed never ends otherwise.
     * Throws [OstromException] with status `0` when the stream closes (reconnect from the last sequence handled, which
     * the event's data carries), with type [OstromClient.MALFORMED_EVENT] on an unreadable event (stop: reconnecting
     * would replay it), with the server's status and type on a refusal; a network failure propagates as the engine's
     * exception.
     */
    public suspend fun follow(
        after: Long?,
        types: Set<String> = emptySet(),
        onGap: suspend (oldestSeq: Long) -> Unit = {},
        onOpen: suspend () -> Unit = {},
        onEvent: suspend (StreamEvent) -> Unit,
    ): StreamEnd {
        require(after == null || after >= 0) { "The cursor cannot be negative." }
        require(types.size <= MAX_TYPES && types.all { EVENT_TYPE.matches(it) && it != GAP && it != "denied" }) {
            "Use at most $MAX_TYPES event types, without gap or denied (always sent)."
        }
        val query = if (types.isEmpty()) emptyList() else listOf("types" to types.sorted().joinToString(","))
        return transport.follow(StreamRoute("/api/v1/events", query), after, onOpen) { type, data ->
            when {
                type == GAP -> onGap(decode(data) { OstromJson.decodeFromJsonElement(Gap.serializer(), it) }.oldestSeq)
                data.isNotEmpty() -> onEvent(StreamEvent(type, decode(data) { it }))
            }
        }
    }

    /** `{"oldestSeq":120}`: the oldest sequence the server still holds. */
    @Serializable
    private class Gap(val oldestSeq: Long)

    private companion object {
        const val MAX_TYPES = 64
        const val GAP = "gap"

        fun <T> decode(data: String, read: (JsonElement) -> T): T = try {
            read(OstromJson.parseToJsonElement(data))
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; its message quotes the payload, so it is not
            // kept as a cause and the exception stays safe to print.
            throw OstromException(0, OstromClient.MALFORMED_EVENT, "The event stream sent a malformed event.")
        }
    }
}
