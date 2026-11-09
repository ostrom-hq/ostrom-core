// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.trace

import run.ostrom.core.telemetry.AttributeValue
import run.ostrom.core.telemetry.Attributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A clock that moves forward by one millisecond at each read. */
internal class SteppingClock(private var nowMillis: Long = 1_700_000_000_000) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis++)
}

/** Ids 1, 2, 3, … in order. */
internal class SequentialIds(private var next: Long = 1) : IdGenerator {
    override fun nextId(): Long = next++
}

class TracerTest {
    private val ended = mutableListOf<SpanData>()
    private val tracer = TracerImpl(SteppingClock(), SequentialIds(), Sampler.parentBasedRatio(1.0), { ended += it })

    @Test
    fun aRootSpanGetsANewTraceAndIsExportedOnceEnded() {
        val route = Attributes.of("http.route" to "/health")
        val span = tracer.startSpan("GET /health", SpanKind.SERVER, attributes = route)
        span.setAttribute("http.response.status_code", 200L)
        span.setStatus(StatusCode.OK)
        span.end()
        span.end()

        val data = ended.single()
        assertEquals(SpanContext(1, 2, 3, true), data.context)
        assertEquals(0L, data.parentSpanId)
        assertEquals("GET /health", data.name)
        assertEquals(SpanKind.SERVER, data.kind)
        assertEquals(1_700_000_000_000_000_000L, data.startEpochNanos)
        assertEquals(1_700_000_000_001_000_000L, data.endEpochNanos)
        assertEquals(AttributeValue.Integer(200), data.attributes["http.response.status_code"])
        assertEquals(AttributeValue.Text("/health"), data.attributes["http.route"])
        assertEquals(StatusCode.OK, data.status)
    }

    @Test
    fun aChildSpanContinuesTheTraceOfItsParent() {
        val parent = SpanContext(10, 20, 30, sampled = true)

        tracer.startSpan("child", parent = parent).end()

        val data = ended.single()
        assertEquals(10L, data.context.traceIdHigh)
        assertEquals(20L, data.context.traceIdLow)
        assertEquals(30L, data.parentSpanId)
        assertEquals(1L, data.context.spanId)
    }

    @Test
    fun anInvalidParentIsIgnored() {
        tracer.startSpan("orphan", parent = SpanContext(0, 0, 5, true)).end()

        assertEquals(0L, ended.single().parentSpanId)
    }

