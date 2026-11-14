# The typed client (`client`)

`run.ostrom:ostrom-client` is the typed Kotlin client of the server API, over the contract of `api`
(`api/openapi.yaml` and `run.ostrom.core.api.models`). The companion app (Kotlin Multiplatform, Compose
Multiplatform, in `ostrom-app`) uses it on Android, iOS and the desktop; the CLI will move to it later (see
[The CLI](#the-cli)). Its contract follows teamcode's `TeamCodeClient` (`teamcode:docs/companion.md`, "Client
contract"), from which the origin check, the failure mapping, the event-stream reader and the network failure classes
are ported (Apache-2.0).

## Targets

| Module | Targets |
| --- | --- |
| `api`, `client` | `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64`, `jvm` (tests), and, with `ostrom.mobileTargets=true`, Android (`minSdk` 26, namespaces `run.ostrom.core.api` and `run.ostrom.core.client`), `iosArm64` and `iosSimulatorArm64` |
| `receipts`, `telemetry`, `actions` | The four native targets and `jvm` |

The mobile targets come from the convention plugin `ostrom.mobile` (`build-logic/`), gated by the Gradle property
`ostrom.mobileTargets`: off by default, on in this repository's `gradle.properties`, so they are built, tested and
published here. ostrom-server and ostrom-cli include this build in their CI (`ostrom.core.local=true`); only the root
build's `gradle.properties` applies there, so they configure no Android or iOS target and need no Android SDK. A build
that wants the mobile variants from source (the companion app including this build) sets `ostrom.mobileTargets=true` in
its own `gradle.properties`.

Only the Android tasks need an Android SDK (`ANDROID_HOME` or `local.properties`); JVM, native, Kover and detekt tasks
configure and run without one, which CI checks by running them with the SDK variables unset. Android tests:
`./gradlew testAndroidHostTest` (`:api:testAndroidHostTest`, `:client:testAndroidHostTest`), the common tests on the
host JVM. The iOS klibs are cross-compiled on Linux (`compileKotlinIosArm64`); running the iOS tests needs a Mac.

## Use

```kotlin
val client = OstromClient(
    serverUrl = "https://ostrom.example",
    token = { credentials.token(account) },            // read at each call; null before sign-in
    organization = { settings.organization(account) }, // sent as Ostrom-Org; null lets the server choose
    createHttpClient = { configure -> HttpClient(Darwin) { configure(this) } },
    userAgent = "ostrom-app/1.2.0 (iOS 18.1)",
)
val me = client.auth.me()
val pending = client.approvals.queue().pending
client.close()
```

- One client per account; creating one is cheap (one Ktor client, no request) and `close()` closes the Ktor client
  the factory built. A factory that shares an engine instance (`HttpClient(engine)`) keeps that engine open.
- `serverUrl` must be an origin: HTTPS, or `http://` on `localhost`, `127.0.0.1` or `[::1]` only; no credentials,
  path, query or fragment. `origin` is the normalized value.
- Every call sends `Authorization: Bearer <token>` when the provider gives one, `Ostrom-Org` when the organization
  provider gives one, and `Accept: application/json`. Redirects are never followed (a credential never reaches another
  origin); ids are percent-encoded path segments, and empty, `.` and `..` are refused before any request.
- A non-2xx answer throws `OstromException(status, type, message, retryAfterSeconds, requestBound)`: `type` and
  `message` are the contract's `ApiError` (`error.type`, `error.message`), `type` is null when the body is not one (a
  proxy's page), whose content is never kept; `requestBound` is the `error.requestBound` of a `step_up_required`.
  A network failure propagates as the engine's own exception; `networkFailure(error)` tells its family (DNS, refused,
  timeout, TLS, closed, proxy, headers, other), or null when the error is not from the HTTP stack.
- `CancellationException` is never caught.

## Calls

| Property | Calls | Routes |
| --- | --- | --- |
| (client) | `capabilities()` | `GET /api/v1/capabilities` |
| `auth` | `signInMethods()`, `githubStartUrl(redirectUri, state, codeChallenge)`, `startUrl(provider, …)`, `exchangeMobileCode(code, codeVerifier, redirectUri)`, `unlock(unlockKey, requestId)`, `me()`, `account()`, `logout()`, `startDeviceLogin(scopes)`, `pollDeviceLogin(deviceCode)` | `/api/v1/auth/providers`, `/api/v1/auth/{provider}/start` (a URL to open, no request), `/api/v1/auth/mobile/token`, `/api/v1/auth/mobile/unlock`, `/api/v1/auth/me`, `/api/v1/auth/me/account`, `/api/v1/auth/logout`, `/api/v1/auth/device`, `/api/v1/auth/device/token` |
| `organizations` | `mine()` | `GET /api/v1/me/organizations` |
| `approvals` | `queue()`, `view(id)`, `answer(id, answer, shownDigest, note)` | `GET /api/v1/approvals/queue`, `GET /api/v1/approvals/{id}/view`, `POST /api/v1/approvals/{id}/answer` |
| `receipts` | `list(before, limit)`, `get(id)`, `keys()` | `GET /api/v1/receipts`, `GET /api/v1/receipts/{id}`, `GET /api/v1/receipts/keys` |
| `spending` | `report(period)` | `GET /api/v1/spending` |
| `activity` | `page(before, repo)` | `GET /api/v1/activity` |
| `pushDevices` | `register(pushToken, provider, platform, locale, publicKey)`, `delete(id)` | `POST /api/v1/push-devices`, `DELETE /api/v1/push-devices/{id}` |
| `notifications` | `preferences()`, `save(preferences)` | `GET` and `PUT /api/v1/me/notifications` |
| `waits` | `list(status, mine, before, limit, resolver)`, `get(id)`, `answer(id, action, values)` | `GET /api/v1/waits`, `GET /api/v1/waits/{id}`, `POST /api/v1/waits/{id}/answer` |
| `artifacts` | `list(before, limit)`, `get(id)`, `versions(id)`, `ticket(id, version)` | `GET /api/v1/artifacts`, `GET /api/v1/artifacts/{id}`, `GET /api/v1/artifacts/{id}/versions`, `POST /api/v1/artifacts/{id}/tickets` |
| `sessions` | `list(repo, dev, open, before, limit)`, `get(id)` | `GET /api/v1/sessions`, `GET /api/v1/sessions/{id}` |
| `todos` | `session(sessionId)` | `GET /api/v1/todos/session` |
| `workflows` | `list(repo, status)`, `get(id)`, `runs(id, status, before)`, `allRuns(status, before)` (the caller's), `run(id)` | `GET /api/v1/workflows`, `GET /api/v1/workflows/{id}`, `GET /api/v1/workflows/{id}/runs`, `GET /api/v1/runs`, `GET /api/v1/runs/{id}` |
| `events` | `follow(after, types, onGap, onOpen, onEvent)` | `GET /api/v1/events` (planned) |

Every parameter left null is not sent, so the server's default applies. The models are those of
`run.ostrom.core.api.models`, named like the schemas of `api/openapi.yaml`; an enum the contract declares inline
(`reasonType`, `relation`, `access`, the spending `period`) stays a `String`, so a new value never breaks a decode.
A yes from the phone (`approvals.answer` with `ApprovalAnswer.APPROVE`) needs a recent `auth.unlock`; without one it
throws `403 step_up_required`, with `requestBound` when the unlock is bound to another request.

`pollDeviceLogin` throws `OstromException` while the sign-in is pending: `428 authorization_pending`, `400 slow_down`,
`400 access_denied`, `400 expired_token`; the caller decides.

**PKCE.** `Pkce.create(randomBytes)` draws a verifier (32 bytes, unpadded base64url) and its S256 `challenge`;
`Pkce.base64Url(bytes)` encodes an OAuth `state`. The SHA-256 is Okio's, on every target. The app opens
`auth.githubStartUrl(redirectUri, state, pkce.challenge)`, checks the `state` of the return, then calls
`auth.exchangeMobileCode(code, pkce.verifier, redirectUri)`.

**Event streams.** An internal reader serves every followed stream: lines bounded at 256 Ki characters and the data
of one event at 1 Mi characters, comments (keep-alives), `id:` and `retry:` skipped (resumption uses the sequence in
the event), multi-line `data:` joined, frames without a name (or with an empty one) ignored, invalid UTF-8 decoded
with replacement characters. The cursor goes in `Last-Event-ID`; `onOpen` runs once the server accepted the stream,
before any frame; `event: denied` ends it (`StreamEnd.DENIED`). A stream that closes throws `OstromException` with
status `0` and no type: reconnect from the last sequence handled. A line or an event over its bound throws status
`0` with type `malformed_event` (`OstromClient.MALFORMED_EVENT`): reconnecting would replay it, so the caller stops.
A network failure surfaces as the engine's exception, and an exception from the caller's handler is rethrown as is.

`events.follow` reads the organization's feed with it. The feed's event schemas are not in the contract yet, so each
event reaches `onEvent` as a `StreamEvent(type, data)` with its JSON data; an unknown type is passed on like the
others, and an event without data is skipped. `types` keeps only those types (at most 64, sent sorted as
`?types=a,b`). A `gap` frame (`{"oldestSeq":…}`) calls `onGap` before the events that follow: events after the
cursor were purged, so the caller reloads what it shows. An event whose data is not JSON throws `malformed_event`.

## The CLI

The CLI keeps its own untyped `api-client` (`ostrom-cli/api-client`, `OstromApi`) for now; moving it to this client is
a follow-up. It needs, before it can: an `Idempotency-Key` per call, the W3C `traceparent` header of `telemetry`, a raw
call for routes it relays without decoding, and its terminal WebSocket channel and downloads, which stay in the CLI.
