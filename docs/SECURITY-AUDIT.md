# Security audit of the Kotlin/TypeScript rewrite

> **Private working document.** It lists open holes. Before any `ostrom-hq` repository becomes public, this file is
> removed from the public history or reduced to the closed items (OSS agent, launch checklist). Reports from outside
> go through `SECURITY.md`.

Second audit (SECURITY-2), night of 30/10/2026, on the new code of `ostrom-hq/*` (open pull requests and `main`).
The first audit covered the prototype: `ostrom` repository, `mvp/COORD/AUDIT-PORTE.md` (holes A1 to A24). The rules
the code must meet are `docs/CONVENTIONS.md` § 16 (pull request ostrom-core#4) and the requirements written in the
coordination journal at 02:15. Every finding gives its evidence as `repository#PR:path:line` on the commit read.

Severity: **critical** (an outsider or an agent breaks isolation, a yes or an account today), **high** (a go blocker:
a rule of § 16 is not met, or the hole opens as soon as the next module lands), **medium**, **low**.

Commits read: server `feat/socle` 77a3e76, `feat/identity` 368121a (+ local `feat/identity-signin` 97fc8e1, not
pushed), `feat/gates-storage` 298ee4f, `feat/secrets` 9bf908a, `feat/mcp-relay` c9eea2d, `feat/runners-drivers`
04f2c67, `feat/billing-module` 6011e13; web `feat/api-client`, `feat/yes-screens`, `ci/image`; helm `feat/chart`;
app `main` bd01382.

## Summary

| Id | Severity | Area | Finding | Where | Status |
|---|---|---|---|---|---|
| S2-01 | high | tenancy | RLS does not hold in production: the chart's PostgreSQL user is a superuser (bypasses `FORCE`), gates rely on `FORCE` alone, and secrets, mcp-relay, runners and billing tables have no RLS at all | server#1, #8, #10, #4, #13, #6, #15; helm#1 | open |
| S2-02 | high | auth | No forwarded-address handling: every sign-in rate limit is keyed on the ingress proxy's address, one bucket for the whole internet (10 `ostrom login` per 10 min, 30 OAuth starts, 10 magic links) | server#1, #10 | open |
| S2-03 | high | auth (A2) | Console routes do not refuse agent or device credentials by construction: gates, secrets, mcp-relay and billing routes check no scope and no credential kind, and lean on three resolvers not written yet | server#8, #4, #13, #15 | open |
| S2-04 | high | auth (A1/A2, M2) | The CLI token carries `gates:answer` and `workflows:write` and lives on the developer's machine, readable by the agent: the agent answers other people's gates or removes its own | server#10 `Policies.kt:36` | open |
| S2-05 | high | gates (A7b) | A push's diff, commits and force flag come from the hook, never from GitHub; nothing checks after the push that what landed is what was shown | server#8 `AskGateUseCase.kt:90-122` | open |
| S2-06 | high | gates (A8) | A merge gate whose pull request head is unknown (no GitHub integration yet) binds the yes to the command text: commits pushed after the yes are merged under it | server#8 `AskGateUseCase.kt:81-84`, `DefaultGatePorts.kt:43` | open |
| S2-07 | high (latent) | auth | Accounts merge on any `email_verified`, whatever the issuer: an organization's own OIDC connection can assert a stranger's address and take their account | server#10 `AccountUseCases.kt:57-66`, signin `OAuthProviders.kt:204-212` | open, before the SSO PR |
| S2-08 | high | web | The console keeps a bearer token in `localStorage`; any XSS (agent-written diffs, commands, PR titles are rendered) steals it | web#3 `src/shared/auth/authStore.ts` | open |
| S2-09 | medium | web, headers | No Content-Security-Policy and no HSTS on the console | web#4 `docker/security-headers.conf` | open |
| S2-10 | high | cloud | Cloud sessions run anyone's code (Free plan) on ControlResell's production nodes with runc only: no sandbox runtime, `nodeSelector`/`tolerations` not applied by the driver, ControlResell's addresses not denied | server#9, helm#1 | open |
| S2-11 | medium | secrets | The chart passes no `OSTROM_SECRETS_KEY`: each pod generates its own key file in its ephemeral filesystem (secrets lost on restart, unreadable by the other replicas); the file is written before `chmod` | server#4 `SecretsKey.kt:42-51`, helm#1 | open |
| S2-12 | medium | auth | Session cookie without the `__Host-` prefix; an artifacts sub-domain of `ostrom.run` could set a parent-domain cookie (session fixation) | server#10 `IdentityRoutes.kt:181` | open |
| S2-13 | medium | auth | Device-flow phishing: the client name is chosen by the caller and the prefilled `verification_uri_complete` invites a one-click approval of a 90-day token | server#10 `DeviceUseCases.kt:60`, `IdentityRoutes.kt:97` | open |
| S2-14 | medium | gates | An approved yes never expires, and can be reserved and consumed while its receipt is not signed | server#8 `UseGateUseCase.kt:66-77`, `EnsureGateReceiptUseCase.kt` | open |
| S2-15 | medium | gates | Self-approval compares free-text identities, and the asker falls back to the credential id when the resolver gives no person: a resolver that forgets `personId` disables the rule | server#8 `AskGateUseCase.kt:77`, `GateRules.kt:189` | open |
| S2-16 | low | auth | Agent tokens are revoked by session id across organizations (session ids are agent strings) | server#10 `TokenUseCases.kt:259`, `CredentialsRepositoriesImpl.kt:130` | open |
| S2-17 | low | auth | Org SSO over OIDC must verify the ID token signature (JWKS from discovery): the token endpoint is chosen by an org admin | signin `OAuthProviders.kt:146-149` | open, before the SSO PR |
| S2-18 | low | app | Receipts are verified against the key served by the same server, without pinning | app `src/repositories/ReceiptsRepository.ts` | open |
| S2-19 | — | core | Package envelopes ignore `revoked` and `contentDigest`, recursive canonical JSON without a depth limit, duplicated `kid` (review of 02:20) | core `main` | fix in progress by CORE (`3d74405`, not pushed at 02:50) |
| S2-20 | — | CLI | Command parsing and hook bypasses A4, A5, A6, A9, A12, A13, A24 on the Kotlin CLI | cli#5, #1, #4 | under review |

## The prototype's holes in the new code

| Hole | Verdict on the new code |
|---|---|
| A1 (an open route recreates another's account and token) | No such route: accounts come only from a sign-in, tokens from a browser session or a device flow approved by one (`IdentityRoutes.kt:137-169`). Not regressed; re-check when the orgs module adds members and invitations. |
| A2 (console writes without credentials, or with a device token) | Kernel answers 401 without credentials; `requireScope` exists; but the module routes do not use it (S2-03), and the CLI token has the console scopes (S2-04). **Partly regressed.** |
| A3 (no token = the console operator) | Gone: no fallback, `authenticated` answers 401. Closed. |
| A4, A6, A9 (options before the verb, redirection, push to main without naming it) | Server side: `GateCommands.redirected` refuses the A6 forms (`GateRules.kt:213-227`). Hook side: S2-20. |
| A5 (a yes on a push covers a force-push) | `force` is in the key (`GateRules.kt:51`) but read from the request body, not from the command (S2-05). |
| A7 (a yes for a harmless diff covers the real one) | The yes is bound to the shown digest and refused when it changed (`AnswerGateUseCase.kt:88`). Closed for the binding; the diff itself is still the hook's (A7b, S2-05). |
| A8 (merge key without base or gate) | Base and gate node are in the key, head and base come from GitHub (`GateRules.kt:38-43`), except when GitHub is not reachable or configured (S2-06). |
| A10, A11, A17 (session writes by another, workflow receipt, session id reuse) | The sessions module is not written; gates check the launch (`UseGateUseCase.kt:62`). To re-check with the sessions PR. |
| A12 (the agent holds the user's token) | The server issues agent tokens with the single `agent:session` scope (`TokenUseCases.kt:219-234`, `CredentialAuthenticator.kt:39-45`). Hook side: S2-20. |
| A14 (the receipt does not sign what was approved) | The receipts signer is not written (`UnavailableGateReceiptSigner`). Requirement: the gate receipt signs repo, refs and shas, remote, force, diff digest, PR head and base. |
| A24 (two parallel tool calls use one yes) | Closed: reserve under the gate's row lock, then consume (`UseGateUseCase.kt:59-85`). |

## What is already right

OAuth with `state` in a browser-bound cookie, PKCE S256 and an OIDC nonce, each attempt consumed once by an atomic
`UPDATE … RETURNING` (signin `SignInUseCases.kt:118-129`, `SignInRepositoriesImpl.kt:37`); redirects limited to console
paths (`safeRedirectPath`); magic links hashed, 15 minutes, single use, redeemed by a POST so mail scanners do not burn
them, a silent per-recipient limit (no enumeration); tokens and sessions stored as SHA-256 of 244-bit secrets,
prefixed, revocable, rotatable, never accepted in the wrong channel; CSRF header required on every unsafe method of a
cookie session; device flow per RFC 8628 with an atomic consume; Stripe webhooks verified by HMAC with a 5-minute
tolerance and deduplicated by event id; runner pods without service-account token, non-root, all capabilities dropped,
seccomp `RuntimeDefault`; upstream MCP URLs limited to public HTTPS and the server's egress denied to private ranges by
NetworkPolicy; org secrets sealed with AES-256-GCM bound to org and secret id; request logs without query strings; no
CORS.

## Details and fixes

### S2-01 — RLS does not hold in production (high)

- helm#1 `charts/ostrom/values.yaml:184-187`: the official `postgres` image makes `POSTGRES_USER=ostrom` a
  **superuser**; the server connects with it. A superuser bypasses RLS, `FORCE` included.
- server#8 `GatesMigrations.kt:54-63` forces RLS but `PostgresGatesRepository.kt:101-105` only sets `ostrom.org_id`:
  under that superuser the policies never apply. The test admits it (`GatesPostgresTest.kt:295`: "the CI database user
  is a superuser … the proof runs as a plain role").
- server#10 `Tenancy.kt` takes the other route (`SET LOCAL ROLE ostrom_tenant`, no `FORCE`): it works for the queries
  that go through `inOrg`, and nothing else is protected.
- No RLS at all: `secrets_entries` (server#4 `SecretsMigrations.kt`), `mcp_relay_*` (server#13), `runners_sessions`,
  `runners_usage` (server#6), `billing_*` (server#15).

Fix (CONVENTIONS § 16.1): one kernel entry point `withOrganization(orgId)` used by every tenant repository; a serving
role `ostrom_app` that owns nothing, `NOSUPERUSER NOBYPASSRLS`; `ostrom-server migrate` with owner credentials the
serving process never receives (the chart already has `migrations.initContainer`); at start, refuse to serve when
`SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user` is true (except single-user SQLite); every
tenant table `ENABLE` + `FORCE` + fail-closed policies; CI integration tests connect as the serving role.

### S2-02 — One rate-limit bucket for everyone (high)

server#1 `kernel/http/Http.kt:35-52` installs no `ForwardedHeaders`/`XForwardedHeaders`; the limits use
`call.request.origin.remoteAddress` (server#10 `IdentityRoutes.kt:89`, signin `SignInRoutes.kt:62,82`), which behind
ingress-nginx (and the console's nginx proxy) is the proxy's address. At launch, ten `ostrom login` in ten minutes lock
out the whole internet. `InMemoryRateLimiter` is also per pod. Fix: `XForwardedHeaders` trusting only the ingress
and console proxy hops (right-most untrusted address), the limiter in PostgreSQL (or a shared store), and a route test
with two client addresses behind the proxy.

### S2-03 — Agent and device credentials reach console routes (high, A2)

The kernel has `requireScope` (server#10 `TokenAuthentication.kt`), but: gates (server#8 `GatesRoutes.kt:69-97`: ask,
answer, list) only call `requirePrincipal`; secrets (server#4 `SecretsRoutes.kt:46-67`) and mcp-relay (server#13
`McpRelayRoutes.kt:84-183`) call `requireCaller`, whose contract lets "agent routes whose token is bound to one org"
resolve; billing (server#15 `BillingRoutes.kt:49-59`) passes `subjectId`, which for an agent is its session id. Three
resolver contracts (`GateActorResolver`, `CallerResolver`, `BillingAccessPolicy`), all to be written by AUTH. Fix: one
kernel helper `requireOrgPrincipal(resolver, orgId, scope)` that refuses `PrincipalKind.AGENT` and `DEVICE` everywhere
but on the routes of `agent:session`, checks the scope (`gates:ask`, `gates:answer`, `secrets:write`, `mcp:admin`,
`billing:manage`…), and a contract test generated from the route list: an agent token gets 403 on every other route.

### S2-04 — The CLI token can say yes (high, M2)

server#10 `Policies.kt:36-38`: `CLI_SCOPES` = `workflows:write` and `gates:answer` among others, for 90 days. The CLI
stores it on the developer's machine, under the user the agent runs as. The agent reads it (keychain query or file)
and answers another developer's gate (the self-approval rule does not apply: it is not its own request), or edits
the workflow to drop its gate. The prototype's promise "a device token never says yes" is gone. Fix: no
`workflows:write` nor `gates:answer` in the default CLI scopes; `ostrom approve` opens `/yes` in the browser (passkey)
or confirms each decision on a second device; at most, an org setting allowing CLI answers for low-risk gates only.

### S2-05 — The push diff is the hook's word (high, A7b)

server#8 `AskGateUseCase.kt:90-100,122`: `diff`, `push.refs` and `push.force` come from the request body, written by
the hook on the agent's machine from local refs the agent can move. Before a push, the commits are not on GitHub, so
the server cannot compute the diff then. Fix: (1) the evidence says "diff reported by the developer's machine, not
verified"; (2) the server reads `force` from the command as well (`--force*`, `-f`, `+ref`, `--mirror`, `--delete`,
`:ref`) and ORs it; (3) on GitHub's push webhook, recompute `compare(before…after)`, compare it with the yes's diff
digest and commits, and on mismatch mark the receipt, alert the approvers and turn the `ostrom-gate` check red;
(4) until (3) exists, recommend the pull-request flow for protected branches (the merge diff comes from GitHub).

### S2-06 — A merge yes without a known head (high, A8)

server#8 `AskGateUseCase.kt:81-84`: without `PullRequestHeads` (default `UnknownPullRequestHeads`), an enforced merge is
keyed on `gh pr merge 3` in the session. Commits pushed to the PR after the yes are merged under it. Fix: an enforced
gate on a pull request whose head and base GitHub did not give is refused (503 "GitHub integration required"), never
bound to its text.

### S2-07 — Account takeover through an organization's SSO (high, latent)

server#10 `AccountUseCases.kt:57-66` merges a sign-in into the account that verified the same address, for any
issuer. Signin `OAuthProviders.kt:204-212` passes `email_verified` of an OIDC connection (`connectionId`) unchanged.
An org admin who configures their own IdP can assert `victim@gmail.com`, verified, and sign in as the victim. Fix: an
SSO identity merges only into accounts whose address is in a domain the organization verified (DNS TXT), and is never
used to merge outside it; Google: `email_verified` merges for `gmail.com` or when `hd` matches a verified domain.

### S2-08 — Bearer token in localStorage (high)

web#3 `src/shared/auth/authStore.ts:5-33` keeps `ostrom.token` (`opk_…`, the prototype's operator token) in
`localStorage` and sends it as `Authorization: Bearer`. The server's console credential is the `HttpOnly` session cookie
plus `X-CSRF-Token` (server#10 `TokenAuthentication.kt`, `GET /me` returns the CSRF token). Fix: `credentials:
'same-origin'`, CSRF token from `/me` kept in memory, no secret readable by script.

### S2-09 — Console headers (medium)

web#4 `docker/security-headers.conf` sets `nosniff`, `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy`, no CSP
and no HSTS; `index.html` has an inline theme script. Fix: `Content-Security-Policy: default-src 'self'; script-src
'self' 'sha256-<theme script>'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self';
connect-src 'self' <PostHog host>; img-src 'self' data: https://avatars.githubusercontent.com`, and
`Strict-Transport-Security: max-age=63072000; includeSubDomains` (nginx or Cloudflare); `Cache-Control: no-store` on
the API's `/me` and token responses.

### S2-10 — Untrusted code on ControlResell's nodes (high)

server#9 `KubernetesRuntimeDriver.kt:150-215` sets a good security context but no `runtimeClassName`, `nodeSelector`
nor `tolerations`; helm#1 `values.yaml` `cloudSessions.runner.nodeSelector`/`tolerations` are not passed to the driver;
`networkPolicy.egress.extraDeniedCidrs: []` does not deny ControlResell's public addresses nor the nodes' public
addresses. A container escape lands on a production node of ControlResell. Fix before the Free plan opens cloud
sessions to the public: a dedicated node pool with a taint read by the driver, gVisor (or Kata) `runtimeClassName`,
ControlResell's and the nodes' addresses in `extraDeniedCidrs`; otherwise ship with `cloudSessions.enabled: false`.

### S2-11 to S2-18

- **S2-11** helm#1 passes no `OSTROM_SECRETS_KEY`; server#4 `SecretsKey.kt:42-51` then creates `data/secrets.key` in
  the pod. Fix: chart Secret required when the module is on; never generate the key outside single-user mode; create
  the file with `O_CREAT|O_EXCL` and mode 0600.
- **S2-12** `ostrom_session` → `__Host-ostrom_session` (Secure, Path=/, no Domain); the OAuth state cookie
  `__Secure-`; artifacts on a separate registrable domain.
- **S2-13** the approval page shows the requesting address, place and time, labels the client name as unverified,
  and asks the user to type the code rather than following `verification_uri_complete` for a token with answer scopes.
- **S2-14** an approved yes expires (one hour for a push, configurable), and an enforced gate without its signed
  receipt cannot be reserved (sign at reserve time, fail closed).
- **S2-15** `GateActor.personId` is mandatory for agent and device actors (refuse otherwise); approvers are bound to
  member ids when the workflow is compiled, not compared as text.
- **S2-16** `RevokeAgentTokensUseCase` takes the organization; the index and the `UPDATE` filter on it.
- **S2-17** OIDC connections of organizations: discovery document, issuer equal to the discovered issuer, JWKS
  signature check, HTTPS endpoints only.
- **S2-18** the app pins the instance key id at sign-in and warns when it changes.

## Log

- 30/10 02:50 — first pass: AUTH (identity, sign-in), tenancy, gates, secrets, mcp-relay, runners, billing, web, helm,
  app receipts. CLI under review.
