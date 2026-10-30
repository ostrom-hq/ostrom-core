// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.otlp

import dev.ostrom.core.telemetry.Attribute
import dev.ostrom.core.telemetry.AttributeValue
import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.logs.LogRecord
import dev.ostrom.core.telemetry.metrics.HistogramPoint
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.core.telemetry.metrics.NumberPoint
import dev.ostrom.core.telemetry.trace.SpanData
import dev.ostrom.core.telemetry.trace.StatusCode

/**
 * Who emits the telemetry: the OpenTelemetry resource attached to every batch.
 *
 * @property serviceName `ostrom-server`, `ostrom-cli`.
 * @property serviceVersion The build version.
 * @property environment `production`, `staging`, `ci`, `self-hosted`.
 * @property instanceId The pod or process identity.
 */
public data class Resource(
    val serviceName: String,
    val serviceVersion: String,
    val environment: String,
    val instanceId: String,
) {
    /** The resource attributes, with the semantic-convention keys. */
    public val attributes: Attributes = Attributes.of(
        "service.name" to serviceName,
        "service.namespace" to "ostrom",
        "service.version" to serviceVersion,
        "deployment.environment.name" to environment,
        "service.instance.id" to instanceId,
    )
}

/**
 * Encodes batches as OTLP/HTTP protobuf (`application/x-protobuf`), the encoding Grafana Cloud and Langfuse recommend
 * for production traffic. Field numbers are those of `opentelemetry-proto` 1.x; the JVM tests decode the output with
 * the official generated classes, and the integration test sends it to a real OpenTelemetry Collector.
 *
 * @param resource The resource of every batch.
 * @param scopeName The instrumentation scope name.
 * @param scopeVersion The instrumentation scope version.
 */
// One private function per OTLP message: splitting the encoder would only scatter one wire format.
@Suppress("TooManyFunctions")
public class OtlpEncoder(
    private val resource: Resource,
    private val scopeName: String = "dev.ostrom.core.telemetry",
    private val scopeVersion: String = "",
) {
    /** @return An `ExportTraceServiceRequest`. */
    public fun encodeSpans(spans: List<SpanData>): ByteArray = request { scope ->
        spans.forEach { span -> scope.message(SCOPE_ITEMS) { span(span) } }
    }

    /** @return An `ExportMetricsServiceRequest`. */
    public fun encodeMetrics(metrics: List<MetricData>): ByteArray = request { scope ->
        metrics.forEach { metric -> scope.message(SCOPE_ITEMS) { metric(metric) } }
    }

    /** @return An `ExportLogsServiceRequest`. */
    public fun encodeLogs(logs: List<LogRecord>): ByteArray = request { scope ->
        logs.forEach { log -> scope.message(SCOPE_ITEMS) { log(log) } }
    }

    // Resource{Spans,Metrics,Logs} = { 1: resource, 2: scope_{spans,metrics,logs} }; Scope* = { 1: scope, 2: items }.
    private fun request(items: (ProtoWriter) -> Unit): ByteArray = ProtoWriter().apply {
        message(REQUEST_RESOURCE_ITEMS) {
            message(RESOURCE) { attributes(RESOURCE_ATTRIBUTES, resource.attributes) }
            message(RESOURCE_SCOPES) {
                message(SCOPE) {
                    string(SCOPE_NAME, scopeName)
                    string(SCOPE_VERSION, scopeVersion)
                }
                items(this)
            }
        }
    }.toByteArray()

    private fun ProtoWriter.span(span: SpanData) {
        bytes(SPAN_TRACE_ID, idBytes(span.context.traceIdHigh, span.context.traceIdLow))
        bytes(SPAN_SPAN_ID, idBytes(span.context.spanId))
        if (span.parentSpanId != 0L) bytes(SPAN_PARENT_SPAN_ID, idBytes(span.parentSpanId))
        string(SPAN_NAME, span.name)
        varint(SPAN_KIND, span.kind.otlp.toLong())
        fixed64(SPAN_START, span.startEpochNanos)
        fixed64(SPAN_END, span.endEpochNanos)
        attributes(SPAN_ATTRIBUTES, span.attributes)
        if (span.status != StatusCode.UNSET) {
            message(SPAN_STATUS) {
                string(STATUS_MESSAGE, span.statusMessage)
                varint(STATUS_CODE, span.status.otlp.toLong())
            }
        }
        fixed32(SPAN_FLAGS, if (span.context.sampled) TRACE_FLAG_SAMPLED else 0)
    }

    private fun ProtoWriter.metric(metric: MetricData) {
        string(METRIC_NAME, metric.name)
        string(METRIC_DESCRIPTION, metric.description)
        string(METRIC_UNIT, metric.unit)
        when (metric) {
            is MetricData.Sum -> message(METRIC_SUM) {
                metric.points.forEach { point -> message(DATA_POINTS) { numberPoint(point) } }
                varint(AGGREGATION_TEMPORALITY, CUMULATIVE)
                bool(SUM_IS_MONOTONIC, true)
            }
            is MetricData.Gauge -> message(METRIC_GAUGE) {
                metric.points.forEach { point -> message(DATA_POINTS) { numberPoint(point) } }
            }
            is MetricData.Distribution -> message(METRIC_HISTOGRAM) {
                metric.points.forEach { point -> message(DATA_POINTS) { histogramPoint(point) } }
                varint(AGGREGATION_TEMPORALITY, CUMULATIVE)
            }
        }
    }

    private fun ProtoWriter.numberPoint(point: NumberPoint) {
        fixed64(POINT_START, point.startEpochNanos)
        fixed64(POINT_TIME, point.epochNanos)
        doubleAlways(NUMBER_AS_DOUBLE, point.value)
        attributes(NUMBER_ATTRIBUTES, point.attributes)
    }

    private fun ProtoWriter.histogramPoint(point: HistogramPoint) {
        fixed64(POINT_START, point.startEpochNanos)
        fixed64(POINT_TIME, point.epochNanos)
        fixed64(HISTOGRAM_COUNT, point.count)
        doubleAlways(HISTOGRAM_SUM, point.sum)
        packedFixed64(HISTOGRAM_BUCKET_COUNTS, point.bucketCounts)
        packedDouble(HISTOGRAM_BOUNDS, point.boundaries)
        attributes(HISTOGRAM_ATTRIBUTES, point.attributes)
        // min and max are optional: absent while nothing was recorded (they still hold the infinities).
        if (point.count > 0) {
            doubleAlways(HISTOGRAM_MIN, point.min)
            doubleAlways(HISTOGRAM_MAX, point.max)
        }
    }

    private fun ProtoWriter.log(log: LogRecord) {
        fixed64(LOG_TIME, log.epochNanos)
        fixed64(LOG_OBSERVED_TIME, log.epochNanos)
        varint(LOG_SEVERITY_NUMBER, log.severity.otlp.toLong())
        string(LOG_SEVERITY_TEXT, log.severity.name)
        message(LOG_BODY) { anyValue(AttributeValue.Text(log.body)) }
        attributes(LOG_ATTRIBUTES, log.attributes)
        log.spanContext?.let { context ->
            fixed32(LOG_FLAGS, if (context.sampled) TRACE_FLAG_SAMPLED else 0)
            bytes(LOG_TRACE_ID, idBytes(context.traceIdHigh, context.traceIdLow))
            bytes(LOG_SPAN_ID, idBytes(context.spanId))
        }
    }

    private fun ProtoWriter.attributes(field: Int, attributes: Attributes) {
        attributes.entries.forEach { attribute -> message(field) { keyValue(attribute) } }
    }

    private fun ProtoWriter.keyValue(attribute: Attribute) {
        string(KEY_VALUE_KEY, attribute.key)
        message(KEY_VALUE_VALUE) { anyValue(attribute.value) }
    }

    // AnyValue is a oneof: every member is written even when it holds its default value.
    private fun ProtoWriter.anyValue(value: AttributeValue) {
        when (value) {
            is AttributeValue.Text -> stringAlways(ANY_STRING, value.value)
            is AttributeValue.Flag -> varintAlways(ANY_BOOL, if (value.value) 1L else 0L)
            is AttributeValue.Integer -> varintAlways(ANY_INT, value.value)
            is AttributeValue.Decimal -> doubleAlways(ANY_DOUBLE, value.value)
        }
    }

    private fun idBytes(vararg halves: Long): ByteArray = ByteArray(halves.size * Long.SIZE_BYTES).also { bytes ->
        halves.forEachIndexed { half, value ->
            for (index in 0 until Long.SIZE_BYTES) {
                bytes[half * Long.SIZE_BYTES + index] =
                    (value ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
            }
        }
    }

    // Field numbers of opentelemetry-proto (collector/*/v1, common/v1, resource/v1, trace/v1, metrics/v1, logs/v1).
    private companion object {
        const val REQUEST_RESOURCE_ITEMS = 1
        const val RESOURCE = 1
        const val RESOURCE_SCOPES = 2
        const val RESOURCE_ATTRIBUTES = 1
        const val SCOPE = 1
        const val SCOPE_ITEMS = 2
        const val SCOPE_NAME = 1
        const val SCOPE_VERSION = 2
        const val KEY_VALUE_KEY = 1
        const val KEY_VALUE_VALUE = 2
        const val ANY_STRING = 1
        const val ANY_BOOL = 2
        const val ANY_INT = 3
        const val ANY_DOUBLE = 4

        const val SPAN_TRACE_ID = 1
        const val SPAN_SPAN_ID = 2
        const val SPAN_PARENT_SPAN_ID = 4
        const val SPAN_NAME = 5
        const val SPAN_KIND = 6
        const val SPAN_START = 7
        const val SPAN_END = 8
        const val SPAN_ATTRIBUTES = 9
        const val SPAN_STATUS = 15
        const val SPAN_FLAGS = 16
        const val STATUS_MESSAGE = 2
        const val STATUS_CODE = 3
        const val TRACE_FLAG_SAMPLED = 1

        const val METRIC_NAME = 1
        const val METRIC_DESCRIPTION = 2
        const val METRIC_UNIT = 3
        const val METRIC_GAUGE = 5
        const val METRIC_SUM = 7
        const val METRIC_HISTOGRAM = 9
        const val DATA_POINTS = 1
        const val AGGREGATION_TEMPORALITY = 2
        const val CUMULATIVE = 2L
        const val SUM_IS_MONOTONIC = 3
        const val POINT_START = 2
        const val POINT_TIME = 3
        const val NUMBER_AS_DOUBLE = 4
        const val NUMBER_ATTRIBUTES = 7
        const val HISTOGRAM_COUNT = 4
        const val HISTOGRAM_SUM = 5
        const val HISTOGRAM_BUCKET_COUNTS = 6
        const val HISTOGRAM_BOUNDS = 7
        const val HISTOGRAM_ATTRIBUTES = 9
        const val HISTOGRAM_MIN = 11
        const val HISTOGRAM_MAX = 12

        const val LOG_TIME = 1
        const val LOG_SEVERITY_NUMBER = 2
        const val LOG_SEVERITY_TEXT = 3
        const val LOG_BODY = 5
        const val LOG_ATTRIBUTES = 6
        const val LOG_FLAGS = 8
        const val LOG_TRACE_ID = 9
        const val LOG_SPAN_ID = 10
        const val LOG_OBSERVED_TIME = 11
    }
}
