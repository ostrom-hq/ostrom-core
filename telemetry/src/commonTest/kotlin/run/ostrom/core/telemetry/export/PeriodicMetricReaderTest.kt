// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.export

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.metrics.MetricData
import run.ostrom.core.telemetry.metrics.NumberPoint
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PeriodicMetricReaderTest {
    private val metric = MetricData.Sum("requests", "1", "Requests.", listOf(NumberPoint(Attributes.EMPTY, 0, 0, 1.0)))

    @Test
    fun metricsAreSentAtEachIntervalAndNothingIsSentWhenThereIsNothing() = runTest {
        val sentAt = mutableListOf<Long>()
        var metrics = emptyList<MetricData>()
        val reader = PeriodicMetricReader(
            collect = { metrics },
            encode = { it.size.toString().encodeToByteArray() },
            transport = { signal, _ ->
                assertEquals(Signal.METRICS, signal)
                sentAt += currentTime
                ExportResult.Success
            },
            interval = 10.seconds,
        )
        reader.start(backgroundScope)

        advanceTimeBy(10_001)
        metrics = listOf(metric)
        advanceTimeBy(20_000)

        assertEquals(listOf(20_000L, 30_000L), sentAt)
    }

    @Test
    fun aFailedExportIsRetriedOnceThenCounted() = runTest {
        val drops = mutableListOf<Pair<DropReason, Int>>()
        var calls = 0
        val reader = PeriodicMetricReader(
            collect = { listOf(metric) },
            encode = { byteArrayOf() },
            transport = { _, _ ->
                calls++
                ExportResult.Retryable()
            },
            onDrop = { reason, count -> drops += reason to count },
        )

        reader.exportNow()

        assertEquals(2, calls)
        assertEquals(listOf(DropReason.RETRIES_EXHAUSTED to 1), drops)
    }

    @Test
    fun theDefaultDropCallbackIsSilent() = runTest {
        PeriodicMetricReader({ listOf(metric) }, { byteArrayOf() }, { _, _ -> ExportResult.Rejected(400) }).exportNow()
    }

    @Test
    fun aThrowingGaugeLosesOneExportNeverTheLoop() = runTest {
        val drops = mutableListOf<Pair<DropReason, Int>>()
        var calls = 0
        var sent = 0
        val reader = PeriodicMetricReader(
            collect = { if (calls++ == 0) error("pool closed") else listOf(metric) },
            encode = { byteArrayOf() },
            transport = { _, _ ->
                sent++
                ExportResult.Success
            },
            interval = 10.seconds,
            onDrop = { reason, count -> drops += reason to count },
        )
        val job = reader.start(backgroundScope)

        advanceTimeBy(20_001)

        assertEquals(listOf(DropReason.COLLECT_FAILED to 1), drops)
        assertEquals(1, sent)
        assertTrue(job.isActive)
    }

    @Test
    fun aCancellationWhileCollectingIsNotADrop() = runTest {
        val drops = mutableListOf<DropReason>()
        val reader = PeriodicMetricReader(
            collect = { throw kotlinx.coroutines.CancellationException("stop") },
            encode = { byteArrayOf() },
            transport = { _, _ -> ExportResult.Success },
            onDrop = { reason, _ -> drops += reason },
        )

        assertFailsWith<kotlinx.coroutines.CancellationException> { reader.exportNow() }
        assertTrue(drops.isEmpty())
    }
}
