# Telemetry

The `telemetry` module is what the server and the CLI use to emit traces, metrics, logs and product events. It has no
dependency on an OpenTelemetry SDK: `opentelemetry-kotlin` publishes no Linux, macOS or Windows artifact (CONVENTIONS
16.4), so the module implements the small part of OpenTelemetry Ostrom needs and writes OTLP/HTTP protobuf itself.

## What is implemented

| Piece | Where | Notes |
| --- | --- | --- |
| W3C Trace Context | `trace/SpanContext.kt` | `traceparent` parsed and written (level 1); a malformed header starts a new trace |
| Tracer | `trace/Tracer.kt` | parent-based ratio sampler, current span carried in the coroutine context (`inSpan`), at most 64 attributes per span, error status = exception type only |
| Meter | `metrics/MeterImpl.kt` | counters, explicit-bucket histograms, gauges; lock-free recording; cumulative temporality; 1 000 series per instrument then one `otel.metric.overflow=true` series |
| Logs | `logs/LogRecord.kt` | records linked to their trace and span (Loki ↔ Tempo) |
| OTLP encoder | `otlp/OtlpEncoder.kt` | protobuf, decoded in tests by the official `opentelemetry-proto` classes |
| Export | `export/` | bounded queue (2 048), batches of 512 every 5 s, retries on 429/502/503/504 with `Retry-After` up to 30 s; never blocks a request, every drop counted in `ostrom.telemetry.dropped{destination,reason}` (`queue_full`, `rejected`, `retries_exhausted`, `closed`, `collect_failed`) |
| Langfuse | `genai/ModelCall.kt` | a model call is a span with the GenAI conventions and Langfuse's `langfuse.*` keys; only those spans go to Langfuse, every one of them whatever `OTEL_TRACES_SAMPLER_ARG` (Langfuse is the cost record); the trace backend gets them without prompt or completion |
| PostHog | `analytics/` | typed factories for every event, `/batch/` encoder and client |
| Wiring | `Telemetry.kt`, `TelemetrySettings.kt` | one object built in the composition root; nothing configured = nothing sent |

## Configuration

Standard OpenTelemetry variables, so any collector or Helm chart configures Ostrom like any other service:

| Variable | Meaning |
| --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP/HTTP base URL, e.g. `https://otlp-gateway-prod-eu-west-2.grafana.net/otlp` or `http://otel-collector:4318` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (default; the only encoding written, JSON is not) |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Basic%20<base64(instanceId:token)>` for Grafana Cloud |
| `OTEL_SERVICE_NAME` | defaults to `ostrom-server` / `ostrom-cli` |
| `OTEL_RESOURCE_ATTRIBUTES` | `deployment.environment.name=production` |
| `OTEL_TRACES_SAMPLER_ARG` | share of new traces recorded, 0 to 1 (default 1) |
| `OTEL_SDK_DISABLED=true` | turns OTLP export off |
| `OSTROM_LANGFUSE_HOST`, `OSTROM_LANGFUSE_PUBLIC_KEY`, `OSTROM_LANGFUSE_SECRET_KEY` | Langfuse (EU cloud: `https://cloud.langfuse.com`) |
| `OSTROM_POSTHOG_API_KEY`, `OSTROM_POSTHOG_HOST` | PostHog (default `https://eu.i.posthog.com`) |

A self-hosted install that sets none of them sends nothing anywhere.

## What never leaves the process

- No prompt, completion, diff, command, path, token, e-mail or response body in a span, a log or an event. A span's
  error status carries the exception type, never its message.
- Model-call content reaches Langfuse only when the organization turned on content capture (`ContentCapture.FULL`),
  truncated to 32 768 characters.
- Metric attributes are bounded enums and route templates, never identifiers. Org and session ids may appear
  on traces (Langfuse groups calls by session), never on metrics.
- PostHog events carry the user's opaque id, never an e-mail; `$ip` is sent as null, `$geoip_disable` as true (the web
  console and the app send it too), and the PostHog project discards client IPs.

## Product events (PostHog)

PostHog Cloud EU, project "ostrom". Names are `snake_case`, past tense, and never renamed once shipped. Each server event
carries the org as PostHog's `organization` group.

| Event | Emitted by | Properties |
| --- | --- | --- |
| `signed_up` | server | `method`: github, google, magic_link |
| `org_created` | server | `plan`: free, team, business, enterprise |
| `member_joined` | server | `role` |
| `agent_session_started` | server | `agent`: claude, codex, other; `where`: local, cloud; `first_for_org` |
| `gate_requested` | server | `kind`: push_main, merge, deploy, custom, other |
| `gate_answered` | server | `answer`: yes, no; `channel`: web, app, slack, cli; `seconds_to_answer` |
| `first_yes` | server | `seconds_since_signup` |
| `subscription_started` | server | `plan`, `interval`: month, year |
| `subscription_canceled` | server | `plan` |
| `cli_command_run` | CLI, opt-in only | `command`, `exit_code_class`, `duration_ms_bucket`, `os`, `arch`, `version` |
| `$pageview`, `$screen` | web, site / app, after consent | — |
| `consent_updated` | web, site, app | `analytics`: true (a refusal sends nothing) |

Activation funnel (14-day window): `signed_up` → `org_created` → `agent_session_started` → `gate_requested` →
`first_yes`. The first "yes" is the activation moment.

## Planned

- Exemplars linking histogram buckets to traces.
- gzip request bodies (measured first: the CPU budget of telemetry is under 1 %).
