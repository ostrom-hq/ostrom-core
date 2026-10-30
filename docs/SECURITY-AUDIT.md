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

Second pass at 04:05 (fetch of every branch; heads in the status column). Commits read at the first pass: server `feat/socle` 77a3e76, `feat/identity` 368121a (+ local `feat/identity-signin` 97fc8e1, not
pushed), `feat/gates-storage` 298ee4f, `feat/secrets` 9bf908a, `feat/mcp-relay` c9eea2d, `feat/runners-drivers`
04f2c67, `feat/billing-module` 6011e13; web `feat/api-client`, `feat/yes-screens`, `ci/image`; helm `feat/chart`;
app `main` bd01382.

## Summary

| Id | Severity | Area | Finding | Where | Status |
|---|---|---|---|---|---|
| S2-01 | high | tenancy | RLS does not hold in production: the chart's PostgreSQL user is a superuser (bypasses `FORCE`), gates rely on `FORCE` alone, and secrets, mcp-relay, runners and billing tables have no RLS at all | server#1, #8, #10, #4, #13, #6, #15; helm#1 | **fixed in PR, not on `main`** (07:00): start-up refusal of a superuser, `BYPASSRLS`, table-owning or owner-member role, plus a `migrate` command with the owner's credentials, in server#37 3939d50 (`kernel/…/database/DatabaseRole.kt:8-26`, called in `Main.kt:87`; `TenancyPostgresTest.aSuperuserOrBypassingRoleIsRefusedAtStart`, `theServingRoleCannotTurnIsolationOff`), **CI green** (run 41009225678); `main` still migrates at boot with the serving credentials (`Main.kt:54-55`). Identity `FORCE` in server#38 0e06281 (`IdentityMigrations.kt` migration 3), CI red because its base does not compile. Chart: S2-43. Tables with `org_id` and no RLS: S2-51 | **closed on `main`** (10:45): start-up refusal and `migrate` (#37 4e4137d, `Main.kt:95`), identity `FORCE` (#10 b1f5dc8 `IdentityMigrations.kt:165,172`), roles in the chart (S2-43); server `main` build 41027901782 green; the e2e bench now has to run the image under a non-superuser role (QA 10:32). Routing tables: S2-51 | |
| S2-02 | high | auth | No forwarded-address handling: every sign-in rate limit is keyed on the ingress proxy's address, one bucket for the whole internet (10 `ostrom login` per 10 min, 30 OAuth starts, 10 magic links) | server#1, #10 | **fixed in PR, not on `main`**: server#37 3939d50 reads `X-Forwarded-For` from the right, only from `OSTROM_TRUSTED_PROXIES` (`kernel/…/network/TrustedProxies.kt`, `http/ClientAddress.kt`; `ClientAddressTest` 3 cases), CI green (run 41009225678). **Not effective once deployed**: the chart sets no `OSTROM_TRUSTED_PROXIES` (S2-43), so every client stays the ingress's address | **closed on `main`** (#37, #42 4206ca8 `TrustedProxies`), set in production (`ostrom-infra` `prod/values.yaml:15`), the chart passes it (helm#12 `server-config.yaml:16-17`) | |
| S2-03 | high | auth (A2) | Console routes do not refuse agent or device credentials by construction: gates, secrets, mcp-relay and billing routes check no scope and no credential kind, and lean on three resolvers not written yet | server#8, #4, #13, #15 | **closed on `main`** for secrets (`CallerRoutes.kt:41-44`) and billing (`domain/Access.kt:46`); **fixed in PR** for gates (#8 2efa70c `GateScopes.kt`, every route) and mcp-relay (#13 8816b0b `McpRelayRoutes.kt:78`). Gates CI: all tests pass, single-use ones included, `koverVerify` red at 98.95 % branches (run 41005080269). Still open: receipts routes (S2-47), personal tokens on console writes (S2-52) | **closed on `main`**: gates (#8 fd53222, build 41023848221 green, Kover 100 %), secrets, billing, mcp-relay (#13 be931c0), receipts (#21 f32f7fe, S2-47). Residual: S2-52 | |
| S2-04 | **critical** with S2-31 | auth (A1/A2, M2) | The CLI token carries `gates:answer` and `workflows:write` and lives on the developer's machine, readable by the agent: the agent answers other people's gates or removes its own | server#10 `Policies.kt:36` | **fixed in PR, CI red by its base**: server#38 0e06281 `Policies.kt` (`CLI_SCOPES` without `gates:answer` nor `workflows:write`; `grantedScopes` re-checked at every request, `CredentialAuthenticator.kt:43-50`); feat/identity-signin does not compile (`IdentityModule.kt:151`), so no run judges it. No identity module on `main`: every token is refused there (401) | **closed on `main`** (#10 b1f5dc8 with #38: `Policies.kt:54-66` `CLI_SCOPES` and `grantedScopes`; build 41027901782 green) | |
| S2-05 | high | gates (A7b) | A push's diff, commits and force flag come from the hook, never from GitHub; nothing checks after the push that what landed is what was shown | server#8 `AskGateUseCase.kt:90-122` | **mitigated, open**: the push diff still comes from the developer's machine, labelled "not checked by Ostrom", the yes bound to its digest and the SHAs (#8 2efa70c `AskGateUseCase.kt:162-166,202`); nothing checks it against the SHAs (GitHub webhook comparison not written) | |
| S2-06 | high | gates (A8) | A merge gate whose pull request head is unknown (no GitHub integration yet) binds the yes to the command text: commits pushed after the yes are merged under it | server#8 `AskGateUseCase.kt:81-84`, `DefaultGatePorts.kt:43` | **fixed in PR** (#8 2efa70c `AskGateUseCase.kt:171-191`: PR head read from GitHub, an enforced merge without it is refused); tests green, Kover red (run 41005080269) | **closed on `main`** (#8 fd53222 `AskGateUseCase.kt`, build 41023848221 green) | |
| S2-07 | high (latent) | auth | Accounts merge on any `email_verified`, whatever the issuer: an organization's own OIDC connection can assert a stranger's address and take their account | server#10 `AccountUseCases.kt:57-66`, signin `OAuthProviders.kt:204-212` | **fixed in PR, CI red by its base**: server#38 0e06281 `AccountUseCases.kt` `vouchesFor` (an org OIDC never vouches; `AccountLinkRequiredException`; `LinkIdentityUseCase` from the signed-in account); tests `AccountUseCasesTest`, `AccountSecurityRoutesPostgresTest` | **closed on `main`** (#10 b1f5dc8 `AccountUseCases.kt:41` `vouchesFor`) | |
| S2-08 | high | web | The console keeps a bearer token in `localStorage`; any XSS (agent-written diffs, commands, PR titles are rendered) steals it | web#3 `src/shared/auth/authStore.ts` | **console closed on `main`** (web#15 7543fda, run red 41002251286 then green 41002676894: token in memory only, old `ostrom.token` erased). **Server side not pushed**: local branch `fix/s2-08-session-cookie` f7babf4 (HttpOnly `ostrom_session` + `ostrom_csrf`, `X-CSRF-Token` equal to the session's, `Origin` allow-list, `POST /api/v1/auth/link` single use, `no-store`; `SessionCookiePostgresTest`), waits for #38. web#9 and #10 reintroduce the old store: S2-44 | **console closed on `main`** (web#15); server `main` keeps the session in an HttpOnly cookie with a CSRF header (`TokenAuthentication.kt:43-48`); `Origin` check, `/auth/link`, `no-store` and logout clearing in **#58 00b0fc7, CI run 41031630777 pending** | **closed on `main`** (console web#15; server #58 5c8f826, Build 41031630777 green: HttpOnly session cookie, `X-CSRF-Token` equal to the session's AND `Origin` in the allow-list, `CredentialAuthenticatorImpl.kt:108`; `SessionCookiePostgresTest.kt:87`, `CredentialAuthenticatorTest.kt:211`) | |
| S2-09 | medium | web, headers | No Content-Security-Policy and no HSTS on the console | web#4 `docker/security-headers.conf` | **closed on `main`** (web#15 7543fda: `docker/security-headers.conf:8-9` CSP without inline or eval, `frame-ancestors 'none'`, HSTS; the image's smoke test checks them) | |
| S2-10 | high | cloud | Cloud sessions run anyone's code (Free plan) on ControlResell's production nodes with runc only: no sandbox runtime, `nodeSelector`/`tolerations` not applied by the driver, ControlResell's addresses not denied | server#9, helm#1 | **neutralized by default** (helm `main`: `cloudSessions.enabled: false` from helm#3; helm#7 a510f8b merged, the Cilium policy also denies other namespaces' pods and the nodes); org allow-list not in the server yet; S2-49 holds while it is off | |
| S2-11 | medium | secrets | The chart passes no `OSTROM_SECRETS_KEY`: each pod generates its own key file in its ephemeral filesystem (secrets lost on restart, unreadable by the other replicas); the file is written before `chmod` | server#4 `SecretsKey.kt:42-51`, helm#1 | **fixed** on `main` (helm#6 2958b29: one generated `secrets-key` Secret for every replica, `OSTROM_SECRETS_KEY` refused in plain values; server writes its file `O_EXCL` 0600) |
| S2-12 | medium | auth | Session cookie without the `__Host-` prefix; an artifacts sub-domain of `ostrom.run` could set a parent-domain cookie (session fixation) | server#10 `IdentityRoutes.kt:181` | open, **mitigated**: artifacts are served on `artifacts.ostrom.run` under `sandbox` without `allow-same-origin` (main `ContentRoutes.kt:54-76`), so a page cannot write a parent-domain cookie; the cookie is still `ostrom_session`, not `__Host-` (f7babf4 `TokenAuthenticator.kt:82`). Low until another same-site host serves content | **fixed in PR** (#58: `__Host-ostrom_session` and `__Host-ostrom_csrf` in HTTPS, 8b9a544), CI pending; mitigated on `main` by the artifacts sandbox | **closed on `main`** (#58 5c8f826: `__Host-` prefix over HTTPS, `TokenAuthenticator.kt:119`, `IdentityConfig.kt:30`) | |
| S2-13 | medium | auth | Device-flow phishing: the client name is chosen by the caller and the prefilled `verification_uri_complete` invites a one-click approval of a 90-day token | server#10 `DeviceUseCases.kt:60`, `IdentityRoutes.kt:97` | open |
| S2-14 | medium | gates | An approved yes never expires, and can be reserved and consumed while its receipt is not signed | server#8 `UseGateUseCase.kt:66-77`, `EnsureGateReceiptUseCase.kt` | **open** (#8 2efa70c, #26 d2e7868): reserving or consuming a yes never checks `receiptId`; a failed receipt signature is only logged and the gate stays APPROVED and usable (`UseGateUseCase.kt:86-101`, `EnsureGateReceiptUseCase.kt:52-54`), and the default signer always fails (`DefaultGatePorts.kt:52-55`). A RESERVED yes never expires (`UseGateUseCase.kt:93-100`) | **open on `main`** (`UseGateUseCase.kt` reserves and consumes without a `receiptId`); fixed in **#41 4a7cc9f, CI run 41030362738 pending** | **open on `main`** (`UseGateUseCase.kt`, no `receiptId` check); fixed in **#41** c828f99, **CI red** (run 41036053499: orgs route tests under the serving role, not its own tests) | |
| S2-15 | medium | gates | Self-approval compares free-text identities, and the asker falls back to the credential id when the resolver gives no person: a resolver that forgets `personId` disables the rule | server#8 `AskGateUseCase.kt:77`, `GateRules.kt:189` | **open** (#8 2efa70c): the asker is still `actor.personId ?: actor.id` (`AskGateUseCase.kt:83`) and identities are compared as case-folded free text mixing GitHub logins and member ids (`GateRules.kt:163-180`): a CI key or a device without `personId` lets the developer approve as "another" approver | **open on `main`** (`AskGateUseCase.kt:83` `actor.personId ?: actor.id`); fixed in **#41 4a7cc9f, CI pending** | **open on `main`** (`AskGateUseCase.kt:83`); fixed in **#41**, CI red (same run) | |
| S2-16 | low | auth | Agent tokens are revoked by session id across organizations (session ids are agent strings) | server#10 `TokenUseCases.kt:259`, `CredentialsRepositoriesImpl.kt:130` | open |
| S2-17 | low | auth | Org SSO over OIDC must verify the ID token signature (JWKS from discovery): the token endpoint is chosen by an org admin | signin `OAuthProviders.kt:146-149` | open, before the SSO PR |
| S2-18 | low | app | Receipts are verified against the key served by the same server, without pinning | app `src/repositories/ReceiptsRepository.ts` | open |
| S2-21 | medium | sharing | Shared Claude/Codex accounts: tables without RLS, routes without scope; the shared token must only ever be used server-side | server#17 `SharingMigrations.kt`, `SharingRoutes.kt:48-68` | **fixed in PR** (sharing eb88373: RLS on both tables `SharingMigrations.kt:49`, `accounts:*` scopes `SharingRoutes.kt:47,52`, a test with a row planted in another org); not merged | **closed on `main`** (#17 d515409) | |
| S2-22 | high (contract) | auth | `POST /api/v1/auth/launcher/login` exchanges any GitHub token for an Ostrom device token: a token minted for another OAuth app (or stolen) signs in as its user | core `api/openapi.yaml:314` | **fixed in PR, CI red by its base**: server#38 `LauncherLogin.kt` checks the GitHub token with GitHub's check-token API for Ostrom's own client (`GitHubTokenVerifierImpl.kt`), then requires an existing GitHub identity; `LauncherLoginTest` | **closed on `main`** (#10 b1f5dc8 `LauncherLogin.kt`, GitHub check-token) | |
| S2-23 | medium (contract) | auth | Unauthenticated GitHub App manifest and install callbacks: without a `state` bound to an admin session, anyone completes an instance's App setup | core `api/openapi.yaml:950` | contract fixed (core `main` 2eaa117 `api/openapi.yaml:1173-1190`: the manifest callback carries `state`, `planned`); server not written | |
| S2-24 | low | app | Mobile sign-in returns to the custom scheme `ostrom://auth/callback`, claimable by another app (PKCE and `state` limit it to a denial) | app#6 `GithubSignInImpl.ts:6` | open |
| S2-19 | — | core | Package envelopes ignore `revoked` and `contentDigest`, recursive canonical JSON without a depth limit, duplicated `kid` (review of 02:20) | core `main` | **closed on `main`** (core#8 be5d0fc: `CanonicalJson.kt:38` depth 64; `PackageEnvelopes.kt:21,70` copies `contentDigest` and refuses `revoked`; key looked up by `kid`) | |
| S2-20 | **critical** | CLI | The hook's gate catalogue is a file the agent can write: empty it and date it in the future (age = now − mtime, negative), or delete it and stop the relay → no gate at all; `failMode` is read from that same file | cli#5 `CatalogueStore.kt:24-55`, `Adapters.kt:99-100` | **fixed in code, CI pending**: cli#25 merged into feat/agent-command (dc1f5bf): policy from `GET /api/v1/gates/catalog/signed` checked against the instance keys with its `expiresAt` at every hook (`SignedGatePolicy.kt:70-75`), a local file only adds gates (`GateCatalogue.kt:47`), no trusted policy = everything held; the server always signs `failMode: closed` (server feat/compiler c51b4d7 `GatePolicyUseCase.kt:88`). cli#25's own head 40fcbb0 did not compile (run 41009586035); run of dc1f5bf 41010381546 pending. Instance key pinned on first use from the same server (S2-35) | **closed on `main`** (cli e045905 #5, 6869403 #10, 993e6bf #11: `SignedGatePolicy.kt`, a local file only adds gates; cli `main` CI 41032064220 green on b78f92e) | |
| S2-25 | **critical** (A12) | CLI | The relay token is in the agent's environment (`OSTROM_RELAY_TOKEN`): the agent calls the relay itself (fake green check, consume, end session); the launcher passes the developer's whole environment | cli#5 `Adapters.kt:18-30`, `SessionHooks.kt:29`, `AgentLauncher.kt:37-44` | **fixed in code, CI pending** (feat/agent-command dc1f5bf): the hooks' capability is read from `OSTROM_RELAY_TOKEN_FILE` (0600), never from the agent's env (`Adapters.kt:21`, `HookContext.kt:71`); the agent's `OSTROM_RELAY_TOKEN` is a second `client` capability that opens neither answers nor session end (`LocalRelay.kt:20`). Same-user limit (the agent can read the file) accepted in `SECURITY.md` (decision 06:10) | **closed on `main`** (cli `HookContext.kt:71` reads `OSTROM_RELAY_TOKEN_FILE`; the agent's capability opens neither answers nor session end); same-user limit in `SECURITY.md` | |
| S2-26 | high (A4) | CLI | Commands are matched on raw text: `g\it push origin main`, `git pu''sh`, `kube''ctl apply`, `B=main; git push origin $B`, `$(…)`, git aliases (`git config alias.ship push`), a script written then run | cli#5 `PushReader.kt:177-215`, `GateCatalogue.kt:55-61`, `ToolSubject.kt:34-61` | **partial** (cli#5 ac9c65e): words read as the shell reads them (`GateCatalogue.kt:62`), dynamic program or push settings refused (`GateHook.kt:101-114`); the server-side binding (head SHA + destination, 15 min) is what holds, see S2-29 |
| S2-27 | high (A9) | CLI | Push destination ignores `branch.<b>.pushRemote`, `remote.pushDefault`, `--repo <url>`; redirection only checked when the verb is `push` | cli#5 `PushReader.kt:123,144-146,219`, `GateRequests.kt:129` | **partial** (cli#5 ac9c65e: push settings refused rather than resolved, `GateHook.kt:101-114`) |
| S2-28 | high (A7b) | CLI | The shown diff uses the local remote-tracking ref and `git diff` without `--no-ext-diff --no-textconv`: the agent shapes the diff | cli#5 `Diffs.kt:23-41` | **fixed in code** (cli#5 ac9c65e: `--no-ext-diff --no-textconv`, absolute git via `OSTROM_GIT`); diff still not computed by the server (S2-05) |
| S2-29 | high (A24, A13) | CLI | No reserve at PreToolUse; the yes is kept in `.yes.json` (agent-writable) and consumed at PostToolUse only (a failing command keeps it); `consumed` answer lets the action through | cli#5 `GateHook.kt:96-97`, `SessionIds.kt:59-73`, `GateRequests.kt:113` | **server tests green** (run 41005080269 on #8 2efa70c: `theFirstOfTwoSimultaneousYesWins`, `theFirstOfTwoSimultaneousConsumesWins` → 409, `aPushWaitsForAnotherApproverThenRunsOnce` pass on linuxX64 with PostgreSQL); the job is red on Kover only. CLI side as before (reserve at PreToolUse) | **server closed on `main`** (#8 fd53222: single use, simultaneous yes/consume → 409, tests green) | |
| S2-30 | medium | CLI | Fail-open edges: event over 1 MiB or unreadable → allowed; `git` from `PATH`; `-ud`/`-qd` read as a plain push; no PR number sent (server binds the merge to the text, S2-06); install without mandatory signature check, hook binary writable by the agent | cli#5 `HookRunner.kt:39`, `HookEvent.kt:48-54`, `Adapters.kt:56`, `PushReader.kt:122`; cli#4 `install.sh:62-99` | **fixed in code** (unreadable event refused, `SessionHookProcessor.kt:42`; 16 MiB bound, `HookRunner.kt:36-37`); no green CI yet |
| S2-31 | **critical** | CLI (A1/M2) | The agent says yes to itself: `ostrom approve <id> --yes` answers with the developer's device token, `--yes` skips the question, the binary is first in the agent's `PATH`; with S2-04 (`gates:answer` in the CLI scopes) and `SOLE_APPROVER`, a solo developer's agent approves its own push | cli#16 `ApprovalCommands.kt:103-113`; cli#10 `AgentLaunchUseCase.kt:224-225`; server#10 `Policies.kt:36-38` | **fixed in PR, not proven**: CLI opens /yes only (cli#16); server#38 removes `gates:answer` from every token; gates `requireAnswering` refuses any non-USER principal (#8 `GateScopes.kt:63-66`, `AnswerGateUseCase.kt:78`). Residual: a plain browser session answers without the passkey step-up of the 04:40 decision (S2-54) | **closed on `main`** except S2-54: no token carries `gates:answer` (#10), gates refuse any non-USER principal (#8), the CLI's `approve` is a stub that answers nothing (`PendingCommands.kt:33`) | |
| S2-32 | high | CLI (M2) | The device token is readable by the agent: `credentials.json` 0600 under the same user, `OSTROM_TOKEN` takes precedence | cli#13 `Credentials.kt:37-51` | **open**: no branch keeps the device token in the keychain; feat/login, approvals, update still write `credentials.json` 0600 (`CliDependencies.kt:111`); the `SecretStore` of cli#27 (466d04d, draft, CI pending) is not wired to login. Impact reduced by #38 (the CLI token can no longer answer nor edit workflows); same-user limit accepted (06:10) | **closed on `main`** (cli#27 9e2cb2a system secret store; `ostrom login` #13 eb86d8f stores the token through it, `CliDependencies.kt:109-120`, decision 08:50); without a Secret Service, an encrypted file (same-user limit) | |
| S2-33 | high | CLI (A13) | The launch package is checked once, then `package.json`, `scripts/` and `plugin/` stay writable by the agent; no binding to the repository, no expiry | cli#10 `PackageLayout.kt:20-45`, `PackageTrust.kt:69-104` | **fixed in code, CI pending** (dc1f5bf: package digest re-checked at each hook, `SessionHook.kt:68`) | **closed on `main`** (`SessionHook.kt:68` package digest at each hook) | |
| S2-34 | medium | CLI | Any `/policy` error, 5xx included, lets the launch go on | cli#10 `LaunchCalls.kt:18-21` | **fixed in code** (cli#10 61ba52f: unreadable policy, 5xx included, stops the launch); no green CI yet |
| S2-35 | medium | CLI | Server key pinned on first use from the same server; `OSTROM_URL` chooses the server for login and approve; `verificationUri` shown unchecked | cli#10 `PackageTrust.kt:132-139`; cli#13 `AccountCommands.kt:25`, `DeviceLogin.kt:46` | **fixed in PR** cli#40 c63a3a1 (`DeviceLogin.kt:154-167`: the sign-in page must be GitHub's or the exact https origin of the server or the console, no user-info, no control characters; http only to this machine; the server refused in clear), **CI red** (run of c63a3a1); key still pinned on first use | **closed on `main`** (cli#40 53ea404, CI green: the sign-in page is GitHub's or the exact https origin of the server or console, `DeviceLogin.kt:154-167`); server key still pinned on first use | |
| S2-36 | low | CLI | Files written with the umask then `chmod` (no 0600 creation, no atomic rename) | cli#10 `LaunchFiles.kt:63-66` | **fixed in code** (cli#10 61ba52f: temporary file, chmod while empty, atomic rename) |
| S2-37 | medium (to confirm) | CLI | Codex: org MCP tools auto-approved (`default_tools_approval_mode="approve"`) and `--dangerously-bypass-hook-trust` with a signed package | cli#10 `CodexAdapter.kt:31` | **partial**: auto-approval of MCP tools removed; `--dangerously-bypass-hook-trust` stays (`CodexAdapter.kt:38`@a3dcc45) |
| S2-38 | high | CLI | Partial fail-closed: with the relay stopped (`kill -STOP` on the launcher, same user) and the on-disk `OSTROM_GATES` writable, only the 4 built-in Bash classes stay guarded; org gates (Write/Edit, custom Bash) vanish | cli#5 `GateCatalogue.kt:120-141`, `CatalogueStore.kt:24-25`@39ea7d2 | **fixed in code, CI pending** (dc1f5bf: without a trusted signed policy every action is held, `GateCatalogue.kt:47`); an outage only lets through under an org's `open` mode, which the server never signs | **closed on `main`** (no trusted policy = everything held) | |
| S2-39 | high | CLI | `ostrom update --version <old>` and `--rollback` have no version floor and are not refused in an agent session: the agent downgrades the binary that runs its own hooks to a signed release from before the fixes; the manifest has no expiry | cli#17 `SelfUpdate.kt:78-104`@055150a | **fixed in PR, CI red by its base**: cli#28 30b225a (`SelfUpdate.kt:114-136,263-270` floor from the signed manifest, rollback without floor refused, no update inside an agent session), run 41009642685 red on detekt `DeviceLogin.kt:44` and Kover of the login stack, not on its lines; cli#29 525442a publishes `floor`, **CI green**, not merged. Residual: an older signed `latest.json` replayed with a lower floor (no manifest expiry) | floor **published on `main`** (cli#29 def7ff2); the client check rides with `ostrom update` (feat/update d6270e4 `SelfUpdate.kt:138`), not on `main`: nothing to downgrade with today | |
| S2-40 | medium | CLI | The package catalogue holds for the whole session, without expiry: an org policy tightened mid-session is ignored | cli#9 `RelayConfig.kt:25`, `RelayTarget.kt:60-61` | open |
| S2-41 | medium (process) | CLI | cli#16, #17 and #22 are stacked without the fix commits (ac9c65e, e151ffd, 61ba52f, e46c51a): a release cut from them ships without the fixes | cli#16 3faa367, #17 055150a, #22 f54c544 | **partial**: ac9c65e, 61ba52f, e46c51a and 3faa367 are now in every stacked branch; the S2-20/25/33/38 fix (40fcbb0) is only in feat/agent-command, to merge up the stack (order of 05:58) | **closed**: the stack is merged on cli `main` with the fixes | |
| S2-42 | low | CLI | "Inside a session" guards rest on `OSTROM_LAUNCH_ID`, which the agent can unset; `OSTROM_CONSOLE_URL` can redirect the /yes page | cli#13 `Credentials.kt:38`, cli#16 `ApprovalCommands.kt:96,148-149` | open |
| S2-43 | high | deploy | The chart cannot run the tenancy fix: no `OSTROM_TRUSTED_PROXIES` (S2-02 stays one bucket behind ingress-nginx), no `ostrom_tenant` role and an owner `NOCREATEROLE` while secrets, usage, budgets, runners and identity `SET LOCAL ROLE ostrom_tenant` or create it (migrations fail or every org route errors); `main`'s image has no `migrate` subcommand the chart's init container calls | helm `main` e712ab0 `charts/ostrom/templates/postgresql.yaml:20-34`, `values.yaml:86-88`; server#37 `docs/tenancy.md` | **closed on `main`** (helm#12 c9af9c0, chart 0.3.0: `ostrom_tenant` created, owner `CREATEROLE`, trusted proxies; `tests/s2_43_test.yaml`; CI 41014313754 green) | |
| S2-44 | medium (process) | web | web#9 and web#10 (drafts) are stacked on an `approvals-screens` older than web#15: the token is back in `localStorage` (`authStore.ts:5,28`) and agent-written links render without `safeHref` (`href={commit.url}`) | web#9 cf52e78, web#10 4807df3 | **closed**: web#9 and #10 gone, web `main` 74bcf35 green (41027053565) | |
| S2-45 | high | logs | Access secrets in the request log: the logged path of `/a/{id}/{ticket}/…` holds a one-hour artifact ticket and `/l/{slug}/…` a public link (a permanent access); server#27 exports every log field to Grafana Cloud (CONVENTIONS § 16.4) | `main` `kernel/…/http/RequestLogging.kt:25,30`, `modules/artifacts/…/ContentRoutes.kt:34,42`; #27 ae72163 `TelemetryLogger.kt` | **closed on `main`** (#27 3ab49c5: request lines carry the route template, `RequestLogging.kt:22-35`; build of #27 green 41020423934) | |
| S2-46 | high (DoS) | http | No request body limit in the kernel: `call.receive` reads the whole body before any cap; the runner heartbeat is outside `authenticated` and parses the body before checking the token, so anyone with `Authorization: Bearer x` makes the server parse up to the ingress's 16 MiB per request; import-teamcode takes secrets lists without size or count cap | `main` `modules/runners/…/CloudSessionsRoutes.kt:75-77,109-113`, `ArtifactsRoutes.kt:57`, `WaitsRoutes.kt:58,73`; helm `values.yaml:303`; #28 c2702c8 `ImportRoutes.kt:21` | **open on `main`**; fixed in #50 f59de9e, **CI red** on detekt of automations (run 41029840071) | **open on `main`**; fixed in **#50** bc24b19, CI pending | |
| S2-47 | high | receipts | The three receipts routes check neither scope nor credential kind: an agent token reads every receipt of the org (commands, approvers) | #21 c0f1c44 `ReceiptsRoutes.kt:22-35` (same in #26) | **closed on `main`** (#21 f32f7fe `ReceiptsRoutes.kt:17,44`, run 41020119819 green) | |
| S2-48 | high | sessions | An agent token ends its session with its own `costUsd`, `exitCode` and `source`, signed into the receipt, and reopens it; `POST /sessions` with another launch's session id closes it as "replaced" without an owner check, and its yes is lost; an agent without `launchId` reads every session of its person | #26 d2e7868 `SessionsRoutes.kt:53-56,87`, `CloseSessionUseCase.kt:92,130`, `SessionLifecycleUseCases.kt:41-46`, `SessionSegments.kt:35-40`, `SessionCommands.kt:74-84` | not on `main` (sessions #26 still a draft): blocks #26 only | |
| S2-49 | high (cloud) | runners | In a cloud pod the agent runs as the runner's UID 1000 and reads its token (`/run/ostrom/token` 0400, or `/proc/<runner>/environ` under Docker): it impersonates the runner (heartbeat ready/stopped/model activity, the terminal's runner link) | #16 ab55241 `KubernetesRuntimeDriver.kt:174-176,209-232,298-300`, `runner/Dockerfile:27` | **fixed on `main`** (#59 a3b551d: the agent runs in its own container, away from the runner token); build 41033056466 pending; cloud sessions off anyway | |
| S2-50 | medium | automations | Webhook replay: the delivery key (`Idempotency-Key`, `Ostrom-Delivery`) is outside the signature, so a captured call replays for 5 min under a new key (a new run each time, up to 30 a minute) | #35 fb1f598 `RunAutomationsUseCase.kt:281,289-294` | **partial on `main`** (#35 c572fe0): a delivery is accepted once and a request without a delivery header is keyed on its signed bytes, but `Ostrom-Delivery` is still outside the signature: a captured request replays within 5 min under a new header value (`RunAutomationsUseCase.kt:292-299`) | |
| S2-51 | medium | tenancy | Routing tables carry `org_id` without RLS and are granted to the tenant role: `artifacts_routes` (every org's slugs), `runners_session_routes` (runner token digests), `automations_routes`, `billing_outbox`, `billing_customers`; § 16.1 asks for narrow, tested `SECURITY DEFINER` functions instead. Identity's "account" policies also show every org's tokens to the serving role outside an org (server#38 migration 3) | `main` `ArtifactsMigrations.kt:52-62`, `RunnersMigrations.kt:59-75`, `BillingMigrations.kt:70,88` | **open on `main`**; in #50 (CI red) | **open on `main`**; fixed in **#50** bc24b19, CI pending | |
| S2-52 | medium | auth | The shared `mayUse` helper refuses only DEVICE credentials: a personal token holding `mcp:admin`, `accounts:write`, `inference:admin` or `import:write` passes console write routes without a browser session; answering a wait checks `waits:write` only, never the kind | `main` `modules/secrets/…/CallerRoutes.kt:42-44`, `automations/…/WaitsRoutes.kt:72`, `Callers.kt:26-29` | **open on `main`**; in #50 (CI red); billing writes need a browser session on `main` (#54 f26dc04) | **open on `main`** (billing writes already need a session, #54 f26dc04); fixed in **#50** bc24b19, CI pending | |
| S2-53 | medium | gateway | The session id written to usage comes from a client header: an agent bills its cost to another session of the org; an agent token reads the model policy's `byMember` | #29 e8ea7fd `GatewayRoutes.kt:132`, `RelayInference.kt:227` | **fixed in PR** (#29 fda0a85: the gateway ignores client session headers); gateway not on `main` | |
| S2-54 | medium | gates | A gate is answered by any browser session holding `user:*`: the recent-passkey step-up (≤ 5 min) of the 04:40 decision is not checked by the server | #8 2efa70c `GateScopes.kt:42` | **open on `main`** (a plain session answers); fixed in **#41** (`StepUp.kt`, 403 `StepUpRequired` past 5 min; web#25/#26 handle it), CI pending | **open on `main`**; fixed in **#41** (`StepUp.kt`), CI red (same run) | |
| S2-55 | low | various | An artifact ticket stays valid up to an hour after access is withdrawn (`ViewArtifactUseCase.kt:63`); the client's `traceparent` is trusted, sampling included (#27 `Tracing.kt:101`); terminal tickets in memory without a cap (#14); the EE licence binds to an instance id the self-hosting admin can edit, and a trial is not bound (#19 f408b08) | see cells | open |
| S2-56 | high (latent) | GitHub App | The install callback trusts the `installation_id` in its query: an admin of org A who starts an install (valid `state`) can finish it with the id of an installation another GitHub account made but did not bind yet, and org A then reads that account's pull requests and publishes checks on them. GitHub's advice: confirm the installation with the user's own token (`GET /user/installations`) before binding it | #44 c97c493 (draft) `GitHubInstalls.kt` `finish` | open (PR draft) |
| S2-57 | — (checked) | orgs | Org resolution (decision 10:23): the only membership, else `Ostrom-Org` checked against the membership; a token bound to an org never acts in another; non-USER principals resolve nothing | #64 1a250fc `MembershipOrgResolver.kt` | **holds in code**, CI pending, not on `main` |
| S2-58 | — (checked) | CLI relay | `OSTROM_RELAY_CLIENT` (decision 09:50): the agent's capability opens only the long-poll read of a gate, MCP and inference; the catalogue and hook routes need the hooks capability from the 0600 file; local requests only | cli `main` `RelayTarget.kt:93-98`, `RelayModule.kt:93-99` | **holds on `main`** |
| S2-59 | — (checked) | app | No production code of the app imports the mock server (`src/api/mocks`, `src/test/fakeServices.ts` reached by tests only), but the CI proof asked at 10:12 (`expo export` in production profile + bundle search) is not on `main` | app `main` 8b38da1 | proof missing (APP) |
| S2-60 | low | tenancy | #67's six `SECURITY DEFINER` functions under `ostrom_router`: `search_path` is fixed (`pg_catalog, public`), but EXECUTE is never revoked from `PUBLIC` nor granted to the serving role only, and `pg_temp` is not listed last (the serving role has `TEMPORARY`: it can shadow the tables, but only for its own calls). No cross-org leak in the bodies: each filters on its argument, which the server takes from the credential | #67 4a95c3f `OrgsMigrations.kt` v2 | open (PR, CI pending) |
| S2-61 | — (process) | tenancy | Server PostgreSQL tests on `main` bypass RLS (they do not run as the serving role): a cross-org path that works in CI fails under `ostrom_app` in production (`POST /api/v1/orgs` → 500, QA 10:56). Fail-closed (no leak), but untested. RLS-ROLE pass running, no PR yet | QA 10:56, orchestrator 11:02 | open |

## The prototype's holes in the new code

| Hole | Verdict on the new code |
|---|---|
| A1 (an open route recreates another's account and token) | No such route: accounts come only from a sign-in, tokens from a browser session or a device flow approved by one (`IdentityRoutes.kt:137-169`). Not regressed; re-check when the orgs module adds members and invitations. |
| A2 (console writes without credentials, or with a device token) | Kernel answers 401 without credentials; `requireScope` exists; but the module routes do not use it (S2-03), and the CLI token has the console scopes (S2-04). **Partly regressed.** |
| A3 (no token = the console operator) | Gone: no fallback, `authenticated` answers 401. Closed. |
| A4, A6, A9 (options before the verb, redirection, push to main without naming it) | Server side: `GateCommands.redirected` refuses the A6 forms (`GateRules.kt:213-227`). Hook side: S2-26, S2-27 (still open). |
| A5 (a yes on a push covers a force-push) | `force` is in the key (`GateRules.kt:51`) but read from the request body, not from the command (S2-05). |
| A7 (a yes for a harmless diff covers the real one) | The yes is bound to the shown digest and refused when it changed (`AnswerGateUseCase.kt:88`). Closed for the binding; the diff itself is still the hook's (A7b, S2-05). |
| A8 (merge key without base or gate) | Base and gate node are in the key, head and base come from GitHub (`GateRules.kt:38-43`), except when GitHub is not reachable or configured (S2-06). |
| A10, A11, A17 (session writes by another, workflow receipt, session id reuse) | The sessions module is not written; gates check the launch (`UseGateUseCase.kt:62`). To re-check with the sessions PR. |
| A12 (the agent holds the user's token) | The server issues agent tokens with the single `agent:session` scope (`TokenUseCases.kt:219-234`, `CredentialAuthenticator.kt:39-45`). Hook side: **reopened**, S2-25. |
| A14 (the receipt does not sign what was approved) | The receipts signer is not written (`UnavailableGateReceiptSigner`). Requirement: the gate receipt signs repo, refs and shas, remote, force, diff digest, PR head and base. |
| A24 (two parallel tool calls use one yes) | Server closed: reserve under the gate's row lock, then consume (`UseGateUseCase.kt:59-85`). The CLI does not call reserve yet: S2-29. |

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

- **S2-21** sharing tables get the S2-01 treatment and the routes the S2-03 one; the shared access and refresh
  tokens (already sealed) are used by the gateway and the org's cloud pods only, never returned to a member.
- **S2-22** the launcher login checks that the GitHub token was issued to Ostrom's own client
  (`POST /applications/{client_id}/token` with the client credentials) before trusting `/user`; otherwise drop the
  route for the device flow, which already exists.
- **S2-23** manifest and install callbacks carry a `state` created by a signed-in owner's session and consumed once;
  before the first owner exists, only the bootstrap setup link may start them (CONVENTIONS § 16.2).
- **S2-24** prefer an App Link / Universal Link (`https://app.ostrom.run/auth/callback`) on both platforms.

- **S2-20** the catalogue comes signed by the server (or only through the relay), its age is the server's signed
  date, never a file's mtime; a catalogue built into the binary is the fallback and it is closed; `failMode` is
  served signed, never read from a local file.
- **S2-25** the relay authenticates the hook process, not a variable: a Unix socket 0600 in a directory the agent
  cannot reach, or a per-call one-time token; `/checks` and `/sessions/*/end` only from the launcher. The launcher
  passes an allow-list of variables to the agent.
- **S2-26** classify on normalized words (core `ShellParser`, quotes and escapes removed, `$(…)` and `bash -c`
  expanded); a dynamic word (`$`, backtick, glob) in a gated command = unreadable → refused; resolve git aliases
  (refuse `!` aliases); `git config` writes to `alias.*`, `remote.*`, `url.*`, `push.*`, `branch.*` are gated;
  Write/Edit contents go through the same classifier.
- **S2-27** git's own precedence for the push remote (`pushRemote`, `pushDefault`, `remote`); `--repo` is a remote;
  redirection checks on every git call.
- **S2-28** `git ls-remote` before the diff; `git -c diff.external= diff --no-ext-diff --no-textconv`; long term the
  server computes the diff (S2-05).
- **S2-29** reserve at PreToolUse (refuse if the reservation fails), consume at PostToolUse or on failure, treat
  `consumed`/`reserved` as a refusal; no yes state the agent can write.
- **S2-30** refuse at PreToolUse on an oversized or unreadable event; absolute `git` path fixed at launch;
  grouped short options; send the PR number; cosign check mandatory in `install.sh`, hook binary owned by another
  user or checked by digest at each launch.

- **S2-31** the server refuses `/approvals/*/answer` from a device or CLI token (no `gates:answer` in `CLI_SCOPES`); a yes
  comes from a browser session with a passkey, or the phone app with biometrics. The CLI refuses `approve` inside an
  `ostrom` session and loses `--yes`. Until then, `SOLE_APPROVER` must not apply to a yes answered by a CLI token.
- **S2-32** system keychain with a per-binary ACL, short-lived device token, minimal scopes; ignore `OSTROM_TOKEN`
  inside an agent session.
- **S2-33** the hook re-checks the package signature and expiry at each load and runs scripts from a verified digest.
- **S2-34 to S2-37** fail closed on `/policy` errors; ship the instance key fingerprint with `ostrom login`; create
  files 0600 and rename; never auto-approve MCP tools for Codex.

Notes from the second pass (not holes): server observability records route templates only, never the path, query or
headers (server#27 `Tracing.kt`); model prompts reach Langfuse only when the org turns content capture on (core#7
`genai/ModelCall.kt`, `ContentCapture` off by default); PostHog in web#7 waits for consent, sends path without
query, no IP geolocation; CLI telemetry (cli#8) is off by default and sends no arguments, paths or repo. The
retention and sweep policies (`mcp_relay_calls_retention_read`, `sessions_sessions_sweep`) open every org's rows
to a transaction that sets a custom setting: acceptable because `ostrom.org_id` is just as settable, RLS here
guards against a forgotten `WHERE`, not SQL injection. Orgs, GitHub webhook, Slack and artifacts modules are still
empty stubs (`*Module.kt` only): to audit when written.

## Fourth pass (30/10, 07:00): what holds, and the noon blockers

Read by `git fetch` at 06:45 and 07:00: server `main` 87f9d14, #37 3939d50, #38 0e06281, local `fix/s2-08-session-cookie`
f7babf4, #8 2efa70c, #13 8816b0b, #17 eb88373, #19 f408b08, #21 c0f1c44, #26 d2e7868, #27 ae72163, #28 c2702c8,
#29 e8ea7fd, #35 fb1f598, #9/#14/#16 ab55241; cli feat/agent-command dc1f5bf, #25 40fcbb0, #27 466d04d, #28 30b225a,
#29 525442a; web `main` 7543fda; helm `main` e712ab0; core `main` 2eaa117.

**What holds, proven.** On server `main`, without an identity module, every token is refused (401), so no org route
is reachable today. Merged modules: artifacts (Ed25519 ticket checked before any read, content on its own host under
`sandbox` without `allow-same-origin`, RLS, scopes), usage and budgets (cost never declared by the client, 1 MiB
and 413, RLS, scopes), billing (Stripe webhook read up to 256 KiB then 413, HMAC over `t.payload`, 5 min tolerance,
constant-time compare, refused without a secret, events deduplicated; routes need a USER session and the billing
scope), secrets (DEVICE refused, scopes), runners (runner token stored hashed, bound to session and epoch; RLS),
waits (only the assignee or an admin in person answers). Observability (#27) puts method, route template and status
on spans, never path, query or headers, and the server sends nothing to PostHog. The EE licence (#19) verifies an
Ed25519 signature against keys pinned in the code before reading the payload. Gates (#8) single use is proven by
integration tests on PostgreSQL (two yes, two consumes → 409), a yes expires after 15 min, the shown digest covers
repo, refs, SHAs, remote, force, diff digest, PR head and base. Console (web `main`): token in memory only, strict CSP,
HSTS.

**Verdict at 07:00: NO-GO** for public sign-up, the console and `ostrom claude`. Nothing a stranger can reach on
`main` breaks isolation today, but only because no one can sign in: every fix that the launch needs is still in a pull
request, and most are not proven by a green run. The site and the docs (no server state) are not a security concern.

Blockers for noon, each with its owner:

1. **AUTH-REPRISE, SEC-FIX-AUTH** — the identity stack green and merged: feat/identity-signin compiles, then #38
   (S2-04, S2-07, S2-22, S2-01 identity), then `fix/s2-08-session-cookie` pushed and merged (S2-08 server side), then
   #18 and #10. Without it there is no sign-in at all.
2. **SERVER-SOCLE** — merge #37 (green: S2-01 start-up refusal, S2-02, `migrate`); kernel request-body limit and the
   heartbeat read after the token check (S2-46, with CLOUD); log the route template instead of the path for artifact
   content (S2-45, with CLOUD).
3. **DEPLOY** — chart aligned with #37 (S2-43): `OSTROM_TRUSTED_PROXIES` set to the ingress's CIDR, the
   `ostrom_tenant` role created and granted to the serving role, `migrate` in the init container only with an image
   that has it.
4. **GATES** — #8 green (Kover 98.95 % branches); S2-14 (no usable yes without its signed receipt) and S2-15 (no
   fallback to the credential id, approvers as member ids) before the "no push without someone else's yes" promise
   is made; before #21 and #26 merge: S2-47 and S2-48.
5. **CLI-AGENTS, SEC-FIX-CLI** — run 41010381546 of feat/agent-command dc1f5bf green, the stack merged up in the
   05:58 order (S2-20, S2-25, S2-33, S2-38, S2-41), #28 and #29 merged (S2-39). `ostrom claude` does not ship
   without them. S2-32 stays open under the accepted same-user limit.

Not blocking at noon if their pull requests stay unmerged or the feature stays off: S2-49 (cloud sessions off),
S2-50 (#35), S2-53 (#29), S2-44 (web drafts). Open on `main` but medium: S2-51, S2-52; to fix in the week.

## Final pass (30/10, 11:11): security verdict for noon

Read on `main` (server ca58668, CLI after cli#40, app 8b38da1).

- **Critical**: none open on `main`. S2-01 to S2-04, S2-06 to S2-08, S2-12, S2-22, S2-29 and S2-49 are closed by merged PRs, each with its test (Summary table).
- **High, still open on a served path**:
  - S2-15 (a gate's asker falls back to the credential's id: self-approval) and S2-14 (the yes is not bound to the receipt), with S2-54 (step-up): fixed in **#41**, CI red on the orgs route tests under the serving role, not on its own tests. Owner: GATES.
  - S2-46 (unauthenticated runner heartbeat with no body limit): fixed in **#50**, CI pending. Owner: SEC-FIX-SERVER.
- **High, not served**: S2-56 (#44 draft), S2-39 (client-side floor check only in cli#17).
- **#67**: the six `SECURITY DEFINER` functions under `ostrom_router` fix `search_path`, and no body leaks across organizations (each filters on its argument, taken from the credential; membership is checked afterwards). EXECUTE is still granted to `PUBLIC` and `pg_temp` is not last: S2-60, low.
- **RLS-ROLE**: no PR yet. On `main` the PostgreSQL tests bypass RLS (S2-61): a fail-closed regression is invisible to CI.

**Verdict: conditional go.** Conditions:

1. #41 merged green, or the "no push without someone else's yes" promise removed from the launch.
2. #50 merged green, or the runner heartbeat route not exposed publicly at noon.
3. #67 merged, so that creating an organization works under the serving role.

Without 1 and 2, it is a no-go for gates and for the runner's exposure.

## Log

- 30/10 02:50 — first pass: AUTH (identity, sign-in), tenancy, gates, secrets, mcp-relay, runners, billing, web, helm,
  app receipts. CLI under review.
- 30/10 02:55 — sharing (server#17), terminal relay (server#14: bearer or single-use ticket, no cookie, no cross-site
  WebSocket hijacking; good), app sign-in (app#6), the contract's unauthenticated operations.
- 30/10 02:57 — CLI review (cli#5 `5814c9d`, cli#4 `61cb0e1`): two critical holes (S2-20, S2-25) and five more.
- 30/10 04:05 — second pass: S2-06 and S2-21 fixed in code, S2-01, S2-03, S2-05, S2-11, S2-25 partial, S2-10
  neutralized by default; new S2-31 (critical) to S2-37. Nothing proven red-then-green in CI yet (server PRs are drafts or pending).
- 30/10 05:20 — third pass: CLI fixes read (S2-28, S2-30, S2-34, S2-36 fixed in code; S2-20 and S2-32 deviate from the
  04:40 decision); gates CI red (run 41000013417); new S2-38 to S2-42. No fix has a green CI run nor a red-before run yet.
- 30/10 07:00 — fourth pass: S2-09 and S2-19 closed on `main`, S2-08 closed on the console; S2-01 and S2-02 fixed in
  #37 with a green run; S2-03 (secrets, billing) closed on `main`; identity fixes (#38) and CLI fixes (dc1f5bf) not yet
  proven; S2-14 and S2-15 still open; new S2-43 to S2-55. Verdict NO-GO, blockers above.
- 30/10 11:11 — final pass: statuses read on `main`; #67 reviewed (S2-60), RLS-ROLE noted (S2-61); conditional go (#41, #50, #67).
