// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.telemetry.analytics

/**
 * The event taxonomy, as typed factories: a caller cannot invent an event name or put an unbounded value in a
 * property. The activation funnel is `signed_up` → `org_created` → `agent_session_started` → `gate_requested` →
 * `first_yes` (the "aha" moment). Documented in docs/telemetry.md.
 */
// One parameter per property of the event schema: grouping them in a class would only hide the schema. One factory per
// event of the taxonomy: splitting the object would split the taxonomy.
@Suppress("LongParameterList", "TooManyFunctions")
public object ProductEvents {
    /** An account was created. */
    public fun signedUp(userId: String, method: SignupMethod, epochMillis: Long): ProductEvent =
        ProductEvent("signed_up", userId, epochMillis, mapOf("method" to text(method)))

    /** An organization was created by [userId]. */
    public fun orgCreated(userId: String, orgId: String, plan: Plan, epochMillis: Long): ProductEvent =
        ProductEvent("org_created", userId, epochMillis, mapOf("plan" to text(plan)), orgId)

    /** [userId] accepted an invitation to [orgId]. */
    public fun memberJoined(userId: String, orgId: String, role: String, epochMillis: Long): ProductEvent =
        ProductEvent("member_joined", userId, epochMillis, mapOf("role" to PropertyValue.Text(role)), orgId)

    /** `ostrom claude` (or another agent) registered a session. */
    public fun agentSessionStarted(
        userId: String,
        orgId: String,
        agent: AgentKind,
        place: SessionPlace,
        firstForOrg: Boolean,
        epochMillis: Long,
    ): ProductEvent = ProductEvent(
        "agent_session_started",
        userId,
        epochMillis,
        mapOf("agent" to text(agent), "where" to text(place), "first_for_org" to PropertyValue.Flag(firstForOrg)),
        orgId,
    )

    /** A gate opened, asked by [userId]. */
    public fun gateRequested(userId: String, orgId: String, kind: GateKind, epochMillis: Long): ProductEvent =
        ProductEvent("gate_requested", userId, epochMillis, mapOf("kind" to text(kind)), orgId)

    /** [approverId] answered a gate. */
    public fun gateAnswered(
        approverId: String,
        orgId: String,
        answer: GateAnswer,
        channel: AnswerChannel,
        secondsToAnswer: Double,
        epochMillis: Long,
    ): ProductEvent = ProductEvent(
        "gate_answered",
        approverId,
        epochMillis,
        mapOf(
            "answer" to text(answer),
            "channel" to text(channel),
            "seconds_to_answer" to PropertyValue.Number(secondsToAnswer),
        ),
        orgId,
    )

    /** The first "yes" ever given in [orgId]: the activation moment. */
    public fun firstYes(
        approverId: String,
        orgId: String,
        secondsSinceSignup: Double,
        epochMillis: Long,
    ): ProductEvent = ProductEvent(
        "first_yes",
        approverId,
        epochMillis,
        mapOf("seconds_since_signup" to PropertyValue.Number(secondsSinceSignup)),
        orgId,
    )

    /** A paid subscription became active. */
    public fun subscriptionStarted(
        userId: String,
        orgId: String,
        plan: Plan,
        interval: BillingInterval,
        epochMillis: Long,
    ): ProductEvent = ProductEvent(
        "subscription_started",
        userId,
        epochMillis,
        mapOf("plan" to text(plan), "interval" to text(interval)),
        orgId,
    )

    /** A paid subscription ended. */
    public fun subscriptionCanceled(userId: String, orgId: String, plan: Plan, epochMillis: Long): ProductEvent =
        ProductEvent("subscription_canceled", userId, epochMillis, mapOf("plan" to text(plan)), orgId)

    /**
     * One CLI command, from the anonymous, opt-in CLI telemetry.
     *
     * @param installId A random id made at the first opt-in, never linked to the account.
     * @param command The command path (`claude`, `login`), never its arguments.
     */
    public fun cliCommandRun(
        installId: String,
        command: String,
        exitCode: Int,
        durationMillis: Long,
        platform: CliPlatform,
        epochMillis: Long,
    ): ProductEvent = ProductEvent(
        "cli_command_run",
        installId,
        epochMillis,
        mapOf(
            "command" to PropertyValue.Text(command),
            "exit_code_class" to PropertyValue.Text(if (exitCode == 0) "success" else "failure"),
            "duration_ms_bucket" to PropertyValue.Text(durationBucket(durationMillis)),
            "os" to PropertyValue.Text(platform.os),
            "arch" to PropertyValue.Text(platform.arch),
            "version" to PropertyValue.Text(platform.version),
            "\$process_person_profile" to PropertyValue.Flag(false),
        ),
    )

    private val DURATION_BUCKETS = listOf(10L, 30L, 100L, 300L, 1_000L, 3_000L, 10_000L)

    /** Buckets a duration so the property stays bounded (`<10`, `10-30`, …, `>=10000`). */
    internal fun durationBucket(millis: Long): String {
        val upper = DURATION_BUCKETS.indexOfFirst { millis < it }
        return when (upper) {
            -1 -> ">=${DURATION_BUCKETS.last()}"
            0 -> "<${DURATION_BUCKETS.first()}"
            else -> "${DURATION_BUCKETS[upper - 1]}-${DURATION_BUCKETS[upper]}"
        }
    }

    private fun text(value: WireName) = PropertyValue.Text(value.wire)
}

/**
 * The platform of a CLI install, as bounded properties.
 *
 * @property os `macos`, `linux`, `windows`.
 * @property arch `arm64`, `x64`.
 * @property version The CLI version.
 */
public data class CliPlatform(val os: String, val arch: String, val version: String)
