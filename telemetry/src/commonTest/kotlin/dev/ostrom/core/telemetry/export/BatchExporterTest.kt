// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.export

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BatchExporterTest {
    private val sent = mutableListOf<Pair<Long, String>>()
    private val drops = mutableListOf<Pair<DropReason, Int>>()

    private fun encode(items: List<String>) = items.joinToString(",").encodeToByteArray()

    @Test
    fun aFullBatchLeavesAtOnceAndAPartialOneAfterTheInterval() = runTest {
        val exporter = BatchExporter(
            encode = ::encode,
            send = { body -> sent += currentTime to body.decodeToString(); ExportResult.Success },
            config = ExportConfig(maxBatch = 2, interval = 5.seconds),
        )
        val job = exporter.start(backgroundScope)
        listOf("a", "b", "c").forEach { assertTrue(exporter.offer(it)) }
        runCurrent()

        assertEquals(listOf(0L to "a,b"), sent)
        advanceTimeBy(5_001)
        assertEquals(listOf(0L to "a,b", 5_000L to "c"), sent)
        assertTrue(job.isActive)
    }

    @Test
    fun aFullQueueDropsAndCountsTheItem() = runTest {
        val exporter = BatchExporter(::encode, { ExportResult.Success }, ExportConfig(maxQueue = 1)) { reason, count ->
            drops += reason to count
        }

        assertTrue(exporter.offer("kept"))
        assertFalse(exporter.offer("dropped"))
        assertEquals(listOf(DropReason.QUEUE_FULL to 1), drops)
    }

    @Test
    fun shutdownSendsWhatIsQueuedThenTheJobCompletes() = runTest {
        val exporter = BatchExporter(::encode, { body ->
            sent += currentTime to body.decodeToString()
            ExportResult.Success
        }) { reason, count -> drops += reason to count }
        val job = exporter.start(backgroundScope)
        exporter.offer("last")
        exporter.shutdown()
        job.join()

        assertEquals(listOf(0L to "last"), sent)
        assertFalse(exporter.offer("after"))
        assertTrue(job.isCompleted)
        assertEquals(listOf(DropReason.CLOSED to 1), drops)
    }

    @Test
    fun aRejectedBatchIsDroppedWithoutRetry() = runTest {
        var calls = 0
        val exporter = BatchExporter(
            encode = ::encode,
            send = {
                calls++
                ExportResult.Rejected(400)
            },
            onDrop = { reason, count -> drops += reason to count },
        )
        exporter.start(backgroundScope)
        exporter.offer("a")
        exporter.offer("b")
        exporter.shutdown()
        advanceTimeBy(10_000)

        assertEquals(1, calls)
        assertEquals(listOf(DropReason.REJECTED to 2), drops)
    }

    @Test
    fun retriesBackOffExponentiallyThenGiveUp() = runTest {
        val attempts = mutableListOf<Long>()
        val config = ExportConfig(maxRetries = 3, initialBackoff = 1.seconds, maxBackoff = 3.seconds)
        val dropped = sendWithRetries(config) {
            attempts += currentTime
            ExportResult.Retryable()
        }

        assertEquals(DropReason.RETRIES_EXHAUSTED, dropped)
        // 1 s, 2 s, then 4 s capped at 3 s.
        assertEquals(listOf(0L, 1_000L, 3_000L, 6_000L), attempts)
    }

    @Test
    fun aRetryAfterFromTheBackendIsHonouredUpToTheCap() = runTest {
        val attempts = mutableListOf<Long>()
        val answers = ArrayDeque(
            listOf(ExportResult.Retryable(200.milliseconds), ExportResult.Retryable(60.seconds), ExportResult.Success),
        )
        val dropped = sendWithRetries(ExportConfig(maxBackoff = 30.seconds)) {
            attempts += currentTime
            answers.removeFirst()
        }

        assertEquals(null, dropped)
        assertEquals(listOf(0L, 200L, 30_200L), attempts)
    }

    @Test
    fun theDefaultsFollowTheOpenTelemetryBatchProcessor() {
        assertEquals(ExportConfig(2_048, 512, 5.seconds, 3, 1.seconds, 30.seconds), ExportConfig())
    }

    @Test
    fun aDroppedBatchUsesTheDefaultDropCallbackSilently() = runTest {
        val exporter = BatchExporter(::encode, { ExportResult.Rejected(413) })
        exporter.start(backgroundScope)
        exporter.offer("a")
        exporter.shutdown()
        advanceTimeBy(1_000)

        assertEquals(
            listOf("queue_full", "rejected", "retries_exhausted", "closed", "collect_failed"),
            DropReason.entries.map { it.wire },
        )
    }
}
