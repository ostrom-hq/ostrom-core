// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why telemetry was dropped; the bounded `reason` of the `ostrom.telemetry.dropped` counter (ENG-003). */
public enum class DropReason(public val wire: String) {
    /** The queue was full: the backend is slower than the traffic. */
    QUEUE_FULL("queue_full"),

    /** The backend refused the batch for good. */
    REJECTED("rejected"),

    /** The backend kept failing after every retry. */
    RETRIES_EXHAUSTED("retries_exhausted"),

    /** Offered after shutdown: the process is stopping. */
    CLOSED("closed"),

    /** Reading the instruments failed (a gauge callback threw): that export is lost, the next one is not. */
    COLLECT_FAILED("collect_failed"),
}

/**
 * Bounds of an exporter. Defaults follow the OpenTelemetry batch processor, with a shorter queue: the server must never
 * hold more than a few megabytes of telemetry when a backend is down.
 *
 * @property maxQueue Items waiting; beyond, new items are dropped and counted.
 * @property maxBatch Items per request.
 * @property interval The longest an item waits before its batch leaves.
 * @property maxRetries Retries of one batch after a retryable failure.
 * @property initialBackoff The first wait between retries, doubled each time.
 * @property maxBackoff The longest wait between retries, including one asked by the backend.
 */
public data class ExportConfig(
    val maxQueue: Int = 2_048,
    val maxBatch: Int = 512,
    val interval: Duration = 5.seconds,
    val maxRetries: Int = 3,
    val initialBackoff: Duration = 1.seconds,
    val maxBackoff: Duration = 30.seconds,
)

/**
 * Sends with retries, as the OTLP specification asks: exponential backoff, honoring the backend's
 * `Retry-After` up to [ExportConfig.maxBackoff], and never more than [ExportConfig.maxRetries] retries.
 *
 * @return Null when sent, or why it was dropped.
 */
internal suspend fun sendWithRetries(config: ExportConfig, send: suspend () -> ExportResult): DropReason? {
    var backoff = config.initialBackoff
    var retries = 0
    var result = send()
    while (result is ExportResult.Retryable && retries < config.maxRetries) {
        delay(minOf(result.retryAfter ?: backoff, config.maxBackoff))
        backoff *= 2
        retries++
        result = send()
    }
    return when (result) {
        ExportResult.Success -> null
        is ExportResult.Rejected -> DropReason.REJECTED
        is ExportResult.Retryable -> DropReason.RETRIES_EXHAUSTED
    }
}

/**
 * Queues items (spans, logs, product events) without blocking the caller, and sends them in batches from one
 * coroutine.
 *
 * [offer] is what request handlers call: a non-suspending `trySend` on a bounded channel, so a slow or dead backend
 * costs the request path nothing but a counted drop.
 *
 * @param encode Encodes one batch.
 * @param send Sends one encoded batch (an [OtlpTransport] for a signal, or the PostHog client).
 * @param config Bounds and timings.
 * @param onDrop Counts drops; called with the reason and the number of items.
 */
public class BatchExporter<T>(
    private val encode: (List<T>) -> ByteArray,
    private val send: suspend (ByteArray) -> ExportResult,
    private val config: ExportConfig = ExportConfig(),
    private val onDrop: (DropReason, Int) -> Unit = { _, _ -> },
) {
    private val queue = Channel<T>(capacity = config.maxQueue)

    /**
     * Queues one item.
     *
     * @return False when the queue was full or closed and the item was dropped.
     */
    public fun offer(item: T): Boolean {
        val result = queue.trySend(item)
        if (result.isFailure) onDrop(if (result.isClosed) DropReason.CLOSED else DropReason.QUEUE_FULL, 1)
        return result.isSuccess
    }

    /**
     * Starts sending in [scope]. Cancelling the scope stops sending at once; [shutdown] drains first.
     *
     * @return The sending job.
     */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val first = queue.receiveCatching().getOrNull() ?: break
            val batch = ArrayList<T>(config.maxBatch)
            batch += first
            withTimeoutOrNull(config.interval) {
                while (batch.size < config.maxBatch) {
                    batch += queue.receiveCatching().getOrNull() ?: break
                }
            }
            val body = encode(batch)
            sendWithRetries(config) { send(body) }?.let { onDrop(it, batch.size) }
        }
    }

    /**
     * Stops accepting items; the sending job sends what is queued and then completes. Join the job, with a deadline,
     * to wait for it.
     */
    public fun shutdown() {
        queue.close()
    }
}
