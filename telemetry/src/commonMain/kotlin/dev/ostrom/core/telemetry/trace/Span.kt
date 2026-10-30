// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.trace

import dev.ostrom.core.telemetry.Attribute
import dev.ostrom.core.telemetry.AttributeValue
import dev.ostrom.core.telemetry.Attributes
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

/** The role of a span, as OTLP numbers it. */
public enum class SpanKind(public val otlp: Int) {
    /** Work inside the process. */
    INTERNAL(1),

    /** An incoming request handled by this process. */
    SERVER(2),

    /** An outgoing request to another service (the gateway calling a model provider). */
    CLIENT(3),
}

/** The outcome of a span, as OTLP numbers it. */
public enum class StatusCode(public val otlp: Int) {
    /** Nothing said: the default. */
    UNSET(0),

    /** Explicitly successful. */
    OK(1),

    /** Failed. */
    ERROR(2),
}

/** Makes span and trace ids. */
public fun interface IdGenerator {
    /** @return A random non-zero 64-bit value. */
    public fun nextId(): Long
}

/**
 * Ids from a [Random] source; [Random.Default] is cryptographically seeded on every platform, which is enough for ids
 * (they are not secrets).
 *
 * @param random The source; tests pass a seeded one.
 */
public class RandomIdGenerator(private val random: Random = Random.Default) : IdGenerator {
    override fun nextId(): Long {
        var id = random.nextLong()
        while (id == 0L) id = random.nextLong()
        return id
    }
}

/**
 * A finished span, handed to the processors. It never carries a prompt, a token or a body unless the organization
 * opted in to content capture (see `ModelCall`).
 *
 * @property context The span's own identity.
 * @property parentSpanId The parent span id, 0 for a root span.
 * @property name A low-cardinality name (`GET /api/v1/gates/{id}`, `gateway.completion`).
 * @property kind The role of the span.
 * @property startEpochNanos Start time, nanoseconds since the Unix epoch.
 * @property endEpochNanos End time, nanoseconds since the Unix epoch.
 * @property attributes The span attributes.
 * @property status The outcome.
 * @property statusMessage A short reason for an error, never an exception message from a provider.
 */
public data class SpanData(
    val context: SpanContext,
    val parentSpanId: Long,
    val name: String,
    val kind: SpanKind,
    val startEpochNanos: Long,
    val endEpochNanos: Long,
    val attributes: Attributes,
    val status: StatusCode,
    val statusMessage: String,
)

/** A span being recorded. Ended exactly once; later calls are ignored. */
public interface Span {
    /** The identity to propagate to children and to other processes. */
    public val context: SpanContext

    /** Sets an attribute; ignored once ended or when the span is not sampled. */
    public fun setAttribute(key: String, value: AttributeValue)

    /** Sets the outcome. @param message A short, non-sensitive reason. */
    public fun setStatus(code: StatusCode, message: String = "")

    /**
     * Renames the span, for a name known only after it started (an HTTP route is matched after the request began).
     * Ignored once ended.
     *
     * @param name The new low-cardinality name.
     */
    public fun updateName(name: String)

    /** Ends the span now and hands it to the processor if it is sampled. */
    public fun end()
}

/** Sets a text attribute. */
public fun Span.setAttribute(key: String, value: String): Unit = setAttribute(key, AttributeValue.Text(value))

/** Sets a whole-number attribute. */
public fun Span.setAttribute(key: String, value: Long): Unit = setAttribute(key, AttributeValue.Integer(value))

/** Sets a floating-point attribute. */
public fun Span.setAttribute(key: String, value: Double): Unit = setAttribute(key, AttributeValue.Decimal(value))

/** Sets a boolean attribute. */
public fun Span.setAttribute(key: String, value: Boolean): Unit = setAttribute(key, AttributeValue.Flag(value))

/** Sets every attribute of [attributes]. */
public fun Span.setAttributes(attributes: Attributes) {
    attributes.entries.forEach { setAttribute(it.key, it.value) }
}

/**
 * The recording span made by [TracerImpl]. Attribute writes come from the coroutine that owns the span; only [end] can
 * race (a timeout and the normal path), so only the end flag is atomic.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RecordingSpan(
    override val context: SpanContext,
    private val parentSpanId: Long,
    private var name: String,
    private val kind: SpanKind,
    private val startEpochNanos: Long,
    initialAttributes: Attributes,
    private val nowEpochNanos: () -> Long,
    private val onEnd: (SpanData) -> Unit,
    private val maxAttributes: Int,
    private val recording: Boolean = context.sampled,
) : Span {
    private val attributes = Attributes.Builder().apply { initialAttributes.entries.forEach { put(it) } }
    private var count = initialAttributes.entries.size
    private var status = StatusCode.UNSET
    private var statusMessage = ""
    private val ended = AtomicBoolean(false)

    override fun setAttribute(key: String, value: AttributeValue) {
        // Bounded: a loop that sets attributes cannot grow a span without limit; overwriting a key costs nothing.
        val known = key in attributes
        if (recording && !ended.load() && (known || count < maxAttributes)) {
            attributes.put(Attribute(key, value))
            if (!known) count++
        }
    }

    override fun setStatus(code: StatusCode, message: String) {
        if (!ended.load()) {
            status = code
            statusMessage = message
        }
    }

    override fun updateName(name: String) {
        if (!ended.load()) this.name = name
    }

    override fun end() {
        if (ended.compareAndSet(expectedValue = false, newValue = true) && recording) {
            onEnd(
                SpanData(
                    context = context,
                    parentSpanId = parentSpanId,
                    name = name,
                    kind = kind,
                    startEpochNanos = startEpochNanos,
                    endEpochNanos = nowEpochNanos(),
                    attributes = attributes.build(),
                    status = status,
                    statusMessage = statusMessage,
                ),
            )
        }
    }
}
