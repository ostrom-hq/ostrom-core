// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.trace

import run.ostrom.core.telemetry.Attributes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Carries the current span through coroutines, so that a child span started deeper in the call finds its parent
 * without passing it by hand.
 *
 * @property spanContext The current span.
 */
public class SpanContextElement(public val spanContext: SpanContext) :
    AbstractCoroutineContextElement(SpanContextElement) {
    /** The key of the element. */
    public companion object Key : CoroutineContext.Key<SpanContextElement>
}

/** @return The span of the calling coroutine, or null outside any span. */
public suspend fun currentSpanContext(): SpanContext? = currentCoroutineContext()[SpanContextElement]?.spanContext

/** Decides, once per trace, whether it is recorded. */
public fun interface Sampler {
    /**
     * @param parent The remote or local parent, null for a new trace.
     * @param traceIdLow The low half of the trace id, uniformly random.
     * @return True to record the span.
     */
    public fun shouldSample(parent: SpanContext?, traceIdLow: Long): Boolean

    /** Ready-made samplers. */
    public companion object {
        /**
         * Follows the parent's decision, and samples a [ratio] of new traces. The decision depends on the trace id
         * only, so every service of a trace decides the same way.
         *
         * @param ratio Between 0 and 1.
         * @return The sampler.
         */
        public fun parentBasedRatio(ratio: Double): Sampler {
            require(ratio in 0.0..1.0) { "The sampling ratio must be between 0 and 1, got $ratio." }
            val bound = (ratio * Long.MAX_VALUE.toDouble()).toLong()
            return Sampler { parent, traceIdLow ->
                parent?.sampled ?: (ratio >= 1.0 || (traceIdLow and Long.MAX_VALUE) < bound)
            }
        }
    }
}

/** Starts spans. */
public interface Tracer {
    /**
     * Starts a span. Prefer [inSpan], which ends it on every path.
     *
     * @param name A low-cardinality name.
     * @param kind The role of the span.
     * @param parent The parent, from a `traceparent` header or [currentSpanContext]; null starts a new trace.
     * @param attributes The initial attributes.
     * @return The started span; the caller must [Span.end] it.
     */
    public fun startSpan(
        name: String,
        kind: SpanKind = SpanKind.INTERNAL,
        parent: SpanContext? = null,
        attributes: Attributes = Attributes.EMPTY,
    ): Span
}

/**
 * Runs [block] in a child span of the current one, ends it on every path, and marks it as an error when [block]
 * throws. A cancellation is not an error of this span: it is recorded as such and rethrown untouched.
 *
 * @param name A low-cardinality name.
 * @param kind The role of the span.
 * @param attributes The initial attributes.
 * @param block The work, which can add attributes to the span.
 * @return What [block] returns.
 */
public suspend fun <T> Tracer.inSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    attributes: Attributes = Attributes.EMPTY,
    block: suspend (Span) -> T,
): T {
    val span = startSpan(name, kind, currentSpanContext(), attributes)
    try {
        return withContext(SpanContextElement(span.context)) { block(span) }
    } catch (error: CancellationException) {
        span.setAttribute("ostrom.cancelled", true)
        throw error
    } catch (error: Throwable) {
        // Only the type: a message can hold a prompt or a provider's raw answer.
        span.setStatus(StatusCode.ERROR, error::class.simpleName ?: "error")
        throw error
    } finally {
        span.end()
    }
}

/**
 * The tracer used in production.
 *
 * @param clock Reads the time; tests inject a fixed clock.
 * @param ids Makes ids.
 * @param sampler Decides which traces are recorded.
 * @param processor Receives every finished span that was recorded: the sampled ones, and with [recordUnsampled] the
 * others too (`context.sampled` is then false, and they must not reach the trace backend).
 * @param maxAttributesPerSpan The attribute bound of one span.
 * @param recordUnsampled Records spans the sampler left out, so that a processor can keep some of them whatever the
 * sampling (model calls, which are the cost record in Langfuse).
 */
public class TracerImpl(
    private val clock: Clock,
    private val ids: IdGenerator,
    private val sampler: Sampler,
    private val processor: (SpanData) -> Unit,
    private val maxAttributesPerSpan: Int = DEFAULT_MAX_ATTRIBUTES,
    private val recordUnsampled: Boolean = false,
) : Tracer {
    override fun startSpan(name: String, kind: SpanKind, parent: SpanContext?, attributes: Attributes): Span {
        val validParent = parent?.takeIf { it.isValid }
        val traceIdHigh = validParent?.traceIdHigh ?: ids.nextId()
        val traceIdLow = validParent?.traceIdLow ?: ids.nextId()
        val context = SpanContext(
            traceIdHigh = traceIdHigh,
            traceIdLow = traceIdLow,
            spanId = ids.nextId(),
            sampled = sampler.shouldSample(validParent, traceIdLow),
        )
        return RecordingSpan(
            context = context,
            parentSpanId = validParent?.spanId ?: 0L,
            name = name,
            kind = kind,
            startEpochNanos = clock.epochNanos(),
            initialAttributes = attributes,
            nowEpochNanos = { clock.epochNanos() },
            onEnd = processor,
            maxAttributes = maxAttributesPerSpan,
            recording = context.sampled || recordUnsampled,
        )
    }

    private companion object {
        const val DEFAULT_MAX_ATTRIBUTES = 64
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L

/** @return The current time in nanoseconds since the Unix epoch. */
public fun Clock.epochNanos(): Long = now().let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond }
