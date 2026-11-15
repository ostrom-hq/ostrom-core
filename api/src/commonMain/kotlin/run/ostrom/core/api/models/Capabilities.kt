// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * What a server speaks and serves: the answer of `GET /api/v1/capabilities`, read by every client after signing in.
 *
 * @property apiVersion The version of the `/api/v1` contract the server speaks; changes only on a breaking change.
 * @property serverVersion The server's release version (`1.4.0`, or `0.0.0-edge.<time>.<sha>` for a build of `main`).
 * @property features The keys of the features served and configured on this instance ([ServerFeatures]); a client
 *   ignores a key it does not know.
 */
@Serializable
public data class Capabilities(
    val apiVersion: Int,
    val serverVersion: String,
    val features: List<String>,
) {
    /** Whether this version of ostrom-core speaks the server's [apiVersion]. */
    public val isSupported: Boolean get() = apiVersion in ServerFeatures.SUPPORTED_API_VERSIONS

    /**
     * Whether the server serves a feature.
     *
     * @param feature A key of [ServerFeatures].
     */
    public operator fun contains(feature: String): Boolean = feature in features
}

/**
 * The API versions this ostrom-core speaks and the feature keys of [Capabilities.features]. A key is `<domain>.v<n>`:
 * a new version of a feature gets a new key, listed beside the old one while servers still serve both.
 */
public object ServerFeatures {
    /** The `/api/v1` contract version of this ostrom-core, served by a server built on it. */
    public const val API_VERSION: Int = 1

    /** The API versions a client built on this ostrom-core speaks. */
    public val SUPPORTED_API_VERSIONS: Set<Int> = setOf(API_VERSION)

    /**
     * The instance's administration (`/api/v1/admin/...`): its administrators and its settings sections, stored in
     * PostgreSQL with their secrets sealed, set in the console instead of the environment or the Helm chart.
     */
    public const val ADMIN_SETTINGS: String = "admin.settings.v1"

    /** Shared artifacts, their viewer and their MCP server. */
    public const val ARTIFACTS: String = "artifacts.v1"

    /** The audit log. */
    public const val AUDIT: String = "audit.v1"

    /** Automations and their triggers. */
    public const val AUTOMATIONS: String = "automations.v1"

    /** Billing (plans, checkout, portal): only with Stripe configured. */
    public const val BILLING: String = "billing.v1"

    /** Spend budgets. */
    public const val BUDGETS: String = "budgets.v1"

    /**
     * Environment variants: named values for environment variables, team (`/api/v1/environment/variants`, assigned
     * by administrators) or personal (`/api/v1/me/environment/variants`), and the caller's own values
     * (`GET /api/v1/environment`) that `ostrom run claude` gives its agent.
     */
    public const val ENVIRONMENT: String = "environment.v1"

    /**
     * The organization's live event feed (`GET /api/v1/events`): server-sent events with a gap-free sequence, resumed
     * from `Last-Event-ID`, kept 7 days.
     */
    public const val EVENTS: String = "events.v1"

    /** Gates, approvals and checks. */
    public const val GATES: String = "gates.v1"

    /** The GitHub App integration: only with a GitHub App configured. */
    public const val GITHUB: String = "github.v1"

    /**
     * Members' GitHub links (`/api/v1/github/link`, `/api/v1/github/links`): the account each member's cloud sessions
     * use for Git and `gh`. Only with an instance App whose OAuth client is known.
     */
    public const val GITHUB_LINK: String = "github.link.v1"

    /** Groups and governance policies. */
    public const val GOVERNANCE: String = "governance.v1"

    /** The import of a TeamCode instance. */
    public const val IMPORT_TEAMCODE: String = "import.teamcode.v1"

    /** The inference gateway (provider accounts, messages, responses). */
    public const val INFERENCE: String = "inference.v1"

    /**
     * The admission queue of the inference gateway, shared by every replica: a call waits for a busy shared account or
     * the member's concurrency limit (`InferenceSettings.memberConcurrency`) instead of being refused, in turns between
     * members, `x-ostrom-workload` `interactive` before `background`; `GET /api/v1/inference/queue`.
     */
    public const val INFERENCE_QUEUE: String = "inference.queue.v1"

    /**
     * The cancellation of an inference call by its `x-ostrom-client-request-id`
     * (`POST /api/v1/inference/requests/{id}/cancel`), on whichever replica runs it.
     */
    public const val INFERENCE_CANCEL: String = "inference.cancel.v1"

