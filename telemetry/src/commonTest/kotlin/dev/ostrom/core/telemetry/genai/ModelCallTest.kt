package dev.ostrom.core.telemetry.genai

import dev.ostrom.core.telemetry.AttributeValue
import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.export.Signal
import dev.ostrom.core.telemetry.trace.SpanContext
import dev.ostrom.core.telemetry.trace.SpanData
import dev.ostrom.core.telemetry.trace.SpanKind
import dev.ostrom.core.telemetry.trace.StatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCallTest {
    private val call = ModelCall(
        source = ModelCallSource.GATEWAY,
        provider = "openrouter",
        requestModel = "anthropic/claude-sonnet",
        responseModel = "anthropic/claude-sonnet-20261001",
        inputTokens = 1_200,
        outputTokens = 300,
        costUsd = 0.5,
        orgId = "org-1",
        sessionId = "session-1",
        input = "the prompt",
        output = "x".repeat(ModelCall.MAX_CONTENT_CHARS + 10),
    )

    private fun Attributes.text(key: String) = (get(key) as? AttributeValue.Text)?.value

    @Test
    fun withoutContentCaptureNoPromptOrCompletionIsWritten() {
        val attributes = call.toAttributes(ContentCapture.NONE)

        assertEquals("generation", attributes.text("langfuse.observation.type"))
        assertEquals("gateway", attributes.text("ostrom.model_call.source"))
        assertEquals("openrouter", attributes.text("gen_ai.system"))
        assertEquals("anthropic/claude-sonnet", attributes.text("gen_ai.request.model"))
        assertEquals("anthropic/claude-sonnet-20261001", attributes.text("gen_ai.response.model"))
        assertEquals(AttributeValue.Integer(1_200), attributes["gen_ai.usage.input_tokens"])
        assertEquals(AttributeValue.Integer(300), attributes["gen_ai.usage.output_tokens"])
        assertEquals("""{"input":1200,"output":300}""", attributes.text("langfuse.observation.usage_details"))
        assertEquals(AttributeValue.Decimal(0.5), attributes["gen_ai.usage.cost"])
        assertEquals("""{"total":0.5}""", attributes.text("langfuse.observation.cost_details"))
        assertEquals("org-1", attributes.text("langfuse.trace.metadata.org_id"))
        assertEquals("session-1", attributes.text("langfuse.session.id"))
        assertNull(attributes["langfuse.observation.input"])
        assertNull(attributes["langfuse.observation.output"])
    }

    @Test
    fun withFullCaptureContentIsKeptAndTruncated() {
        val attributes = call.toAttributes(ContentCapture.FULL)

        assertEquals("the prompt", attributes.text("langfuse.observation.input"))
        assertEquals(ModelCall.MAX_CONTENT_CHARS, attributes.text("langfuse.observation.output")?.length)
    }

    @Test
    fun optionalFieldsAndAnInvalidCostAreLeftOut() {
        val minimal = ModelCall(ModelCallSource.EXTRACTION, "anthropic", "claude-haiku", costUsd = Double.NaN)
        val negative = minimal.copy(costUsd = -1.0)
        val infinite = minimal.copy(costUsd = Double.POSITIVE_INFINITY)

        listOf(minimal, negative, infinite).forEach { model ->
            val attributes = model.toAttributes(ContentCapture.FULL)
            assertEquals("extraction", attributes.text("ostrom.model_call.source"))
            listOf(
                "gen_ai.response.model",
                "gen_ai.usage.cost",
                "langfuse.trace.metadata.org_id",
                "langfuse.session.id",
                "langfuse.observation.input",
                "langfuse.observation.output",
            ).forEach { key -> assertNull(attributes[key], key) }
        }
    }

    @Test
    fun onlyModelCallSpansGoToLangfuse() {
        val context = SpanContext(1, 1, 1, true)
        fun span(attributes: Attributes) =
            SpanData(context, 0, ModelCall.SPAN_NAME, SpanKind.CLIENT, 0, 1, attributes, StatusCode.UNSET, "")

        assertTrue(ModelCall.isModelCall(span(call.toAttributes(ContentCapture.NONE))))
        assertFalse(ModelCall.isModelCall(span(Attributes.of("http.route" to "/api/v1/gates"))))
    }

    @Test
    fun theLangfuseEndpointCarriesBasicAuthAndTheIngestionVersion() {
        val endpoint = ModelCall.langfuseEndpoint("https://cloud.langfuse.com/", "pk-lf-1", "sk-lf-2")

        assertEquals("https://cloud.langfuse.com/api/public/otel/v1/traces", endpoint.url(Signal.TRACES))
        // base64("pk-lf-1:sk-lf-2")
        assertEquals("Basic cGstbGYtMTpzay1sZi0y", endpoint.headers["Authorization"])
        assertEquals("4", endpoint.headers["x-langfuse-ingestion-version"])
        assertEquals(setOf(Signal.TRACES), endpoint.signals)
    }

    @Test
    fun theTraceBackendCopyHasNoContent() {
        val context = SpanContext(1, 1, 1, true)
        fun span(attributes: Attributes) =
            SpanData(context, 0, ModelCall.SPAN_NAME, SpanKind.CLIENT, 0, 1, attributes, StatusCode.UNSET, "")

        val full = span(call.toAttributes(ContentCapture.FULL))
        val stripped = ModelCall.withoutContent(full).attributes
        assertNull(stripped["langfuse.observation.input"])
        assertNull(stripped["langfuse.observation.output"])
        assertEquals("openrouter", stripped.text("gen_ai.system"))
        val none = span(call.toAttributes(ContentCapture.NONE))
        assertTrue(ModelCall.withoutContent(none) === none)
    }
}
