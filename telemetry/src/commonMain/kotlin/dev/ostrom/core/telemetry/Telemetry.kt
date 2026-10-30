package dev.ostrom.core.telemetry

import dev.ostrom.core.telemetry.analytics.PostHogClient
import dev.ostrom.core.telemetry.analytics.PostHogEncoder
import dev.ostrom.core.telemetry.analytics.ProductEvent
import dev.ostrom.core.telemetry.export.BatchExporter
import dev.ostrom.core.telemetry.export.DropReason
import dev.ostrom.core.telemetry.export.ExportConfig
import dev.ostrom.core.telemetry.export.KtorOtlpTransport
import dev.ostrom.core.telemetry.export.PeriodicMetricReader
import dev.ostrom.core.telemetry.export.Signal
import dev.ostrom.core.telemetry.genai.ModelCall
import dev.ostrom.core.telemetry.logs.LogRecord
import dev.ostrom.core.telemetry.metrics.Meter
import dev.ostrom.core.telemetry.metrics.MeterImpl
import dev.ostrom.core.telemetry.metrics.NoopMeter
import dev.ostrom.core.telemetry.otlp.OtlpEncoder
import dev.ostrom.core.telemetry.otlp.Resource
import dev.ostrom.core.telemetry.trace.IdGenerator
import dev.ostrom.core.telemetry.trace.RandomIdGenerator
import dev.ostrom.core.telemetry.trace.Sampler
import dev.ostrom.core.telemetry.trace.SpanData
import dev.ostrom.core.telemetry.trace.Tracer
import dev.ostrom.core.telemetry.trace.TracerImpl
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Everything a process needs to emit telemetry: a tracer, a meter, a log sink and a product event sink, wired to the
 * destinations of [TelemetrySettings]. Built once in the composition root and passed by constructor; never a global.
 */
public interface Telemetry {
    /** Starts spans; spans are exported only when a trace destination is configured. */
    public val tracer: Tracer

    /** Creates instruments. */
    public val meter: Meter

    /** Exports one log record; returns at once (bounded queue). */
    public fun log(record: LogRecord)

    /** Sends one product event to PostHog; returns at once (bounded queue). */
    public fun capture(event: ProductEvent)

    /** Sends what is queued, waiting at most [timeout]; call it once, when the process stops. */
    public suspend fun shutdown(timeout: Duration = 5.seconds)

    /** Factories. */
    public companion object {
        /**
         * Telemetry that records nothing and sends nothing; trace ids are still made so `traceparent` propagates.
         *
         * @param clock Reads the time.
         * @return The disabled telemetry.
         */
        public fun disabled(clock: Clock = Clock.System): Telemetry = DisabledTelemetry(clock)

        /**
         * Builds the telemetry and starts its exporters in [scope].
         *
         * @param settings The destinations.
         * @param resource Who emits.
         * @param scope Owns the exporters; cancelling it stops them.
         * @param httpClient Makes the client of one destination; telemetry never shares the product's client.
         * @param clock Reads the time.
         * @param ids Makes trace and span ids.
         * @param config Queue and retry bounds.
         * @return The running telemetry.
         */
        @Suppress("LongParameterList") // Every collaborator is injected so tests control time, ids and HTTP.
        public fun start(
            settings: TelemetrySettings,
            resource: Resource,
            scope: CoroutineScope,
            httpClient: () -> HttpClient,
            clock: Clock = Clock.System,
            ids: IdGenerator = RandomIdGenerator(),
            config: ExportConfig = ExportConfig(),
        ): Telemetry = RunningTelemetry(settings, resource, scope, httpClient, clock, ids, config)
    }
}

private class DisabledTelemetry(clock: Clock) : Telemetry {
    override val tracer: Tracer = TracerImpl(clock, RandomIdGenerator(), { _, _ -> false }, {})
    override val meter: Meter = NoopMeter
    override fun log(record: LogRecord) = Unit
    override fun capture(event: ProductEvent) = Unit
    override suspend fun shutdown(timeout: Duration) = Unit
}

@Suppress("LongParameterList") // Mirrors Telemetry.start.
private class RunningTelemetry(
    settings: TelemetrySettings,
    resource: Resource,
    scope: CoroutineScope,
    httpClient: () -> HttpClient,
    clock: Clock,
    ids: IdGenerator,
    config: ExportConfig,
) : Telemetry {
    private val meterImpl = MeterImpl(clock)
    private val encoder = OtlpEncoder(resource)
    private val dropped = meterImpl.counter(
        name = "ostrom.telemetry.dropped",
        unit = "{item}",
        description = "Telemetry items dropped because a backend was slow, down or refused them.",
    )
    private val otlp = settings.otlp?.let { KtorOtlpTransport(httpClient(), it) }
    private val spans = otlp?.let { transport ->
        BatchExporter(encoder::encodeSpans, { body -> transport.send(Signal.TRACES, body) }, config, drops("traces"))
    }
    private val logs = otlp?.let { transport ->
        BatchExporter(encoder::encodeLogs, { body -> transport.send(Signal.LOGS, body) }, config, drops("logs"))
    }
    private val metrics = otlp?.let { transport ->
        PeriodicMetricReader(meterImpl::collect, encoder::encodeMetrics, transport, onDrop = drops("metrics"))
    }
    private val langfuse = settings.langfuse?.let { endpoint ->
        val transport = KtorOtlpTransport(httpClient(), endpoint)
        BatchExporter(encoder::encodeSpans, { body -> transport.send(Signal.TRACES, body) }, config, drops("langfuse"))
    }
    private val events = settings.postHog?.let { postHog ->
        val client = PostHogClient(httpClient(), postHog)
        val postHogEncoder = PostHogEncoder(postHog, settings.serviceName, resource.serviceVersion)
        BatchExporter(postHogEncoder::encode, client::send, config, drops("posthog"))
    }
    private val batches = listOfNotNull(spans, logs, langfuse, events)
    private val batchJobs = batches.map { it.start(scope) }
    private val metricsJob = metrics?.start(scope)

    override val tracer: Tracer = TracerImpl(clock, ids, Sampler.parentBasedRatio(settings.samplingRatio), ::onSpanEnd)
    override val meter: Meter = meterImpl

    override fun log(record: LogRecord) {
        logs?.offer(record)
    }

    override fun capture(event: ProductEvent) {
        events?.offer(event)
    }

    override suspend fun shutdown(timeout: Duration) {
        batches.forEach { it.shutdown() }
        metricsJob?.cancel()
        withTimeoutOrNull(timeout) {
            metrics?.exportNow()
            batchJobs.joinAll()
        }
        batchJobs.forEach { it.cancel() }
    }

    private fun onSpanEnd(span: SpanData) {
        spans?.offer(span)
        if (ModelCall.isModelCall(span)) langfuse?.offer(span)
    }

    private fun drops(destination: String): (DropReason, Int) -> Unit = { reason, count ->
        dropped.add(count.toDouble(), Attributes.of("destination" to destination, "reason" to reason.wire))
    }
}