    /**
     * The agent memory (`/api/v1/memory`, TeamCode's docs/memory.md): the organization's entries and each member's
     * personal ones, an index and a search, read on demand, the `memory:use` scope, agents' team entries as proposals
     * their session's owner answers, and the curation an administrator turns on, with its review queue and the restore
     * of the entries it superseded.
     */
    public const val MEMORY: String = "memory.v1"

    /** The MCP relay (servers, assignments, policies). */
    public const val MCP: String = "mcp.v1"

    /**
     * The members' personal MCP servers (`/api/v1/me/mcp/servers`), relayed to their owner alone, and the team's
     * `memberHostedMcp` setting (TeamCode #269).
     */
    public const val MCP_PERSONAL: String = "mcp.personal.v1"

    /** Notifications. */
    public const val NOTIFICATIONS: String = "notifications.v1"

    /**
     * Push notifications to the mobile app: the server takes a phone's FCM registration token with its P-256 public
     * key (`POST /api/v1/push-devices`, `provider: fcm`) and encrypts every push for that key (`PushPayload`), sent
     * through the provider an administrator chose in the instance's `push` settings (nothing while it is `off`).
     */
    public const val NOTIFICATIONS_PUSH: String = "notifications.push.v1"

    /**
     * Any member signed in interactively may send `POST /api/v1/notifications/push/test` to their own phones.
     */
    public const val NOTIFICATIONS_PUSH_TEST: String = "notifications.push.test.v1"

    /** Session packages and their compilation. */
    public const val PACKAGES: String = "packages.v1"

    /** Signed receipts. */
    public const val RECEIPTS: String = "receipts.v1"

    /** Reports and analysis. */
    public const val REPORTS: String = "reports.v1"

    /** Rules. */
    public const val RULES: String = "rules.v1"

    /** Runners (machines registered by members). */
    public const val RUNNERS: String = "runners.v1"

    /** Cloud runners started by the server: only with a runtime driver configured. */
    public const val RUNNERS_CLOUD: String = "runners.cloud.v1"

    /**
     * A cloud session's agent token is bound to its cloud session: the todo (`/api/v1/todos/...`), the waits and their
     * MCP server, the wake, the team memory's proposals and a workflow step's delivery act on the cloud session the
     * token's launch runs in, whatever session the call names, and need none. The runner connects the agent to the
     * waits, the todo and the workflow delivery only when the server announces it, so that a server without it is
     * never called with a token it cannot place. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_AGENT: String = "runners.cloud.agent.v1"

    /**
     * Conversation sessions (`mode: conversation`, claude and codex): the agent runs headless in the pod, its
     * normalized transcript is paged and streamed (`/api/v1/cloud/sessions/{id}/events`), members send messages and
     * interrupts, and the runner speaks the agent channel (`/api/v1/cloud/runner/agent|events|inputs|inputs/ack`).
     * TeamCode's cloud.transcript.v1 and cloud.messages.v1. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_CONVERSATION: String = "runners.cloud.conversation.v1"

    /**
     * Permission requests of a conversation session are relayed to members and answered through
     * `POST /api/v1/cloud/sessions/{id}/permissions`; the session view carries `waiting` (TeamCode's
     * cloud.permissions.v1 and cloud.sessions.waiting.v1).
     */
    public const val RUNNERS_CLOUD_PERMISSIONS: String = "runners.cloud.permissions.v1"

    /** The owner's standing approval of a session's tool uses, `PUT .../permission-bypass` (TeamCode's Bypass). */
    public const val RUNNERS_CLOUD_PERMISSIONS_BYPASS: String = "runners.cloud.permissions.bypass.v1"

    /** Files sent with a message: `POST .../files`, `fileIds`, `GET /api/v1/cloud/files/policy`. */
    public const val RUNNERS_CLOUD_FILES: String = "runners.cloud.files.v1"

    /** The agent's background tasks as `tasks` events, and `POST .../tasks/stop`. */
    public const val RUNNERS_CLOUD_TASKS: String = "runners.cloud.tasks.v1"

    /** Live agent text: `message.agent.delta` events, replaced by their final `message.agent`. */
    public const val RUNNERS_CLOUD_TRANSCRIPT_LIVE: String = "runners.cloud.transcript.live.v1"

    /** Per-agent feeds: events carry their `feed`, `events?feed=` pages one, `GET .../agents` lists the subagents. */
    public const val RUNNERS_CLOUD_TRANSCRIPT_FEEDS: String = "runners.cloud.transcript.feeds.v1"

