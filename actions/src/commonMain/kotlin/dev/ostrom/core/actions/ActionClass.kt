package dev.ostrom.core.actions

/**
 * A class of frontier action: something an agent may only do with a yes from the gate that covers it.
 *
 * @property id Stable identifier (`push-main`, `custom-release-notes`).
 * @property label What a person reads.
 * @property kinds The tool kinds it applies to.
 * @property keywords Words the server uses to guess which gate of a workflow covers it.
 */
public data class ActionClass(
    val id: String,
    val label: String,
    val kinds: Set<SubjectKind>,
    val keywords: List<String>,
)

/** The starting catalogue of frontier actions (the prototype's `ACTIONS`), detected by a parser, not by patterns. */
public object BuiltInActions {
    /** Push to the main branch (`main` or `master`), whatever the command says. */
    public const val PUSH_MAIN: String = "push-main"

    /** Push a release tag, or create a GitHub release. */
    public const val PUSH_TAG: String = "push-tag"

    /** Merge a pull request (`gh pr merge`, the GitHub API). */
    public const val MERGE: String = "merge"

    /** Deploy to production (helm, kubectl, terraform, fly, vercel, package and image publication…). */
    public const val DEPLOY: String = "deploy"

    /** Run or add a database migration. */
    public const val MIGRATION: String = "migration"

    /** Release the mobile app to the stores. */
    public const val MOBILE_RELEASE: String = "mobile-release"

    /** Send a message to a customer through an MCP tool. */
    public const val CLIENT_MESSAGE: String = "client-message"

    private val SHELL = setOf(SubjectKind.SHELL)

    /** Every built-in class, in the prototype's order. */
    public val all: List<ActionClass> = listOf(
        ActionClass(
            PUSH_MAIN,
            "Push to the main branch",
            SHELL,
            listOf("push to main", "main", "pin", "production", "merge"),
        ),
        ActionClass(PUSH_TAG, "Push a release tag", SHELL, listOf("tag", "release", "production")),
        ActionClass(MERGE, "Merge a pull request", SHELL, listOf("merge", "approve", "review")),
        ActionClass(
            DEPLOY,
            "Deploy to production",
            SHELL,
            listOf("deploy", "production", "helm", "rollout", "pin", "release"),
        ),
        ActionClass(
            MIGRATION, "Run or add a database migration", setOf(SubjectKind.SHELL, SubjectKind.WRITE),
            listOf("migration", "schema", "database", "flyway"),
        ),
        ActionClass(
            MOBILE_RELEASE, "Release the mobile app to the stores", SHELL,
            listOf("mobile", "store", "fastlane", "daily release", "app"),
        ),
        ActionClass(
            CLIENT_MESSAGE, "Send a message to a customer", setOf(SubjectKind.MCP),
            listOf("client", "customer", "support", "message", "reply"),
        ),
    )
}

/**
 * An action a gate declares in the console (the prototype's `GateAction`), already filtered for the session's
 * repository by the server.
 *
 * A built-in [classId] adds its [patterns] to the built-in detection, never replaces it: a pattern written for one case
 * must not drop the parser's view of `git push origin HEAD` or `git -C x push`. Any other class is custom.
 *
 * @property classId `custom-<slug>`, or a built-in id.
 * @property label What a person reads.
 * @property tools `Bash` (shell), `Write`/`Edit`/`MultiEdit` (file writes), `mcp` (MCP tools).
 * @property patterns Regular expressions, matched without case on the command, the path, or the MCP tool name.
 */
public data class DeclaredAction(
    val classId: String,
    val label: String = classId,
    val tools: List<String> = listOf("Bash"),
    val patterns: List<String>,
)

/**
 * A pattern the classifier refused, so the console can say why.
 *
 * @property classId The class that declared it.
 * @property pattern The pattern.
 * @property reason Why: invalid, too long, too many, or open to catastrophic backtracking.
 */
public data class RejectedPattern(val classId: String, val pattern: String, val reason: String)
