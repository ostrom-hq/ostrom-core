package dev.ostrom.core.telemetry.genai

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.export.OtlpEndpoint
import dev.ostrom.core.telemetry.export.Signal
import dev.ostrom.core.telemetry.trace.SpanData
import kotlin.io.encoding.Base64

/**
 * Whether an organization lets prompts and completions reach Langfuse. Off by default: an org turns it on in its
 * settings to debug its own agents; Ostrom never needs it to run.
 */
public enum class ContentCapture {
    /** Model, tokens, cost and latency only. */
    NONE,

    /** Also the input and output, truncated to [ModelCall.MAX_CONTENT_CHARS]. */
    FULL,
}

/** Which part of the product called the model; a bounded attribute. */
public enum class ModelCallSource(public val wire: String) {
    /** The inference gateway, on behalf of an org's agent (BYOK or ours). */
    GATEWAY("gateway"),

    /** The extraction pipeline (packages, workflows). */
    EXTRACTION("extraction"),
}

/**
 * One call to a model provider, described with the OpenTelemetry GenAI conventions and Langfuse's own keys so that the
 * span shows as a "generation" in Langfuse and as an ordinary client span in Tempo.
 *
 * @property source Which part of the product called.
 * @property provider `openrouter`, `anthropic`, `openai`.
 * @property requestModel The model asked for.
 * @property responseModel The model that answered, when the provider says.
 * @property inputTokens Prompt tokens.
 * @property outputTokens Completion tokens.
 * @property costUsd What the call cost, in US dollars, when known.
 * @property orgId The organization, as a trace attribute (never a metric label).
 * @property sessionId The Ostrom agent session, which Langfuse groups calls by.
 * @property input The prompt, kept only when [ContentCapture.FULL].
 * @property output The completion, kept only when [ContentCapture.FULL].
 */
public data class ModelCall(
    val source: ModelCallSource,
    val provider: String,
    val requestModel: String,
    val responseModel: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double? = null,
    val orgId: String? = null,
    val sessionId: String? = null,
    val input: String? = null,
    val output: String? = null,
) {
    /**
     * The span attributes of the call.
     *
     * @param capture The organization's choice; with [ContentCapture.NONE], [input] and [output] are never written.
     * @return The attributes.
     */
    public fun toAttributes(capture: ContentCapture): Attributes = Attributes.Builder().apply {
        put(OBSERVATION_TYPE, "generation")
        put("ostrom.model_call.source", source.wire)
        put("gen_ai.system", provider)
        put("gen_ai.request.model", requestModel)
        responseModel?.let { put("gen_ai.response.model", it) }
        put("gen_ai.usage.input_tokens", inputTokens)
        put("gen_ai.usage.output_tokens", outputTokens)
        put("langfuse.observation.usage_details", """{"input":$inputTokens,"output":$outputTokens}""")
        costUsd?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            put("gen_ai.usage.cost", it)
            put("langfuse.observation.cost_details", """{"total":$it}""")
        }
        orgId?.let { put("langfuse.trace.metadata.org_id", it) }
        sessionId?.let { put("langfuse.session.id", it) }
        if (capture == ContentCapture.FULL) {
            input?.let { put(INPUT, it.take(MAX_CONTENT_CHARS)) }
            output?.let { put(OUTPUT, it.take(MAX_CONTENT_CHARS)) }
        }
    }.build()

    /** Constants and the Langfuse helpers. */
    public companion object {
        /** The span name of a model call; Langfuse shows it as the generation name. */
        public const val SPAN_NAME: String = "gen_ai.chat"

        /** The longest prompt or completion kept, in characters. */
        public const val MAX_CONTENT_CHARS: Int = 32_768

        private const val OBSERVATION_TYPE = "langfuse.observation.type"
        private const val INPUT = "langfuse.observation.input"
        private const val OUTPUT = "langfuse.observation.output"
        private val CONTENT = setOf(INPUT, OUTPUT)

        /**
         * The span without the prompt and the completion: what the trace backend (Tempo) receives, whatever the
         * organization's choice. Content only ever goes to Langfuse.
         *
         * @param span A finished span.
         * @return The same span, or a copy without the content attributes.
         */
        public fun withoutContent(span: SpanData): SpanData {
            val kept = span.attributes.without(CONTENT)
            return if (kept === span.attributes) span else span.copy(attributes = kept)
        }

        /**
         * True for the spans Langfuse should receive: model calls only. The rest of a trace goes to Tempo.
         *
         * @param span A finished span.
         * @return True when the span describes a model call.
         */
        public fun isModelCall(span: SpanData): Boolean = span.attributes[OBSERVATION_TYPE] != null

        /**
         * The Langfuse OTLP endpoint (traces only), with its Basic authentication and the ingestion version header
         * without which Langfuse may delay data by minutes.
         *
         * @param host `https://cloud.langfuse.com` (EU) or a self-hosted Langfuse 3.22+.
         * @param publicKey The project's public key.
         * @param secretKey The project's secret key.
         * @return The endpoint.
         */
        public fun langfuseEndpoint(host: String, publicKey: String, secretKey: String): OtlpEndpoint = OtlpEndpoint(
            baseUrl = "${host.trimEnd('/')}/api/public/otel",
            headers = mapOf(
                "Authorization" to "Basic " + Base64.encode("$publicKey:$secretKey".encodeToByteArray()),
                "x-langfuse-ingestion-version" to "4",
            ),
            signals = setOf(Signal.TRACES),
        )
    }
}