    /** The owner changes the agent's model while it runs, `PUT .../model`. */
    public const val RUNNERS_CLOUD_MODEL: String = "runners.cloud.model.v1"

    /** The owner changes the agent's effort while it runs, `PUT .../effort`. */
    public const val RUNNERS_CLOUD_EFFORT: String = "runners.cloud.effort.v1"

    /**
     * A cloud session changes owner (TeamCode's cloud.transfer.v1): `GET /api/v1/cloud/transfer-recipients`,
     * `POST /api/v1/cloud/sessions/{id}/transfer` (with an `Idempotency-Key`) and the `/api/v1/cloud/transfers` routes.
     * The session pauses first, the owner changes at the paused boundary, the workspace is marked untrusted
     * (`untrustedWorkspace` of the runner's environment), and executions, inference and hours that follow belong to the
     * recipient. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_TRANSFER: String = "runners.cloud.transfer.v1"

    /**
     * Cloud sessions use their owner's GitHub link: the runner's `POST /api/v1/cloud/runner/github`, the session's
     * `githubAccess` and `PUT /api/v1/cloud/sessions/{id}/github`. Only with cloud runners and [GITHUB_LINK].
     */
    public const val RUNNERS_CLOUD_GITHUB: String = "runners.cloud.github.v1"

    /**
     * The preparation recipe of cloud sessions (the session's `environment`: repositories, working directory, setup
     * commands, required variables, Git identity) and the runner's `POST /api/v1/cloud/runner/environment` and
     * `POST /api/v1/cloud/runner/preparation`. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_ENVIRONMENT: String = "runners.cloud.environment.v1"

    /**
     * The agent of a cloud session runs with its owner's environment variables: the runner's
     * `POST /api/v1/cloud/runner/variables`. Only with cloud runners and [ENVIRONMENT].
     */
    public const val RUNNERS_CLOUD_VARIABLES: String = "runners.cloud.variables.v1"

    /**
     * The agent of a cloud session starts with its owner's delivered skills, installed under the agent's UID: the
     * runner's `POST /api/v1/cloud/runner/skills` and `POST /api/v1/cloud/runner/skills/artifact`. Only with cloud
     * runners and [SKILLS].
     */
    public const val RUNNERS_CLOUD_SKILLS: String = "runners.cloud.skills.v1"

    /**
     * A Claude Code agent of a cloud session starts with its owner's delivered team hooks, without a member approval
     * in the pod: the runner's `POST /api/v1/cloud/runner/hooks` and `POST /api/v1/cloud/runner/hooks/artifact`. Only
     * with cloud runners and [HOOKS].
     */
    public const val RUNNERS_CLOUD_HOOKS: String = "runners.cloud.hooks.v1"

    /**
     * A cloud session's creation takes an `Idempotency-Key` as TeamCode's: the same key and request replay the session
     * (`202`, `Idempotency-Replayed: true`), the same key with another request is 422 `idempotency_conflict`; records
     * are kept 24 hours. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_IDEMPOTENCY: String = "runners.cloud.idempotency.v1"

    /**
     * The agent of a cloud session reaches models through its runner's loopback proxy, as a local `ostrom run` does:
     * the runner asks `POST /api/v1/cloud/runner/inference` for an agent token of its execution's launch
     * (`agent:session`), lasting one hour, renews it after a third of its lifetime, and the agent only holds the
     * proxy's local token (TeamCode's `cloud.inference.v1`). Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_INFERENCE: String = "runners.cloud.inference.v1"

    /**
     * A cloud session write takes `Ostrom-On-Behalf-Of` as TeamCode's `TeamCode-On-Behalf-Of`: an owner or an admin
     * signed in, or an administrator's `cloud:admin` service key, creates, pauses, resumes or deletes a member's
     * session, named by id or login (403 `on_behalf_forbidden`, 422 `invalid_effective_member`, 400
     * `invalid_on_behalf`); a session records `createdBy`, and `listAudit` the effective member and the credential of
     * each `cloud.session.*` entry. A server without it ignores the header. Only with cloud runners.
     */
    public const val RUNNERS_CLOUD_ON_BEHALF: String = "runners.cloud.onbehalf.v1"

