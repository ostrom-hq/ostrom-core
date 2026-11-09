// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.telemetry.analytics

/**
 * A property value of a product event. Only bounded values (enums, counts, durations) are allowed: never a prompt, a
 * diff, a command, a path, a token, an e-mail or a repository name (docs/telemetry.md).
 */
public sealed interface PropertyValue {
    /** A bounded text value, typically an enum's wire name. */
    public data class Text(val value: String) : PropertyValue

    /** A number. */
    public data class Number(val value: Double) : PropertyValue

    /** A boolean. */
    public data class Flag(val value: Boolean) : PropertyValue
}

/**
 * One product analytics event, as PostHog stores it.
 *
 * @property name A `snake_case` past-tense name from [ProductEvents]; never renamed once shipped.
 * @property distinctId The user's opaque id (or the CLI's random install id), never an e-mail.
 * @property epochMillis When it happened.
 * @property properties Bounded properties.
 * @property organizationId The org, sent as PostHog's `organization` group so funnels can be read per org.
 */
public data class ProductEvent(
    val name: String,
    val distinctId: String,
    val epochMillis: Long,
    val properties: Map<String, PropertyValue> = emptyMap(),
    val organizationId: String? = null,
)

/** A value with a stable wire name, used as an event property. */
public interface WireName {
    /** The name sent to PostHog; never changed once shipped. */
    public val wire: String
}

/** How an account was created. */
public enum class SignupMethod(override val wire: String) : WireName {
    /** GitHub OAuth. */
    GITHUB("github"),

    /** Google OAuth. */
    GOOGLE("google"),

    /** A magic link sent by e-mail. */
    MAGIC_LINK("magic_link"),
}

/** The plans of the price list (30/10/2026). */
public enum class Plan(override val wire: String) : WireName {
    /** Free. */
    FREE("free"),

    /** Team. */
    TEAM("team"),

    /** Business. */
    BUSINESS("business"),

    /** Enterprise. */
    ENTERPRISE("enterprise"),
}

/** The coding agent behind a session. */
public enum class AgentKind(override val wire: String) : WireName {
    /** Claude Code. */
    CLAUDE("claude"),

    /** Codex. */
    CODEX("codex"),

    /** Any other agent. */
    OTHER("other"),
}

/** Where an agent session runs. */
public enum class SessionPlace(override val wire: String) : WireName {
    /** On the user's machine. */
    LOCAL("local"),

    /** In an Ostrom cloud pod. */
    CLOUD("cloud"),
}

/** What a gate protects. */
public enum class GateKind(override val wire: String) : WireName {
    /** A push to the default branch. */
    PUSH_MAIN("push_main"),

    /** A merge. */
    MERGE("merge"),

    /** A deployment. */
    DEPLOY("deploy"),

    /** A gate declared in an org workflow. */
    CUSTOM("custom"),

    /** Anything else. */
    OTHER("other"),
}

/** An approver's answer. */
public enum class GateAnswer(override val wire: String) : WireName {
    /** Yes. */
    YES("yes"),

    /** No. */
    NO("no"),
}

/** Where an approver answered. */
public enum class AnswerChannel(override val wire: String) : WireName {
    /** The web console. */
    WEB("web"),

    /** The mobile app. */
    APP("app"),

    /** Slack. */
    SLACK("slack"),

    /** The CLI. */
    CLI("cli"),
}

/** A billing period. */
public enum class BillingInterval(override val wire: String) : WireName {
    /** Monthly. */
    MONTH("month"),

    /** Yearly. */
    YEAR("year"),
}
