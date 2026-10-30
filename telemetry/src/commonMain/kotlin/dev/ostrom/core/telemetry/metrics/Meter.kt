// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.metrics

import dev.ostrom.core.telemetry.Attributes

/** A monotonic sum: requests served, gates asked, dollars spent on inference. */
public interface Counter {
    /**
     * Adds to the series identified by [attributes].
     *
     * @param value A non-negative amount; a negative one is ignored (a counter never goes down).
     * @param attributes Bounded attributes (an enum, a route template), never an identifier.
     */
    public fun add(value: Double, attributes: Attributes = Attributes.EMPTY)
}

/** Adds one. */
public fun Counter.increment(attributes: Attributes = Attributes.EMPTY): Unit = add(1.0, attributes)

/** A distribution with explicit bucket bounds: request durations, the time to the first "yes". */
public interface Histogram {
    /**
     * Records one measurement.
     *
     * @param value The measurement, in the instrument's unit.
     * @param attributes Bounded attributes.
     */
    public fun record(value: Double, attributes: Attributes = Attributes.EMPTY)
}

/** One value read by a gauge callback. */
public data class GaugeReading(val value: Double, val attributes: Attributes = Attributes.EMPTY)

/** Creates instruments. Create each instrument once, at start-up, and keep it: creation is not on a hot path. */
public interface Meter {
    /**
     * @param name A dotted name (`ostrom.gates.requested`).
     * @param unit A UCUM unit (`1`, `s`, `USD`, `{token}`).
     * @param description What it counts.
     * @return The counter.
     */
    public fun counter(name: String, unit: String, description: String): Counter

    /**
     * @param name A dotted name.
     * @param unit A UCUM unit.
     * @param description What it measures.
     * @param boundaries Increasing bucket upper bounds.
     * @return The histogram.
     */
    public fun histogram(name: String, unit: String, description: String, boundaries: List<Double>): Histogram

    /**
     * Registers a gauge read at each export (queue length, active sessions). The callback runs on the exporter's
     * coroutine: it must be fast and must not throw.
     *
     * @param name A dotted name.
     * @param unit A UCUM unit.
     * @param description What it shows.
     * @param observe Reads the current values.
     */
    public fun gauge(name: String, unit: String, description: String, observe: () -> List<GaugeReading>)
}

/** Bucket bounds shared by the server's instruments. */
public object Buckets {
    /** Seconds, for HTTP and database durations: 1 ms to 10 s. */
    public val LATENCY_SECONDS: List<Double> =
        listOf(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)

    /** Seconds, for the time an approver takes to answer: 5 s to one day. */
    public val HUMAN_SECONDS: List<Double> =
        listOf(5.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1_800.0, 3_600.0, 14_400.0, 86_400.0)
}

/** A meter that records nothing, for a self-hosted server with no telemetry endpoint. */
public object NoopMeter : Meter {
    private val counter = object : Counter {
        override fun add(value: Double, attributes: Attributes) = Unit
    }
    private val histogram = object : Histogram {
        override fun record(value: Double, attributes: Attributes) = Unit
    }

    override fun counter(name: String, unit: String, description: String): Counter = counter

    override fun histogram(name: String, unit: String, description: String, boundaries: List<Double>): Histogram =
        histogram

    override fun gauge(name: String, unit: String, description: String, observe: () -> List<GaugeReading>): Unit = Unit
}