    /**
     * A cloud session runs on an organization's cloud profile: `profileId` and `image` on
     * `POST /api/v1/cloud/sessions`, `profile` and `image` on the session, and the profile's cluster and namespace
     * checked again at each start. Only with cloud runners and [CLOUD_ADMIN].
     */
    public const val RUNNERS_CLOUD_PROFILES: String = "runners.cloud.profiles.v1"

    /**
     * Cloud sessions are kept and cleaned up as TeamCode's: a paused session expires after the instance's
     * `pausedRetentionDays` (the session's `expiresAt`, `terminatedReason` `expired`), its workspace deletion is proven
     * down to the provider volume (`workspaceDeletion`), a profile whose storage class keeps volumes is refused
     * (`storage_class_retains_volumes`, `storage_class_missing`), and a garbage collection deletes what no session
     * accounts for (the cluster's `orphanedVolumes`, `lastGarbageCollectionAt`). Only with cloud runners on Kubernetes.
     */
    public const val RUNNERS_CLOUD_RETENTION: String = "runners.cloud.retention.v1"

    /**
     * Team hooks: immutable versions published by an administrator or a CI key (`hooks:publish`), pinned only by an
     * administrator in the console, members' decisions and the audience, and the delivery routes (`hooks:read`);
     * each member approves each version on their computer.
     */
    public const val HOOKS: String = "hooks.v1"

    /** Organization secrets. */
    public const val SECRETS: String = "secrets.v1"

    /** Agent sessions. */
    public const val SESSIONS: String = "sessions.v1"

    /**
     * Whether a session's agent works or waits for a person, reported by its hooks (`POST
     * /api/v1/sessions/{id}/activity`) and shown on the sessions list (`Session.activity`), as TeamCode's
     * `team.runs.activity.v1`. A launcher reports nothing to a server without it.
     */
    public const val SESSIONS_ACTIVITY: String = "sessions.activity.v1"

    /**
     * Labels on a launch (`createLaunch.labels`), copied onto its sessions (`Session.labels`) and filtered by
     * `listSessions?label=key=value`, as TeamCode's `team.runs.labels.v1`. A CLI refuses `--label` against a server
     * without it rather than dropping the labels.
     */
    public const val SESSIONS_LABELS: String = "sessions.labels.v1"

    /**
     * Limits of one launch (`GET`/`PUT /api/v1/launches/{launchId}/limits`): a lifetime budget, a concurrency cap and a
     * pause that bind the model calls of the launch's agent token, as TeamCode's run policy.
     */
    public const val LAUNCHES_LIMITS: String = "launches.limits.v1"

    /**
     * The wide catalogue of service-key scopes: a key may hold `inference:use`, `mcp:use`, `usage:read`, `cloud:read`,
     * `cloud:write`, `cloud:admin`, `artifacts:read`, `artifacts:write` and `team:read` besides the CI's
     * `sessions:write` and `rules:read`, and acts as its creator within them.
     */
    public const val SERVICE_KEY_SCOPES: String = "servicekeys.scopes.v1"

    /**
     * Team skills (TeamCode's skill catalog): immutable versions published by an administrator or a CI key
     * (`skills:publish`, `skills:pin`, `skills:assign`), a pinned version per skill, members' decisions and the
     * audience, members' personal skills, the delivery routes (`skills:read`) and workstation installation reports.
     */
    public const val SKILLS: String = "skills.v1"

    /** Sharing with other members and organizations. */
    public const val SHARING: String = "sharing.v1"

    /**
     * Refresh ownership of shared accounts, as TeamCode: an imported account waits for a handover
     * (`POST /api/v1/accounts/{id}/handover`) or serves access-only, and the TeamCode import's `accessOnly` trial.
     */
    public const val SHARING_HANDOVER: String = "sharing.handover.v1"

    /**
     * The Slack integration is in the server (planned): a member's Slack link (`/api/v1/slack/link`, whose
     * `configured` says whether an administrator set the `slack` settings section's bot token and signing secret),
     * the `slack` notification channel, and yes requests with buttons answered at `/webhooks/slack`.
     */
    public const val SLACK: String = "slack.v1"

    /**
     * The Discord integration is in the server (planned): a member's Discord link (`/api/v1/discord/link`, linked by
     * Discord's OAuth2 `identify`, whose `configured` says whether an administrator filled the `discord` settings
     * section), the `discord` notification channel, and yes requests with a Reject button answered at
     * `/webhooks/discord`.
     */
    public const val DISCORD: String = "discord.v1"

