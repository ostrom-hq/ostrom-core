// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry

import run.ostrom.core.telemetry.analytics.PostHogConfig
import run.ostrom.core.telemetry.export.OtlpEndpoint
import run.ostrom.core.telemetry.genai.ModelCall

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
         * | `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (the default, and the only one supported) |
         * | `OTEL_EXPORTER_OTLP_HEADERS` | `key=value,key2=value2`, values percent-encoded (W3C baggage format) |
         * | `OTEL_SERVICE_NAME` | overrides [defaultServiceName] |
         * | `OTEL_RESOURCE_ATTRIBUTES` | reads `deployment.environment.name` from it |
         * | `OTEL_TRACES_SAMPLER_ARG` | sampling ratio, 0 to 1 |
         * | `OSTROM_LANGFUSE_HOST`, `OSTROM_LANGFUSE_PUBLIC_KEY`, `OSTROM_LANGFUSE_SECRET_KEY` | Langfuse |
         * | `OSTROM_POSTHOG_HOST` (default EU cloud), `OSTROM_POSTHOG_API_KEY` | PostHog |
         *
         * @param getEnv Reads one variable.
         * @param defaultServiceName The service name when `OTEL_SERVICE_NAME` is absent.
         * @return The settings.
         * @throws IllegalArgumentException When a value is malformed, so the process fails at start-up.
         */
        public fun fromEnvironment(getEnv: (String) -> String?, defaultServiceName: String): TelemetrySettings {
            fun value(name: String) = getEnv(name)?.let { raw -> raw.trim().takeIf { it.isNotEmpty() } }
            val disabled = value("OTEL_SDK_DISABLED")?.lowercase() == "true"
            val protocol = value("OTEL_EXPORTER_OTLP_PROTOCOL") ?: PROTOBUF
            require(protocol == PROTOBUF) { "OTEL_EXPORTER_OTLP_PROTOCOL: only $PROTOBUF is supported." }
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
            val postHog = value("OSTROM_POSTHOG_API_KEY")?.let { key ->
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
        internal fun parsePairs(raw: String?, variable: String): Map<String, String> {
            if (raw == null) return emptyMap()
            return raw.split(',').filter { it.isNotBlank() }.associate { pair ->
                val separator = pair.indexOf('=')
                require(separator > 0) { "$variable must be a list of key=value pairs." }
                pair.substring(0, separator).trim() to percentDecode(pair.substring(separator + 1).trim())
            }
        }

        private const val PROTOBUF = "http/protobuf"
        private const val HEX_RADIX = 16
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val ESCAPE_LENGTH = 3

        private fun percentDecode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var runStart = 0
            var index = 0
            // Text between escapes is copied as a whole, so a character outside the BMP (two UTF-16 units) survives.
            fun copyRun(end: Int) = value.substring(runStart, end).encodeToByteArray().forEach { bytes += it }
            while (index < value.length) {
                val decoded = if (value[index] == '%' && index + 2 < value.length) {
                    hexByte(value[index + 1], value[index + 2])
                } else {
                    null
                }
                if (decoded != null) {
                    copyRun(index)
                    bytes += decoded
                    index += ESCAPE_LENGTH
                    runStart = index
                } else {
                    index++
                }
            }
            copyRun(value.length)
            return bytes.toByteArray().decodeToString()
        }

        /** Two hexadecimal digits as a byte; null for anything else (a sign, a letter past f, a non-ASCII digit). */
        private fun hexByte(high: Char, low: Char): Byte? {
            val first = HEX_DIGITS.indexOf(high.lowercaseChar())
            val second = HEX_DIGITS.indexOf(low.lowercaseChar())
            return if (first < 0 || second < 0) null else (first * HEX_RADIX + second).toByte()
        }
    }
}
