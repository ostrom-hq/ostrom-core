// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.CloudEvent
import run.ostrom.core.api.models.CloudEventPage
import run.ostrom.core.api.models.CloudSubagentList

/**
 * The transcript of a conversation session (`runners.cloud.conversation.v1`, TeamCode's cloud transcript): pages in
 * both directions, the live stream, and the subagents (`runners.cloud.transcript.feeds.v1`). Reached through
 * [OstromClient.cloudTranscripts].
 */
public class CloudTranscriptsClient internal constructor(private val transport: Transport) {
    /**
     * A page of a conversation session's transcript in ascending order (`GET .../events`,
     * `runners.cloud.conversation.v1`): after [after] or before [before], [limit] events (1 to 200), one [feed]
     * (`main` or a subagent's, `runners.cloud.transcript.feeds.v1`) or every event when null.
     */
    public suspend fun events(
        id: String,
        after: Long? = null,
        before: Long? = null,
        limit: Int? = null,
        feed: String? = null,
    ): CloudEventPage = transport.json(
        HttpMethod.Get,
        "${path(id)}/events",
        query(
            "after" to after?.toString(),
            "before" to before?.toString(),
            "limit" to limit?.toString(),
            "feed" to feed,
        ),
    )

    /**
     * Follows a conversation session's transcript after [after] (`GET .../events/stream`), calling [onEvent] for each
     * event in order. Returns [StreamEnd.ENDED] once the session is terminated, [StreamEnd.DENIED] when the caller
     * lost access; throws [OstromException] with status `0` when the stream closes (reconnect from the last `seq`
     * handled) and with type [OstromClient.MALFORMED_EVENT] on an unreadable event.
     */
    public suspend fun followEvents(
        id: String,
        after: Long?,
        onOpen: suspend () -> Unit = {},
        onEvent: suspend (CloudEvent) -> Unit,
    ): StreamEnd {
        require(after == null || after >= 0) { "The cursor cannot be negative." }
        return transport.follow(StreamRoute("${path(id)}/events/stream", endEvent = "end"), after, onOpen) { _, data ->
            if (data.isNotEmpty()) onEvent(decodeEvent(data))
        }
    }

    /** The session's subagents and their state (`GET .../agents`, `runners.cloud.transcript.feeds.v1`). */
    public suspend fun agents(id: String): CloudSubagentList = transport.json(HttpMethod.Get, "${path(id)}/agents")

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"
}

private fun decodeEvent(data: String): CloudEvent = try {
    OstromJson.decodeFromString(CloudEvent.serializer(), data)
} catch (_: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException; its message quotes the payload, so it is not kept.
    throw OstromException(0, OstromClient.MALFORMED_EVENT, "The event stream sent a malformed event.")
}