    /**
     * A session's todo: the plan reference and its items with typed end conditions, the Stop hook's routes and the
     * per-member Stop hook switch.
     */
    public const val TODOS: String = "todos.v1"

    /** Usage metering and its events. */
    public const val USAGE: String = "usage.v1"

    /** Waits (human answers asked by agents). */
    public const val WAITS: String = "waits.v1"

    /**
     * The wake of an agent's session by the answers to its waits (`POST /api/v1/waits/wake`): a launch's Stop hook
     * holds its agent while a wait is open and hands it the answer.
     */
    public const val WAITS_WAKE: String = "waits.wake.v1"

    /**
     * Waits that resolve by themselves (`resolver` `time` or `event`, the agent's `wait_for`), and the event address
     * of an `event` wait (`POST /api/v1/waits/{id}/event`).
     */
    public const val WAITS_TIMED: String = "waits.timed.v1"

    /**
     * `secret` fields of a wait (an agent's `ask_human`): the value is sealed, never returned, and reaches the agent
     * as `OSTROM_WAIT_<NAME>` (Claude Code only); `file` fields.
     */
    public const val WAITS_SECRETS: String = "waits.secrets.v1"

    /**
     * Outgoing webhooks (`/api/v1/webhooks`): the feed's events POSTed to the organization's services, signed
     * `Ostrom-Signature: v1=` (HMAC-SHA256), retried with backoff, with a delivery log, a test and a secret rotation.
     */
    public const val WEBHOOKS: String = "webhooks.v1"

    /** Workflows and their runs. */
    public const val WORKFLOWS: String = "workflows.v1"

    /**
     * An agent node ends by delivering: `deliverRun`, `deliverWorkflowSession`, `pendingWorkflowSession` and the
     * `ostrom-workflow` MCP server (`workflowsMcp`), with the agent token's `workflow:session` scope.
     */
    public const val WORKFLOWS_DELIVER: String = "workflows.deliver.v1"

    /** Who may decide workflows (`getWorkflowSettings`, `saveWorkflowSettings`). */
    public const val WORKFLOWS_SETTINGS: String = "workflows.settings.v1"

    /** The enterprise license (ee build only). */
    public const val LICENSE: String = "license.v1"

    /**
     * TeamCode's editable API price table (ADR 0002 step 1): the organization's per-model prices
     * (`GET`/`PUT /api/v1/policy/api-prices`, `POST .../load-defaults`).
     */
    public const val CAPACITY_API_PRICES: String = "capacity.apiprices.v1"

    /**
     * TeamCode's temporary quota overrides: a dated total daily budget granted to a member by an administrator
     * (`/api/v1/members/{login}/budget-overrides`).
     */
    public const val CAPACITY_BUDGET_OVERRIDES: String = "capacity.budgetoverrides.v1"

    /**
     * TeamCode's Forecasts page: each shared account's provider windows, their last hour of readings, the learned rate
     * and when the window reaches the account's ceiling (`GET /api/v1/forecasts`, owners and admins).
     */
    public const val CAPACITY_FORECASTS: String = "capacity.forecasts.v1"

    /**
     * TeamCode's capacity controller: the organization's settings (`/api/v1/policy/pacing`), admissions spaced before
     * every usable account runs out, and the advice delivered to agents (`/api/v1/directives/...`, the `guidance`
     * scope).
     */
    public const val CAPACITY_PACING: String = "capacity.pacing.v1"

    /**
     * TeamCode's automatic shared pool size: the organization's daily budget sized from its shared accounts
     * (`GET /api/v1/pools/size`).
     */
    public const val CAPACITY_POOL_SIZE: String = "capacity.poolsize.v1"

    /**
     * TeamCode's unknown usage review: the charges kept for calls whose usage never arrived, and an administrator's
     * final correction of them (`/api/v1/usage/pending`, `/reconciliations`, `/reservations/{id}`).
     */
    public const val CAPACITY_USAGE_RECONCILIATION: String = "capacity.usagereconciliation.v1"

    /** The instance's cloud runtime and clusters, and the organizations' cloud profiles (TeamCode's cloud admin). */
    public const val CLOUD_ADMIN: String = "cloud.admin.v1"

    /**
     * The workstations of the CLI as TeamCode's devices: one device per installation (`installationId` on
     * `POST /api/v1/auth/device`) kept across its sign-ins, with its platform and system version, renamed by its owner
     * (`PATCH /api/v1/devices/{id}`), read by an owner or an admin for a member
     * (`GET /api/v1/members/{memberId}/devices`) and listed to the CLI's own sign-in (`ostrom devices`). A CLI sends no
     * installation to a server without it.
     */
    public const val DEVICES: String = "devices.v1"

