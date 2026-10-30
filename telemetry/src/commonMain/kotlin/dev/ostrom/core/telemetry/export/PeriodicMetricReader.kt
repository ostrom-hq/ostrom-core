package dev.ostrom.core.telemetry.export

import dev.ostrom.core.telemetry.metrics.MetricData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Collects the meter every [interval] and sends the cumulative state. A lost export loses nothing for good: the next
 * one carries the same totals.
 *
 * @param collect Reads every instrument.
 * @param encode Encodes the metrics.
 * @param transport Sends them.
 * @param interval Time between exports (Grafana Cloud's default scrape is 60 s; 30 s gives finer alerting).
 * @param config Retry bounds.
 * @param onDrop Counts drops.
 */
public class PeriodicMetricReader(
    private val collect: () -> List<MetricData>,
    private val encode: (List<MetricData>) -> ByteArray,
    private val transport: OtlpTransport,
    private val interval: Duration = 30.seconds,
    private val config: ExportConfig = ExportConfig(maxRetries = 1),
    private val onDrop: (DropReason, Int) -> Unit = { _, _ -> },
) {
    /** @return The job exporting every [interval] until [scope] is cancelled. */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        // delay() throws when the scope is cancelled, which ends the loop.
        while (true) {
            delay(interval)
            exportNow()
        }
    }

    /**
     * Collects and sends once; called by the loop and at shutdown so the last totals are not lost. A gauge callback
     * that throws loses this export (counted as [DropReason.COLLECT_FAILED]), never the loop nor the process.
     */
    public suspend fun exportNow() {
        val batch = try {
            collect().takeIf { it.isNotEmpty() }?.let { metrics -> metrics.size to encode(metrics) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onDrop(DropReason.COLLECT_FAILED, 1)
            null
        }
        if (batch != null) {
            val (count, body) = batch
            sendWithRetries(config) { transport.send(Signal.METRICS, body) }?.let { onDrop(it, count) }
        }
    }
}
