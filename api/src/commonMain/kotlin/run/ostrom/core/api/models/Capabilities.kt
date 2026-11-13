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

    /** The MCP relay (servers, assignments, policies). */
    public const val MCP: String = "mcp.v1"

    /** Notifications. */
    public const val NOTIFICATIONS: String = "notifications.v1"

    /** Push notifications to the mobile app: only with a push sender configured. */
    public const val NOTIFICATIONS_PUSH: String = "notifications.push.v1"

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
     * Cloud sessions use their owner's GitHub link: the runner's `POST /api/v1/cloud/runner/github`, the session's
     * `githubAccess` and `PUT /api/v1/cloud/sessions/{id}/github`. Only with cloud runners and [GITHUB_LINK].
     */
    public const val RUNNERS_CLOUD_GITHUB: String = "runners.cloud.github.v1"

    /** Organization secrets. */
    public const val SECRETS: String = "secrets.v1"

    /** Agent sessions. */
    public const val SESSIONS: String = "sessions.v1"

    /**
     * The wide catalogue of service-key scopes: a key may hold `inference:use`, `mcp:use`, `usage:read`, `cloud:read`,
     * `cloud:write`, `cloud:admin`, `artifacts:read`, `artifacts:write` and `team:read` besides the CI's
     * `sessions:write` and `rules:read`, and acts as its creator within them.
     */
    public const val SERVICE_KEY_SCOPES: String = "servicekeys.scopes.v1"

    /** Sharing with other members and organizations. */
    public const val SHARING: String = "sharing.v1"

    /** The Slack integration: only with Slack configured. */
    public const val SLACK: String = "slack.v1"

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

    /** Workflows and their runs. */
    public const val WORKFLOWS: String = "workflows.v1"

    /** The enterprise license (ee build only). */
    public const val LICENSE: String = "license.v1"

    /** Every key this ostrom-core knows, in contract order. */
    public val ALL: List<String> = listOf(
        ARTIFACTS, AUDIT, AUTOMATIONS, BILLING, BUDGETS, GATES, GITHUB, GITHUB_LINK, GOVERNANCE, IMPORT_TEAMCODE,
        INFERENCE, MCP, NOTIFICATIONS, NOTIFICATIONS_PUSH, PACKAGES, RECEIPTS, REPORTS, RULES, RUNNERS, RUNNERS_CLOUD,
        RUNNERS_CLOUD_GITHUB, SECRETS, SESSIONS, SERVICE_KEY_SCOPES, SHARING, SLACK, TODOS, USAGE, WAITS, WAITS_WAKE,
        WAITS_TIMED, WORKFLOWS, LICENSE,
    )
}