    /**
     * Remote sessions (TeamCode's cloud.sessions.remote.v1): `kind: remote` on `POST /api/v1/cloud/sessions` creates a
     * conversation session on the caller's own device, hosted by `ostrom remote` (each execution opened with
     * `POST /api/v1/cloud/sessions/{id}/host`) through the runner channel under the owner's sign-in; `?kind=` lists
     * them.
     */
    public const val RUNNERS_CLOUD_REMOTE: String = "runners.cloud.remote.v1"

    /**
     * Remote sessions started from any client on one of the caller's devices, hosted by its daemon in a folder it
     * published (TeamCode's cloud.sessions.remote.daemon.v1): `/api/v1/devices/current/daemon/...`,
     * `/api/v1/devices/current/folders`, `GET /api/v1/devices/{id}/folders`.
     */
    public const val RUNNERS_CLOUD_REMOTE_DAEMON: String = "runners.cloud.remote.daemon.v1"

    /**
     * A remote session that continues a Claude Code conversation of a device's folder (TeamCode's
     * cloud.sessions.remote.resume.v1): `GET /api/v1/devices/{id}/folders/{folderId}/resumable` and `resumeSessionId`.
     */
    public const val RUNNERS_CLOUD_REMOTE_RESUME: String = "runners.cloud.remote.resume.v1"

    /** Every key this ostrom-core knows, in contract order. */
    public val ALL: List<String> = listOf(
        ADMIN_SETTINGS, ARTIFACTS, AUDIT, AUTOMATIONS, BILLING, BUDGETS, CAPACITY_API_PRICES, CAPACITY_BUDGET_OVERRIDES,
        CAPACITY_FORECASTS, CAPACITY_PACING, CAPACITY_POOL_SIZE, CAPACITY_USAGE_RECONCILIATION, CLOUD_ADMIN, DEVICES,
        ENVIRONMENT, EVENTS, GATES, GITHUB, GITHUB_LINK, GOVERNANCE, HOOKS, IMPORT_TEAMCODE, INFERENCE, INFERENCE_QUEUE,
        INFERENCE_CANCEL, MCP, MCP_PERSONAL, MEMORY, NOTIFICATIONS, NOTIFICATIONS_PUSH, NOTIFICATIONS_PUSH_TEST,
        PACKAGES, RECEIPTS, REPORTS, RULES, RUNNERS, RUNNERS_CLOUD, RUNNERS_CLOUD_AGENT, RUNNERS_CLOUD_CONVERSATION,
        RUNNERS_CLOUD_EFFORT, RUNNERS_CLOUD_ENVIRONMENT, RUNNERS_CLOUD_FILES, RUNNERS_CLOUD_GITHUB, RUNNERS_CLOUD_HOOKS,
        RUNNERS_CLOUD_IDEMPOTENCY, RUNNERS_CLOUD_INFERENCE, RUNNERS_CLOUD_MODEL, RUNNERS_CLOUD_ON_BEHALF,
        RUNNERS_CLOUD_PERMISSIONS,
        RUNNERS_CLOUD_PERMISSIONS_BYPASS, RUNNERS_CLOUD_PROFILES, RUNNERS_CLOUD_REMOTE, RUNNERS_CLOUD_REMOTE_DAEMON,
        RUNNERS_CLOUD_REMOTE_RESUME,
        RUNNERS_CLOUD_RETENTION, RUNNERS_CLOUD_SKILLS, RUNNERS_CLOUD_TASKS, RUNNERS_CLOUD_TRANSCRIPT_FEEDS,
        RUNNERS_CLOUD_TRANSCRIPT_LIVE, RUNNERS_CLOUD_TRANSFER,
        RUNNERS_CLOUD_VARIABLES, SECRETS, SESSIONS, SESSIONS_ACTIVITY, SESSIONS_LABELS,
        LAUNCHES_LIMITS, SERVICE_KEY_SCOPES, SHARING, SHARING_HANDOVER, SKILLS, SLACK, DISCORD, TODOS, USAGE, WAITS,
        WAITS_WAKE, WAITS_TIMED, WAITS_SECRETS, WEBHOOKS, WORKFLOWS, WORKFLOWS_DELIVER, WORKFLOWS_SETTINGS, LICENSE,
    )
}
