// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.metrics

import dev.ostrom.core.telemetry.Attributes

/** One point of a sum or a gauge. Times are nanoseconds since the Unix epoch. */
public data class NumberPoint(
    val attributes: Attributes,
    val startEpochNanos: Long,
    val epochNanos: Long,
    val value: Double,
)

/** One point of a histogram, cumulative since [startEpochNanos]. */
public data class HistogramPoint(
    val attributes: Attributes,
    val startEpochNanos: Long,
    val epochNanos: Long,
    val count: Long,
    val sum: Double,
    val min: Double,
    val max: Double,
    val boundaries: List<Double>,
    val bucketCounts: List<Long>,
)

/** The state of one instrument at a collection. */
public sealed interface MetricData {
    /** The instrument name. */
    public val name: String

    /** The UCUM unit. */
    public val unit: String

    /** What it measures. */
    public val description: String

    /** A cumulative monotonic sum. */
    public data class Sum(
        override val name: String,
        override val unit: String,
        override val description: String,
        val points: List<NumberPoint>,
    ) : MetricData

    /** Values read at collection time. */
    public data class Gauge(
        override val name: String,
        override val unit: String,
        override val description: String,
        val points: List<NumberPoint>,
    ) : MetricData

    /** A cumulative explicit-bucket histogram. */
    public data class Distribution(
        override val name: String,
        override val unit: String,
        override val description: String,
        val points: List<HistogramPoint>,
    ) : MetricData
}
