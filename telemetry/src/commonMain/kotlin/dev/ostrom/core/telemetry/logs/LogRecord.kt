// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.logs

import dev.ostrom.core.telemetry.Attributes
import dev.ostrom.core.telemetry.trace.SpanContext

/** Log severities, with their OTLP severity numbers. */
public enum class Severity(public val otlp: Int) {
    /** Diagnostic detail, off in production. */
    DEBUG(5),

    /** A normal event worth keeping. */
    INFO(9),

    /** Something unexpected that the server survived. */
    WARN(13),

    /** A failure. */
    ERROR(17),
}

/**
 * One log line exported to Loki through OTLP. The body is a message written by our code, never a request or response
 * body, a prompt or a token (CONVENTIONS 6.1).
 *
 * @property epochNanos When it happened, nanoseconds since the Unix epoch.
 * @property severity The severity.
 * @property body The message.
 * @property attributes Structured fields (logger name, error type).
 * @property spanContext The span active when it was written, so that Grafana links the log to its trace.
 */
public data class LogRecord(
    val epochNanos: Long,
    val severity: Severity,
    val body: String,
    val attributes: Attributes = Attributes.EMPTY,
    val spanContext: SpanContext? = null,
)
