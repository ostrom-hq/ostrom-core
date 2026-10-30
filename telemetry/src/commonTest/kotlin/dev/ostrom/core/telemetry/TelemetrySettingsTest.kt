package dev.ostrom.core.telemetry

import dev.ostrom.core.telemetry.analytics.PostHogConfig
import dev.ostrom.core.telemetry.export.Signal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TelemetrySettingsTest {
    private fun settings(vararg env: Pair<String, String>) =
        TelemetrySettings.fromEnvironment(env.toMap()::get, defaultServiceName = "ostrom-server")

    @Test
    fun withNoVariableNothingIsSent() {
        val settings = settings()

        assertNull(settings.otlp)
        assertNull(settings.langfuse)
        assertNull(settings.postHog)
        assertEquals("ostrom-server", settings.serviceName)
        assertEquals("production", settings.environment)
        assertEquals(1.0, settings.samplingRatio)
    }

    @Test
    fun theStandardOtelVariablesConfigureTheOtlpDestination() {
        val settings = settings(
            "OTEL_EXPORTER_OTLP_ENDPOINT" to " https://otlp-gateway-prod-eu-west-2.grafana.net/otlp ",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Basic%20abc%3D,,X-Scope=ostrom",
            "OTEL_SERVICE_NAME" to "ostrom-api",
            "OTEL_RESOURCE_ATTRIBUTES" to "deployment.environment.name=staging,team=core",
            "OTEL_TRACES_SAMPLER_ARG" to "0.25",
        )

        val otlp = requireNotNull(settings.otlp)
        assertEquals("https://otlp-gateway-prod-eu-west-2.grafana.net/otlp/v1/traces", otlp.url(Signal.TRACES))
        assertEquals(mapOf("Authorization" to "Basic abc=", "X-Scope" to "ostrom"), otlp.headers)
        assertEquals("ostrom-api", settings.serviceName)
        assertEquals("staging", settings.environment)
        assertEquals(0.25, settings.samplingRatio)
    }

    @Test
    fun otelSdkDisabledTurnsOtlpOff() {
        val settings = settings("OTEL_SDK_DISABLED" to "TRUE", "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://collector:4318")

        assertNull(settings.otlp)
        val enabled = settings("OTEL_SDK_DISABLED" to "false", "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://collector:4318")
        assertEquals("http://collector:4318", enabled.otlp?.baseUrl)
    }

    @Test
    fun langfuseNeedsItsTwoKeys() {
        val settings = settings(
            "OSTROM_LANGFUSE_HOST" to "https://cloud.langfuse.com",
            "OSTROM_LANGFUSE_PUBLIC_KEY" to "pk",
            "OSTROM_LANGFUSE_SECRET_KEY" to "sk",
        )

        assertEquals("https://cloud.langfuse.com/api/public/otel", settings.langfuse?.baseUrl)
        assertFailsWith<IllegalArgumentException> {
            settings("OSTROM_LANGFUSE_HOST" to "https://cloud.langfuse.com", "OSTROM_LANGFUSE_PUBLIC_KEY" to "pk")
        }
        assertFailsWith<IllegalArgumentException> {
            settings("OSTROM_LANGFUSE_HOST" to "https://cloud.langfuse.com", "OSTROM_LANGFUSE_SECRET_KEY" to "sk")
        }
    }

    @Test
    fun postHogDefaultsToTheEuCloud() {
        assertEquals(PostHogConfig.EU_HOST, settings("OSTROM_POSTHOG_API_KEY" to "phc_1").postHog?.host)
        assertEquals(
            "https://posthog.internal",
            settings("OSTROM_POSTHOG_API_KEY" to "phc_1", "OSTROM_POSTHOG_HOST" to "https://posthog.internal")
                .postHog?.host,
        )
        assertNull(settings("OSTROM_POSTHOG_HOST" to "https://posthog.internal").postHog)
    }

    @Test
    fun malformedValuesFailAtStartUpWithoutEchoingThem() {
        val badRatio = assertFailsWith<IllegalArgumentException> { settings("OTEL_TRACES_SAMPLER_ARG" to "lots") }
        assertFailsWith<IllegalArgumentException> { settings("OTEL_TRACES_SAMPLER_ARG" to "2") }
        val badHeaders = assertFailsWith<IllegalArgumentException> {
            settings("OTEL_EXPORTER_OTLP_ENDPOINT" to "http://c", "OTEL_EXPORTER_OTLP_HEADERS" to "secret-token")
        }

        val badProtocol = assertFailsWith<IllegalArgumentException> {
            settings("OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json")
        }
        assertEquals(
            "OTEL_EXPORTER_OTLP_PROTOCOL: only http/protobuf is supported.",
            badProtocol.message,
        )
        assertNull(settings("OTEL_EXPORTER_OTLP_PROTOCOL" to "http/protobuf").otlp)
        assertEquals("OTEL_TRACES_SAMPLER_ARG must be a number between 0 and 1.", badRatio.message)
        assertEquals("OTEL_EXPORTER_OTLP_HEADERS must be a list of key=value pairs.", badHeaders.message)
    }

    @Test
    fun percentDecodingLeavesInvalidEscapesAndUnicodeAlone() {
        assertEquals(
            mapOf("a" to "100%", "b" to "%zz", "c" to "é", "d" to "x%2"),
            TelemetrySettings.parsePairs("a=100%,b=%zz,c=%C3%A9,d=x%2", "V"),
        )
        assertEquals(mapOf("e" to "ü"), TelemetrySettings.parsePairs("e=ü", "V"))
    }

    @Test
    fun blankValuesCountAsAbsent() {
        assertNull(settings("OTEL_EXPORTER_OTLP_ENDPOINT" to "   ").otlp)
    }
}
