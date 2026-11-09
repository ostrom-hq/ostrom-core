// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.export

import kotlin.time.Duration

/** The three OTLP signals and their HTTP paths. */
public enum class Signal(public val path: String) {
    /** Spans. */
    TRACES("v1/traces"),

    /** Metrics. */
    METRICS("v1/metrics"),

    /** Logs. */
    LOGS("v1/logs"),
}

/** What a backend answered to one batch. */
public sealed interface ExportResult {
    /** Accepted. */
    public data object Success : ExportResult

    /**
     * Worth retrying (429, 502, 503, 504, network error), after [retryAfter] when the backend said so.
     *
     * @property retryAfter The wait the backend asked for, if any.
     */
    public data class Retryable(val retryAfter: Duration? = null) : ExportResult

    /**
     * Refused for good (400, 401, 403, 413): retrying would fail the same way.
     *
     * @property status The HTTP status.
     */
    public data class Rejected(val status: Int) : ExportResult
}

/** Sends one encoded batch. Never throws for a backend or network failure: it says so in its result. */
public fun interface OtlpTransport {
    /**
     * @param signal The signal, which picks the path.
     * @param body The encoded request.
     * @return The outcome.
     */
    public suspend fun send(signal: Signal, body: ByteArray): ExportResult
}

/**
 * Where to send: an OTLP/HTTP base URL (`https://otlp-gateway-prod-eu-west-2.grafana.net/otlp`, a collector's
 * `http://otel-collector:4318`, Langfuse's `https://cloud.langfuse.com/api/public/otel`) and its headers.
 *
 * @property baseUrl The base URL; the signal path is appended.
 * @property headers Request headers, typically `Authorization`. Never printed: [toString] hides them.
 * @property signals The signals this backend accepts (Langfuse takes traces only).
 */
public class OtlpEndpoint(
    public val baseUrl: String,
    public val headers: Map<String, String> = emptyMap(),
    public val signals: Set<Signal> = Signal.entries.toSet(),
) {
    /** @return The full URL of [signal]. */
    public fun url(signal: Signal): String = "${baseUrl.trimEnd('/')}/${signal.path}"

    override fun toString(): String = "OtlpEndpoint($baseUrl, headers=${headers.keys}, signals=$signals)"
}
