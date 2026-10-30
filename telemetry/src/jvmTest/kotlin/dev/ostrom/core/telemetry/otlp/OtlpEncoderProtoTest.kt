package dev.ostrom.core.telemetry.otlp

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.attributes
import dev.ostrom.core.telemetry.logs.LogRecord
import dev.ostrom.core.telemetry.logs.Severity
import dev.ostrom.core.telemetry.metrics.HistogramPoint
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.core.telemetry.metrics.NumberPoint
import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.core.telemetry.trace.SpanData
import dev.ostrom.core.telemetry.trace.SpanKind
import dev.ostrom.core.telemetry.trace.StatusCode
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.AggregationTemporality
import io.opentelemetry.proto.metrics.v1.NumberDataPoint
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decodes what [OtlpEncoder] writes with the official `opentelemetry-proto` classes: a wrong field number or wire type
 * fails here, before any collector sees it.
 */
class OtlpEncoderProtoTest {
    private val encoder = OtlpEncoder(Resource("ostrom-server", "1.2.3", "ci", "pod-1"), scopeVersion = "0.1.0")

    private fun hex(bytes: com.google.protobuf.ByteString) = bytes.toByteArray().joinToString("") { "%02x".format(it) }

    private fun Iterable<KeyValue>.asMap(): Map<String, AnyValue> = associate { it.key to it.value }

    private val parentContext = SpanContext(0x4bf92f3577b34da6L, -0x5c316d62f1f1b8caL, 0x00f067aa0ba902b7L, true)

    @Test
    fun spansDecodeWithTheirIdsTimesKindStatusAndTypedAttributes() {
        val span = SpanData(
            context = parentContext.copy(spanId = 0x0102030405060708L),
            parentSpanId = 0x00f067aa0ba902b7L,
            name = "POST /api/v1/gates",
            kind = SpanKind.SERVER,
            startEpochNanos = 1_000,
            endEpochNanos = 2_000,
            attributes = attributes {
                put("http.route", "/api/v1/gates")
                put("http.response.status_code", 500L)
                put("ratio", 0.25)
                put("retried", false)
                put("empty", "")
                put("zero", 0L)
            },
            status = StatusCode.ERROR,
            statusMessage = "IllegalStateException",
        )

        val request = ExportTraceServiceRequest.parseFrom(encoder.encodeSpans(listOf(span)))

        val resourceSpans = request.resourceSpansList.single()
        val resource = resourceSpans.resource.attributesList.asMap()
        assertEquals("ostrom-server", resource.getValue("service.name").stringValue)
        assertEquals("ostrom", resource.getValue("service.namespace").stringValue)
        assertEquals("1.2.3", resource.getValue("service.version").stringValue)
        assertEquals("ci", resource.getValue("deployment.environment.name").stringValue)
        assertEquals("pod-1", resource.getValue("service.instance.id").stringValue)
        val scopeSpans = resourceSpans.scopeSpansList.single()
        assertEquals("dev.ostrom.core.telemetry", scopeSpans.scope.name)
        assertEquals("0.1.0", scopeSpans.scope.version)
        val decoded = scopeSpans.spansList.single()
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", hex(decoded.traceId))
        assertEquals("0102030405060708", hex(decoded.spanId))
        assertEquals("00f067aa0ba902b7", hex(decoded.parentSpanId))
        assertEquals("POST /api/v1/gates", decoded.name)
        assertEquals(Span.SpanKind.SPAN_KIND_SERVER, decoded.kind)
        assertEquals(1_000L, decoded.startTimeUnixNano)
        assertEquals(2_000L, decoded.endTimeUnixNano)
        assertEquals(Status.StatusCode.STATUS_CODE_ERROR, decoded.status.code)
        assertEquals("IllegalStateException", decoded.status.message)
        assertEquals(1, decoded.flags)
        val attributes = decoded.attributesList.asMap()
        assertEquals("/api/v1/gates", attributes.getValue("http.route").stringValue)
        assertEquals(500L, attributes.getValue("http.response.status_code").intValue)
        assertEquals(0.25, attributes.getValue("ratio").doubleValue)
        // Default values inside the AnyValue oneof are still present: false, "" and 0 are not "no value".
        assertEquals(AnyValue.ValueCase.BOOL_VALUE, attributes.getValue("retried").valueCase)
        assertEquals(AnyValue.ValueCase.STRING_VALUE, attributes.getValue("empty").valueCase)
        assertEquals(AnyValue.ValueCase.INT_VALUE, attributes.getValue("zero").valueCase)
    }

    @Test
    fun aRootUnsampledSpanWithoutStatusHasNoParentStatusOrFlags() {
        val span = SpanData(
            context = parentContext.copy(sampled = false),
            parentSpanId = 0,
            name = "root",
            kind = SpanKind.INTERNAL,
            startEpochNanos = 1,
            endEpochNanos = 2,
            attributes = Attributes.EMPTY,
            status = StatusCode.UNSET,
            statusMessage = "",
        )

        val decoded = ExportTraceServiceRequest.parseFrom(encoder.encodeSpans(listOf(span)))
            .getResourceSpans(0).getScopeSpans(0).getSpans(0)

        assertTrue(decoded.parentSpanId.isEmpty)
        assertFalse(decoded.hasStatus())
        assertEquals(0, decoded.flags)
        assertEquals(Span.SpanKind.SPAN_KIND_INTERNAL, decoded.kind)
    }

