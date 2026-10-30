package dev.ostrom.core.actions

/**
 * A pull request a merge command targets.
 *
 * @property number Its number.
 * @property owner The owner named by the command (`-R owner/repo`, a URL, an API path), null when the command does not
 *   say (the session's repository).
 * @property repo The repository named by the command, null when it does not say.
 */
public data class PullRequestRef(val number: Int, val owner: String? = null, val repo: String? = null)

/**
 * What a tool call does at the frontier, and what a yes to it must bind.
 *
 * Caller's rule (CLI hook and server alike): when [refusal] is set and one of [actions] is under an enforced gate, the
 * call is refused at once with [Refusal.message], and nobody is asked.
 *
 * @property actions The frontier classes it falls under, in catalogue order; empty when it may run freely.
 * @property refusal Why a yes could not be tied to what runs, if so.
 * @property refusalDetail The configuration key or remote behind [refusal], for the message, when there is one.
 * @property push The command's `git push`, when it has one.
 * @property pullRequest The pull request a merge command names, when it names one.
 */
public data class Classification(
    val actions: List<String>,
    val refusal: Refusal? = null,
    val refusalDetail: String? = null,
    val push: PushPlan? = null,
    val pullRequest: PullRequestRef? = null,
)
