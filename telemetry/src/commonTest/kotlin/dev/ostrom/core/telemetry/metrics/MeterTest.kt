package dev.ostrom.core.telemetry.metrics

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.attributes
import dev.ostrom.core.telemetry.trace.SteppingClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeterTest {
    private val meter = MeterImpl(SteppingClock(nowMillis = 1_000))

    @Test
    fun aCounterSumsPerAttributeSetAndIgnoresNegativeValues() {
        val counter = meter.counter("ostrom.gates.requested", "{gate}", "Gates opened.")
        val push = Attributes.of("kind" to "push_main")
        counter.increment(push)
        counter.add(2.5, push)
        counter.add(-4.0, push)
        counter.increment()

        val sum = meter.collect().single() as MetricData.Sum
        assertEquals("ostrom.gates.requested", sum.name)
        assertEquals("{gate}", sum.unit)
        assertEquals("Gates opened.", sum.description)
        assertEquals(mapOf(push to 3.5, Attributes.EMPTY to 1.0), sum.points.associate { it.attributes to it.value })
        // Cumulative: every point starts when the meter was created and is stamped at the collection.
        assertTrue(sum.points.all { it.startEpochNanos == 1_000_000_000L && it.epochNanos == 1_001_000_000L })
    }

    @Test
    fun aHistogramCountsBucketsSumMinAndMax() {
        val histogram = meter.histogram("http.server.request.duration", "s", "Request durations.", listOf(0.1, 1.0))
        histogram.record(0.05)
        histogram.record(0.1)
        histogram.record(0.5)
        histogram.record(3.0)

        val point = (meter.collect().single() as MetricData.Distribution).points.single()
        assertEquals(4L, point.count)
        assertEquals(3.65, point.sum, 1e-9)
        assertEquals(0.05, point.min)
        assertEquals(3.0, point.max)
        assertEquals(listOf(0.1, 1.0), point.boundaries)
        // A value equal to a bound falls in that bound's bucket (upper bound inclusive, as OTLP defines it).
        assertEquals(listOf(2L, 1L, 1L), point.bucketCounts)
    }

    @Test
    fun aHistogramIgnoresValuesThatAreNotFinite() {
        val histogram = meter.histogram("latency", "s", "Latency.", listOf(1.0))
        histogram.record(Double.NaN)
        histogram.record(Double.POSITIVE_INFINITY)
        histogram.record(0.5)

        val point = (meter.collect().single() as MetricData.Distribution).points.single()
        assertEquals(1L, point.count)
        assertEquals(0.5, point.sum)
    }

    @Test
    fun histogramBoundsMustIncrease() {
        assertFailsWith<IllegalArgumentException> { meter.histogram("bad", "s", "Bad.", listOf(1.0, 1.0)) }
    }

    @Test
    fun aGaugeIsReadAtEachCollection() {
        var pending = 3.0
        meter.gauge("ostrom.gates.pending", "{gate}", "Gates waiting for an answer.") {
            listOf(GaugeReading(pending), GaugeReading(1.0, Attributes.of("where" to "cloud")))
        }

        val first = meter.collect().single() as MetricData.Gauge
        pending = 5.0
        val second = meter.collect().single() as MetricData.Gauge

        assertEquals(listOf(3.0, 1.0), first.points.map { it.value })
        assertEquals(listOf(5.0, 1.0), second.points.map { it.value })
        assertEquals(first.points.first().epochNanos, first.points.first().startEpochNanos)
    }

    @Test
    fun instrumentsWithoutPointsAreLeftOut() {
        meter.counter("unused", "1", "Never incremented.")
        meter.histogram("unused.histogram", "s", "Never recorded.", Buckets.LATENCY_SECONDS)
        meter.gauge("empty.gauge", "1", "Reads nothing.") { emptyList() }

        assertEquals(emptyList(), meter.collect())
    }

    @Test
    fun seriesBeyondTheCardinalityBoundGoToOneOverflowSeries() {
        val bounded = MeterImpl(SteppingClock(), maxSeriesPerInstrument = 2)
        val counter = bounded.counter("requests", "1", "Requests.")
        val histogram = bounded.histogram("durations", "s", "Durations.", listOf(1.0))
        (1..5).forEach { index ->
            counter.increment(Attributes.of("route" to "/r$index"))
            histogram.record(0.5, Attributes.of("route" to "/r$index"))
        }
        counter.increment(Attributes.of("route" to "/r1"))

        val overflow = attributes { put("otel.metric.overflow", true) }
        val sums = (bounded.collect().first() as MetricData.Sum).points.associate { it.attributes to it.value }
        assertEquals(
            mapOf(Attributes.of("route" to "/r1") to 2.0, Attributes.of("route" to "/r2") to 1.0, overflow to 3.0),
            sums,
        )
        val distribution = bounded.collect().last() as MetricData.Distribution
        val counts = distribution.points.associate { it.attributes to it.count }
        assertEquals(3L, counts[overflow])
    }

    @Test
    fun theNoopMeterAcceptsEverythingAndKeepsNothing() {
        NoopMeter.counter("c", "1", "C.").add(1.0)
        NoopMeter.histogram("h", "s", "H.", Buckets.HUMAN_SECONDS).record(1.0)
        NoopMeter.gauge("g", "1", "G.") { listOf(GaugeReading(1.0)) }

        assertEquals(13, Buckets.LATENCY_SECONDS.size)
        assertEquals(11, Buckets.HUMAN_SECONDS.size)
    }
}
