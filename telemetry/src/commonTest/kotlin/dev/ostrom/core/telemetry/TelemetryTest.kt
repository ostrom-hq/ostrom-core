package dev.ostrom.core.telemetry

import dev.ostrom.core.telemetry.analytics.PostHogConfig
import dev.ostrom.core.telemetry.analytics.ProductEvents
import dev.ostrom.core.telemetry.analytics.SignupMethod
import dev.ostrom.core.telemetry.export.ExportConfig
import dev.ostrom.core.telemetry.export.OtlpEndpoint
import dev.ostrom.core.telemetry.genai.ContentCapture
import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.genai.ModelCallSource
import dev.ostrom.core.telemetry.logs.LogRecord
import dev.ostrom.core.telemetry.logs.Severity
import dev.ostrom.core.telemetry.metrics.MeterImpl
import dev.ostrom.core.telemetry.metrics.MetricData
import dev.ostrom.core.telemetry.metrics.increment
import dev.ostrom.core.telemetry.otlp.Resource
import dev.ostrom.core.telemetry.trace.SequentialIds
import dev.ostrom.core.telemetry.trace.SteppingClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TelemetryTest {
    private val requests = mutableListOf<String>()
    private val resource = Resource("ostrom-server", "1.0.0", "ci", "pod-1")
    private var status = HttpStatusCode.OK

    // The engine runs on the test dispatcher: requests happen in virtual time, one at a time, in a known order.
    private fun TestScope.client() = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requests += request.url.toString()
                    respond("", status)
                }
            },
        ),
    )

    private fun TestScope.clients(): () -> HttpClient = client().let { client -> { client } }

    private val everything = TelemetrySettings(
        otlp = OtlpEndpoint("http://collector:4318"),
        langfuse = ModelCall.langfuseEndpoint("https://cloud.langfuse.com", "pk", "sk"),
        postHog = PostHogConfig("https://eu.i.posthog.com", "phc_test"),
        serviceName = "ostrom-server",
    )

    @Test
    fun spansLogsMetricsAndEventsReachTheirDestinations() = runTest {
        val telemetry = Telemetry.start(
            everything,
            resource,
            backgroundScope,
            clients(),
            SteppingClock(),
            SequentialIds(),
            ExportConfig(interval = 1.seconds),
        )

        telemetry.tracer.startSpan("GET /health").end()
        telemetry.tracer.startSpan(
            ModelCall.SPAN_NAME,
            attributes = ModelCall(ModelCallSource.GATEWAY, "openrouter", "m").toAttributes(ContentCapture.NONE),
        ).end()
        telemetry.log(LogRecord(1, Severity.INFO, "Started"))
        telemetry.capture(ProductEvents.signedUp("u1", SignupMethod.GOOGLE, 1))
        telemetry.meter.counter("requests", "1", "Requests.").increment()
        advanceTimeBy(1_001)
        telemetry.shutdown()

        assertEquals(
            listOf(
                "http://collector:4318/v1/logs",
                "http://collector:4318/v1/metrics",
                "http://collector:4318/v1/traces",
                "https://cloud.langfuse.com/api/public/otel/v1/traces",
                "https://eu.i.posthog.com/batch/",
            ),
            requests.sorted(),
        )
    }

    @Test
    fun withoutDestinationsNothingIsSentButTracesStillHaveIds() = runTest {
        val settings = TelemetrySettings(serviceName = "ostrom-cli")
        val telemetry = Telemetry.start(settings, resource, backgroundScope, clients())

        val span = telemetry.tracer.startSpan("command")
        span.end()
        val model = ModelCall(ModelCallSource.EXTRACTION, "anthropic", "m").toAttributes(ContentCapture.NONE)
        telemetry.tracer.startSpan(ModelCall.SPAN_NAME, attributes = model).end()
        telemetry.log(LogRecord(1, Severity.INFO, "ignored"))
        telemetry.capture(ProductEvents.signedUp("u1", SignupMethod.GOOGLE, 1))
        telemetry.meter.counter("c", "1", "C.").increment()
        telemetry.shutdown(1.seconds)

        assertTrue(span.context.isValid)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theDisabledTelemetryPropagatesIdsAndRecordsNothing() = runTest {
        val telemetry = Telemetry.disabled()

        val span = telemetry.tracer.startSpan("command")
        span.end()
        telemetry.log(LogRecord(1, Severity.INFO, "ignored"))
        telemetry.capture(ProductEvents.signedUp("u1", SignupMethod.GOOGLE, 1))
        telemetry.meter.counter("c", "1", "C.").increment()
        telemetry.shutdown()

        assertTrue(span.context.isValid)
        assertFalse(span.context.sampled)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun dropsAreCountedPerDestinationAndReason() = runTest {
        status = HttpStatusCode.BadRequest
        val telemetry = Telemetry.start(
            everything.copy(langfuse = null, postHog = null),
            resource,
            backgroundScope,
            clients(),
            SteppingClock(),
            SequentialIds(),
            ExportConfig(interval = 1.seconds),
        )

        telemetry.tracer.startSpan("refused").end()
        advanceTimeBy(1_001)
        status = HttpStatusCode.OK
        telemetry.shutdown()

        val dropped = (telemetry.meter as MeterImpl).collect().filterIsInstance<MetricData.Sum>()
            .single { it.name == "ostrom.telemetry.dropped" }.points.single()
        assertEquals(Attributes.of("destination" to "traces", "reason" to "rejected"), dropped.attributes)
        assertEquals(1.0, dropped.value)
        assertEquals(listOf("http://collector:4318/v1/traces", "http://collector:4318/v1/metrics"), requests)
    }
}
