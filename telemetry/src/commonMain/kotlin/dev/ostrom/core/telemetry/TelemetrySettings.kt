package dev.ostrom.core.telemetry

import dev.ostrom.core.telemetry.analytics.PostHogConfig
import dev.ostrom.core.telemetry.export.OtlpEndpoint
import dev.ostrom.core.telemetry.genai.ModelCall

/**
 * Where telemetry goes, read once from the environment in the composition root. Every destination is optional: a
 * self-hosted server with none of these variables sends nothing anywhere and pays only for a few no-op calls.
 *
 * The OTLP variables are the standard `OTEL_*` ones, so any collector, Helm chart or Grafana Alloy setup configures
 * Ostrom like any other service; the product-specific destinations use `OSTROM_*`.
 *
 * @property otlp Grafana Cloud or any OTLP/HTTP collector (traces, metrics, logs).
 * @property langfuse Langfuse (model-call spans only).
 * @property postHog PostHog (product events).
 * @property serviceName `service.name`.
 * @property environment `deployment.environment.name`.
 * @property samplingRatio The share of new traces recorded.
 */
public data class TelemetrySettings(
    val otlp: OtlpEndpoint? = null,
    val langfuse: OtlpEndpoint? = null,
    val postHog: PostHogConfig? = null,
    val serviceName: String,
    val environment: String = "production",
    val samplingRatio: Double = 1.0,
) {
    /** Parsing. */
    public companion object {
        /**
         * Reads the settings.
         *
         * | Variable | Meaning |
         * | --- | --- |
         * | `OTEL_SDK_DISABLED=true` | turns OTLP export off (Langfuse and PostHog follow their own variables) |
         * | `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP/HTTP base URL, without `/v1/...` |
         * | `OTEL_EXPORTER_OTLP_HEADERS` | `key=value,key2=value2`, values percent-encoded (W3C baggage format) |
         * | `OTEL_SERVICE_NAME` | overrides [defaultServiceName] |
         * | `OTEL_RESOURCE_ATTRIBUTES` | reads `deployment.environment.name` from it |
         * | `OTEL_TRACES_SAMPLER_ARG` | sampling ratio, 0 to 1 |
         * | `OSTROM_LANGFUSE_HOST`, `OSTROM_LANGFUSE_PUBLIC_KEY`, `OSTROM_LANGFUSE_SECRET_KEY` | Langfuse |
         * | `OSTROM_POSTHOG_HOST` (default EU cloud), `OSTROM_POSTHOG_KEY` | PostHog |
         *
         * @param getEnv Reads one variable.
         * @param defaultServiceName The service name when `OTEL_SERVICE_NAME` is absent.
         * @return The settings.
         * @throws IllegalArgumentException When a value is malformed, so the process fails at start-up.
         */
        public fun fromEnvironment(getEnv: (String) -> String?, defaultServiceName: String): TelemetrySettings {
            fun value(name: String) = getEnv(name)?.trim()?.takeIf { it.isNotEmpty() }
            val disabled = value("OTEL_SDK_DISABLED")?.lowercase() == "true"
            val otlp = value("OTEL_EXPORTER_OTLP_ENDPOINT")?.takeUnless { disabled }?.let { url ->
                OtlpEndpoint(url, parsePairs(value("OTEL_EXPORTER_OTLP_HEADERS"), "OTEL_EXPORTER_OTLP_HEADERS"))
            }
            val langfuseHost = value("OSTROM_LANGFUSE_HOST")
            val langfuseKeys = value("OSTROM_LANGFUSE_PUBLIC_KEY") to value("OSTROM_LANGFUSE_SECRET_KEY")
            val langfuse = langfuseHost?.let { host ->
                val (publicKey, secretKey) = langfuseKeys
                require(publicKey != null && secretKey != null) {
                    "OSTROM_LANGFUSE_HOST is set: OSTROM_LANGFUSE_PUBLIC_KEY and " +
                        "OSTROM_LANGFUSE_SECRET_KEY are required."
                }
                ModelCall.langfuseEndpoint(host, publicKey, secretKey)
            }
            val postHog = value("OSTROM_POSTHOG_KEY")?.let { key ->
                PostHogConfig(value("OSTROM_POSTHOG_HOST") ?: PostHogConfig.EU_HOST, key)
            }
            val ratio = value("OTEL_TRACES_SAMPLER_ARG")?.let { raw ->
                raw.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                    ?: throw IllegalArgumentException("OTEL_TRACES_SAMPLER_ARG must be a number between 0 and 1.")
            }
            val resource = parsePairs(value("OTEL_RESOURCE_ATTRIBUTES"), "OTEL_RESOURCE_ATTRIBUTES")
            return TelemetrySettings(
                otlp = otlp,
                langfuse = langfuse,
                postHog = postHog,
                serviceName = value("OTEL_SERVICE_NAME") ?: defaultServiceName,
                environment = resource["deployment.environment.name"] ?: "production",
                samplingRatio = ratio ?: 1.0,
            )
        }

        /** Parses `k=v,k2=v2` with percent-encoded values; the error names the variable, never its value. */
        internal fun parsePairs(raw: String?, variable: String): Map<String, String> =
            raw?.split(',')?.filter { it.isNotBlank() }?.associate { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0) { "$variable must be a list of key=value pairs." }
                pair.substring(0, separator).trim() to percentDecode(pair.substring(separator + 1).trim())
            } ?: emptyMap()

        private const val HEX_RADIX = 16
        private const val ESCAPE_LENGTH = 3

        private fun percentDecode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                val escaped = value[index] == '%' && index + 2 < value.length
                val hex = if (escaped) value.substring(index + 1, index + ESCAPE_LENGTH) else null
                val decoded = hex?.toIntOrNull(HEX_RADIX)
                if (decoded != null) {
                    bytes += decoded.toByte()
                    index += ESCAPE_LENGTH
                } else {
                    value[index].toString().encodeToByteArray().forEach { bytes += it }
                    index++
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
