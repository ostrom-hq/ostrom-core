package dev.ostrom.core.telemetry.export

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.core.telemetry.metrics.NumberPoint
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
