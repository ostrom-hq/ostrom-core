@file:OptIn(ExperimentalAtomicApi::class)

package dev.ostrom.core.telemetry.metrics

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.trace.epochNanos
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.concurrent.atomics.updateAndFetch
import kotlin.time.Clock

/**
 * The in-process meter: lock-free recording, cumulative aggregation, read by [collect] at each export.
 *
 * Recording is on the request path, so it allocates nothing once a series exists: a series is found in a
 * copy-on-write map and updated with atomics. A series past [maxSeriesPerInstrument] goes to one overflow series
 * marked `otel.metric.overflow=true` (OpenTelemetry's cardinality limit) instead of growing memory.
 *
 * @param clock Reads the time.
 * @param maxSeriesPerInstrument The cardinality bound of one instrument.
 */
public class MeterImpl(
    private val clock: Clock,
    private val maxSeriesPerInstrument: Int = DEFAULT_MAX_SERIES,
) : Meter {
    private val startEpochNanos = clock.epochNanos()
    private val instruments = AtomicReference<List<Instrument>>(emptyList())

    override fun counter(name: String, unit: String, description: String): Counter =
        register(CounterInstrument(name, unit, description, maxSeriesPerInstrument))

    override fun histogram(name: String, unit: String, description: String, boundaries: List<Double>): Histogram {
        require(boundaries.zipWithNext().all { (a, b) -> a < b }) { "Histogram $name: bounds must increase." }
        return register(HistogramInstrument(name, unit, description, boundaries, maxSeriesPerInstrument))
    }

    override fun gauge(name: String, unit: String, description: String, observe: () -> List<GaugeReading>) {
        register(GaugeInstrument(name, unit, description, observe))
    }

    /**
     * Reads every instrument. Instruments with no point are left out.
     *
     * @return The metrics, cumulative since the meter was created.
     */
    public fun collect(): List<MetricData> {
        val now = clock.epochNanos()
        return instruments.load().mapNotNull { it.collect(startEpochNanos, now) }
    }

    private fun <T : Instrument> register(instrument: T): T {
        instruments.update { it + instrument }
        return instrument
    }

    private companion object {
        const val DEFAULT_MAX_SERIES = 1_000
    }
}

private interface Instrument {
    fun collect(startEpochNanos: Long, now: Long): MetricData?
}

private val OVERFLOW = Attributes.Builder().put("otel.metric.overflow", true).build()

/** A copy-on-write map from an attribute set to its cell, bounded. */
private class SeriesMap<C>(private val maxSeries: Int, private val newCell: () -> C) {
    private val cells = AtomicReference<Map<Attributes, C>>(emptyMap())

    fun cell(attributes: Attributes): C {
        cells.load()[attributes]?.let { return it }
        // The transform may run again when another thread won the race: creating a spare cell is harmless.
        val updated = cells.updateAndFetch { current ->
            val key = if (current.size < maxSeries) attributes else OVERFLOW
            if (key in current) current else current + (key to newCell())
        }
        return updated[attributes] ?: updated.getValue(OVERFLOW)
    }

    fun snapshot(): Map<Attributes, C> = cells.load()
}

/** Adds a double to a long holding its bits, atomically. */
private fun AtomicLong.addDouble(delta: Double) = update { (Double.fromBits(it) + delta).toRawBits() }

private class CounterInstrument(
    val name: String,
    val unit: String,
    val description: String,
    maxSeries: Int,
) : Instrument, Counter {
    private val series = SeriesMap(maxSeries) { AtomicLong(0.0.toRawBits()) }

    override fun add(value: Double, attributes: Attributes) {
        if (value >= 0.0) series.cell(attributes).addDouble(value)
    }

    override fun collect(startEpochNanos: Long, now: Long): MetricData? {
        val points = series.snapshot().map { (attributes, cell) ->
            NumberPoint(attributes, startEpochNanos, now, Double.fromBits(cell.load()))
        }
        return if (points.isEmpty()) null else MetricData.Sum(name, unit, description, points)
    }
}

private class HistogramCell(bucketCount: Int) {
    val buckets = AtomicLongArray(bucketCount)
    val count = AtomicLong(0)
    val sum = AtomicLong(0.0.toRawBits())
    val min = AtomicLong(Double.POSITIVE_INFINITY.toRawBits())
    val max = AtomicLong(Double.NEGATIVE_INFINITY.toRawBits())
}

private class HistogramInstrument(
    val name: String,
    val unit: String,
    val description: String,
    val boundaries: List<Double>,
    maxSeries: Int,
) : Instrument, Histogram {
    // An array, not the list: the bucket search runs on every recording (CONVENTIONS 10.3).
    private val bounds = boundaries.toDoubleArray()
    private val series = SeriesMap(maxSeries) { HistogramCell(bounds.size + 1) }

    override fun record(value: Double, attributes: Attributes) {
        val cell = series.cell(attributes)
        var bucket = 0
        while (bucket < bounds.size && value > bounds[bucket]) bucket++
        cell.buckets.fetchAndAddAt(bucket, 1L)
        cell.count.fetchAndAdd(1L)
        cell.sum.addDouble(value)
        cell.min.update { minOf(Double.fromBits(it), value).toRawBits() }
        cell.max.update { maxOf(Double.fromBits(it), value).toRawBits() }
    }

    override fun collect(startEpochNanos: Long, now: Long): MetricData? {
        val points = series.snapshot().map { (attributes, cell) ->
            HistogramPoint(
                attributes = attributes,
                startEpochNanos = startEpochNanos,
                epochNanos = now,
                count = cell.count.load(),
                sum = Double.fromBits(cell.sum.load()),
                min = Double.fromBits(cell.min.load()),
                max = Double.fromBits(cell.max.load()),
                boundaries = boundaries,
                bucketCounts = List(bounds.size + 1) { cell.buckets.loadAt(it) },
            )
        }
        return if (points.isEmpty()) null else MetricData.Distribution(name, unit, description, points)
    }
}

private class GaugeInstrument(
    val name: String,
    val unit: String,
    val description: String,
    val observe: () -> List<GaugeReading>,
) : Instrument {
    override fun collect(startEpochNanos: Long, now: Long): MetricData? {
        val points = observe().map { NumberPoint(it.attributes, now, now, it.value) }
        return if (points.isEmpty()) null else MetricData.Gauge(name, unit, description, points)
    }
}