    @Test
    fun anUnsampledSpanRecordsNothingAndIsNotExported() {
        val span = tracer.startSpan("skipped", parent = SpanContext(1, 1, 1, sampled = false))
        span.setAttribute("ignored", "value")
        span.end()

        assertFalse(span.context.sampled)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun writesAfterTheEndAreIgnored() {
        val span = tracer.startSpan("GET")
        span.updateName("GET /v1/gates/{id}")
        span.end()
        span.setAttribute("late", true)
        span.setStatus(StatusCode.ERROR, "late")
        span.updateName("late")

        assertEquals("GET /v1/gates/{id}", ended.single().name)
        assertNull(ended.single().attributes["late"])
        assertEquals(StatusCode.UNSET, ended.single().status)
    }

    @Test
    fun attributesAreBoundedPerSpan() {
        val bounded = TracerImpl(SteppingClock(), SequentialIds(), Sampler.parentBasedRatio(1.0), { ended += it }, 2)
        val span = bounded.startSpan("loop", attributes = Attributes.of("a" to "1"))
        span.setAttribute("b", 2.5)
        span.setAttribute("c", false)
        span.end()

        assertEquals(listOf("a", "b"), ended.single().attributes.entries.map { it.key })
    }

    @Test
    fun overwritingAKeyDoesNotUseTheBound() {
        val bounded = TracerImpl(SteppingClock(), SequentialIds(), Sampler.parentBasedRatio(1.0), { ended += it }, 2)
        val span = bounded.startSpan("loop", attributes = Attributes.of("a" to "1"))
        repeat(5) { span.setAttribute("a", it.toLong()) }
        span.setAttribute("b", true)
        span.end()

        assertEquals(listOf("a", "b"), ended.single().attributes.entries.map { it.key })
        assertEquals(AttributeValue.Integer(4), ended.single().attributes["a"])
    }

    @Test
    fun unsampledSpansCanBeRecordedForAProcessorThatKeepsSome() {
        val tracer = TracerImpl(
            SteppingClock(),
            SequentialIds(),
            Sampler.parentBasedRatio(0.0),
            { ended += it },
            recordUnsampled = true,
        )
        val span = tracer.startSpan("gen_ai.chat")
        span.setAttribute("gen_ai.system", "openrouter")
        span.end()

        assertFalse(ended.single().context.sampled)
        assertEquals(AttributeValue.Text("openrouter"), ended.single().attributes["gen_ai.system"])
    }

    @Test
    fun setAttributesCopiesEveryEntry() {
        val span = tracer.startSpan("bulk")
        span.setAttributes(Attributes.of("x" to "1", "y" to "2"))
        span.end()

        assertEquals(Attributes.of("x" to "1", "y" to "2"), ended.single().attributes)
    }

    @Test
    fun inSpanNestsChildrenUnderTheCurrentSpan() = runTest {
        val result = tracer.inSpan("outer") { outer ->
            assertEquals(outer.context, currentSpanContext())
            tracer.inSpan("inner", SpanKind.CLIENT) { "done" }
        }

        assertEquals("done", result)
        val (inner, outer) = ended
        assertEquals("inner", inner.name)
        assertEquals(outer.context.spanId, inner.parentSpanId)
        assertEquals(outer.context.traceIdLow, inner.context.traceIdLow)
        assertNull(currentSpanContext())
    }

    @Test
    fun inSpanMarksAFailureWithTheErrorTypeOnly() = runTest {
        assertFailsWith<IllegalStateException> {
            tracer.inSpan("failing") { error("secret prompt inside the message") }
        }

        assertEquals(StatusCode.ERROR, ended.single().status)
        assertEquals("IllegalStateException", ended.single().statusMessage)
    }

    @Test
    fun aFailureWithoutAClassNameIsStillAnError() = runTest {
        val anonymous = object : RuntimeException("secret") {}
        assertFailsWith<RuntimeException> { tracer.inSpan("failing") { throw anonymous } }

        val span = ended.single()
        assertEquals(StatusCode.ERROR, span.status)
        assertTrue(span.statusMessage.isNotBlank())
        assertFalse(span.statusMessage.contains("secret"))
    }

    @Test
    fun inSpanPropagatesCancellationWithoutMarkingAnError() = runTest {
        assertFailsWith<CancellationException> {
            tracer.inSpan("cancelled") { throw CancellationException("caller went away") }
        }

        assertEquals(StatusCode.UNSET, ended.single().status)
        assertEquals(AttributeValue.Flag(true), ended.single().attributes["ostrom.cancelled"])
    }

    @Test
    fun theRatioSamplerFollowsTheParentThenTheTraceId() {
        val half = Sampler.parentBasedRatio(0.5)

        assertTrue(half.shouldSample(SpanContext(1, 1, 1, true), Long.MAX_VALUE))
        assertFalse(half.shouldSample(SpanContext(1, 1, 1, false), 0))
        assertTrue(half.shouldSample(null, 1))
        assertFalse(half.shouldSample(null, Long.MAX_VALUE))
        assertFalse(half.shouldSample(null, -1))
        assertTrue(Sampler.parentBasedRatio(1.0).shouldSample(null, Long.MAX_VALUE))
        assertFalse(Sampler.parentBasedRatio(0.0).shouldSample(null, 0))
    }

    @Test
    fun aRatioOutsideZeroToOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { Sampler.parentBasedRatio(1.5) }
        assertFailsWith<IllegalArgumentException> { Sampler.parentBasedRatio(-0.1) }
    }

    @Test
    fun randomIdsAreNeverZero() {
        // A source that returns 0 first: the generator must draw again.
        val source = object : Random() {
            private var calls = 0
            override fun nextBits(bitCount: Int): Int = if (calls++ < 2) 0 else 7
        }

        assertNotEquals(0L, RandomIdGenerator(source).nextId())
        assertNotEquals(0L, RandomIdGenerator().nextId())
    }

    @Test
    fun epochNanosCombinesSecondsAndNanoseconds() {
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(2, 5)
        }

        assertEquals(2_000_000_005L, clock.epochNanos())
    }
}