    @Test
    fun anOkStatusAndAClientSpanDecode() {
        val span = SpanData(parentContext, 0, "gen_ai.chat", SpanKind.CLIENT, 1, 2, Attributes.EMPTY, StatusCode.OK, "")

        val decoded = ExportTraceServiceRequest.parseFrom(encoder.encodeSpans(listOf(span)))
            .getResourceSpans(0).getScopeSpans(0).getSpans(0)

        assertEquals(Status.StatusCode.STATUS_CODE_OK, decoded.status.code)
        assertEquals(Span.SpanKind.SPAN_KIND_CLIENT, decoded.kind)
    }

    @Test
    fun metricsDecodeAsCumulativeSumsGaugesAndHistograms() {
        val route = Attributes.of("http.route" to "/health")
        val metrics = listOf(
            MetricData.Sum("ostrom.gates.requested", "{gate}", "Gates.", listOf(NumberPoint(route, 10, 20, 3.0))),
            MetricData.Gauge("ostrom.gates.pending", "{gate}", "Pending.", listOf(NumberPoint(route, 20, 20, 0.0))),
            MetricData.Distribution(
                "http.server.request.duration",
                "s",
                "Durations.",
                listOf(
                    HistogramPoint(route, 10, 20, 3, 1.5, 0.1, 1.0, listOf(0.5, 1.0), listOf(1, 2, 0)),
                    HistogramPoint(
                        Attributes.EMPTY, 10, 20, 0, 0.0, Double.POSITIVE_INFINITY, 0.0, listOf(1.0), listOf(0, 0),
                    ),
                ),
            ),
        )

        val decoded = ExportMetricsServiceRequest.parseFrom(encoder.encodeMetrics(metrics))
            .getResourceMetrics(0).getScopeMetrics(0).metricsList

        val (sum, gauge, histogram) = decoded
        assertEquals("ostrom.gates.requested", sum.name)
        assertEquals("{gate}", sum.unit)
        assertEquals("Gates.", sum.description)
        assertTrue(sum.sum.isMonotonic)
        assertEquals(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE, sum.sum.aggregationTemporality)
        val sumPoint = sum.sum.getDataPoints(0)
        assertEquals(3.0, sumPoint.asDouble)
        assertEquals(10L, sumPoint.startTimeUnixNano)
        assertEquals(20L, sumPoint.timeUnixNano)
        assertEquals("/health", sumPoint.attributesList.asMap().getValue("http.route").stringValue)
        // A gauge at 0 still carries its value: as_double is a oneof member.
        assertTrue(gauge.hasGauge())
        assertEquals(NumberDataPoint.ValueCase.AS_DOUBLE, gauge.gauge.getDataPoints(0).valueCase)
        val cumulative = AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE
        assertEquals(cumulative, histogram.histogram.aggregationTemporality)
        val (recorded, empty) = histogram.histogram.dataPointsList
        assertEquals(3L, recorded.count)
        assertEquals(1.5, recorded.sum)
        assertEquals(0.1, recorded.min)
        assertEquals(1.0, recorded.max)
        assertEquals(listOf(0.5, 1.0), recorded.explicitBoundsList)
        assertEquals(listOf(1L, 2L, 0L), recorded.bucketCountsList)
        assertTrue(empty.hasSum())
        assertFalse(empty.hasMin())
        assertFalse(empty.hasMax())
    }

    @Test
    fun logsDecodeWithSeverityBodyAndTheTraceTheyBelongTo() {
        val logs = listOf(
            LogRecord(5_000, Severity.WARN, "Retrying", Attributes.of("logger" to "telemetry"), parentContext),
            LogRecord(6_000, Severity.INFO, "Started", spanContext = parentContext.copy(sampled = false)),
            LogRecord(7_000, Severity.ERROR, "Failed"),
            LogRecord(8_000, Severity.DEBUG, "Detail"),
        )

        val decoded = ExportLogsServiceRequest.parseFrom(encoder.encodeLogs(logs))
            .getResourceLogs(0).getScopeLogs(0).logRecordsList

        val (warn, info, error, debug) = decoded
        assertEquals(5_000L, warn.timeUnixNano)
        assertEquals(5_000L, warn.observedTimeUnixNano)
        assertEquals(13, warn.severityNumberValue)
        assertEquals("WARN", warn.severityText)
        assertEquals("Retrying", warn.body.stringValue)
        assertEquals("telemetry", warn.attributesList.asMap().getValue("logger").stringValue)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", hex(warn.traceId))
        assertEquals("00f067aa0ba902b7", hex(warn.spanId))
        assertEquals(1, warn.flags)
        assertEquals(0, info.flags)
        assertEquals(9, info.severityNumberValue)
        assertTrue(error.traceId.isEmpty)
        assertEquals(17, error.severityNumberValue)
        assertEquals(5, debug.severityNumberValue)
    }
}
