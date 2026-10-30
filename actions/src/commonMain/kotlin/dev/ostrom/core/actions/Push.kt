package dev.ostrom.core.actions

/**
 * Why Ostrom cannot tie a yes to what a command really does. When a refusal is set and the action is under an enforced
 * gate, the caller refuses at once and asks nobody: an approver would say yes to something else than what runs.
 *
 * @property key Stable key, for logs, metrics and the API.
 * @property message What the agent reads, with what to do instead.
 */
public enum class Refusal(public val key: String, public val message: String) {
    /** The command is longer than [ActionClassifier.MAX_COMMAND_LENGTH]. */
    TOO_LARGE(
        "too_large",
        "this command is too long for Ostrom to read. Run the frontier action (a plain `git push <remote> <branch>`, " +
            "a deploy, a merge) as a short command of its own.",
    ),

    /** `GIT_DIR=`, `--git-dir`, `GIT_CONFIG_*`, `-c remote.*`, `-c url.*`…: git would read or send elsewhere. */
    REDIRECTED(
        "push_redirected",
        "this push changes where git reads or sends (GIT_DIR, --git-dir, --work-tree, GIT_CONFIG_*, HOME, " +
            "-c remote.*, -c url.*…): Ostrom could not tie the yes to what it really sends, and no yes was asked. " +
            "Run a plain `git push <remote> <branch>` from the repository, as its own command.",
    ),

    /** The push is wrapped (`sudo`, `bash -c`, `$(…)`), built from variables, or behind a `cd` in a subshell. */
    NOT_PLAIN(
        "push_not_plain",
        "Ostrom cannot read which commits this push sends (the push is not a plain `git push` command). " +
            "Run a plain `git push <remote> <branch>` as its own command.",
    ),

    /** `--all`, `--mirror`, `--branches`, `--tags`, `--follow-tags`, a wildcard refspec, submodules pushed too. */
    UNLISTABLE(
        "push_unlistable",
        "this push sends refs Ostrom cannot list before it runs (--all, --mirror, --tags, --follow-tags, a wildcard, " +
            "submodules). Push each branch or tag by name: run a plain `git push <remote> <branch>` " +
            "as its own command.",
    ),

    /** No refspec, and git follows `remote.<r>.push`, `remote.<r>.mirror` or `push.default=matching`. */
    CONFIG_DRIVEN(
        "push_config_driven",
        "this push follows the git configuration (remote.<name>.push, remote.<name>.mirror or " +
            "push.default=matching), which may send any branch. Name what you push: run a plain " +
            "`git push <remote> <branch>` as its own command.",
    ),

    /** A bare push, or a push of `HEAD` without a target, from a detached `HEAD`. */
    DETACHED_HEAD(
        "push_detached_head",
        "this push names no target from a detached HEAD. Run a plain `git push <remote> HEAD:<branch>` " +
            "as its own command.",
    ),

    /** Several remotes in one command, or a remote with several push URLs. */
    SEVERAL_DESTINATIONS(
        "push_several_destinations",
        "this command pushes to several places at once (several remotes, or a remote with several URLs). " +
            "Run a plain `git push <remote> <branch>` for one remote at a time, each as its own command.",
    ),

    /** The caller could not read the git state where the push runs. */
    GIT_STATE_UNREADABLE(
        "push_git_state_unreadable",
        "Ostrom could not read the git repository this push runs in. Run a plain `git push <remote> <branch>` " +
            "from inside the repository, as its own command.",
    ),

    /** A program whose name is built at run time (`$GIT`, `$(which git)`, `{git,x}`): what runs is unknown. */
    DYNAMIC_COMMAND(
        "command_not_plain",
        "this command runs a program whose name is built at run time (\$VAR, \$(…), backticks, {a,b}): Ostrom cannot " +
            "tell what it does. Write the program's name in full.",
    ),

    /** Several merges in one command, or merges of different pull requests: one yes would cover them all. */
    SEVERAL_MERGES(
        "several_merges",
        "this command merges more than one pull request. Merge one pull request per command " +
            "(`gh pr merge <number>`).",
    ),
}

/**
 * One ref a push updates.
 *
 * @property source What the command pushes (`main`, `HEAD`, `abc123`, `HEAD~1`), resolved to a commit by the caller;
 *   null for a deletion.
 * @property destination The remote ref it lands on: a branch by its short name (`main`), anything else in full
 *   (`refs/tags/1.0.0`).
 */
public data class PushTarget(val source: String?, val destination: String)

/**
 * Where a push lands, read from the command and the git state.
 *
 * @property remote The remote name or URL the command pushes to (`origin`).
 * @property url The URL git really pushes to (`pushurl`, `pushInsteadOf` and `insteadOf` applied).
 * @property repository `owner/name` of [url]: what the approver reads.
 * @property refs The refs updated, in the order of the command.
 * @property force A forced update: part of what a yes binds.
 */
public data class PushTargets(
    val remote: String,
    val url: String,
    val repository: String,
    val refs: List<PushTarget>,
    val force: Boolean,
) {
    /**
     * What a yes to this push binds: the repository, each ref with the commit it receives, and the force flag. A yes is
     * valid for that binding only: another commit, another ref, a force-push of the same commit ask again.
     *
     * @param commitOf The commit a source names (`git rev-parse --verify <source>^{commit}`), null when it names none.
     */
    public fun bind(commitOf: (String) -> String?): PushBindResult {
        val bound = refs.map { target ->
            val source = target.source ?: return@map BoundRef(target.destination, "")
            val sha = commitOf(source) ?: return PushBindResult.UnknownCommit(source)
            BoundRef(target.destination, sha)
        }
        return PushBindResult.Bound(PushBinding(repository, bound.distinct().sortedWith(BOUND_ORDER), force))
    }

    private companion object {
        val BOUND_ORDER: Comparator<BoundRef> = compareBy<BoundRef> { it.ref }.thenBy { it.sha }
    }
}

/**
 * One ref of a [PushBinding].
 *
 * @property ref The remote ref (`main`).
 * @property sha The commit it receives; empty for a deletion.
 */
public data class BoundRef(val ref: String, val sha: String)

/**
 * What a yes to a push is about. Two pushes with equal bindings are the same action, whatever their text
 * (`git push origin main` and `git push origin main 2>&1 | tail`).
 *
 * @property repository `owner/name` where the push goes.
 * @property refs Each ref and its commit, sorted.
 * @property force Whether the push is forced: a yes to a plain push never covers a force-push.
 */
public data class PushBinding(val repository: String, val refs: List<BoundRef>, val force: Boolean)

/** The result of [PushTargets.bind]. */
public sealed interface PushBindResult {

    /** Every source names a commit. */
    public data class Bound(val binding: PushBinding) : PushBindResult

    /** [source] names no commit here: Ostrom cannot tell what the push sends. */
    public data class UnknownCommit(val source: String) : PushBindResult
}

/**
 * What a command's `git push` does, as far as the command and (when given) the git state tell.
 *
 * @property force The push is forced (`--force`, `-f`, `-uf`, `--force-with-lease`, `--force-if-includes`, `+ref`).
 * @property standalone Only `cd` and other pushes come before it in the command: the commits it sends exist when the
 *   hook runs, so a check can test them. A push after `git commit … &&` is not standalone.
 * @property explicitDestinations The destinations the command names itself (`main` in `git push origin x:main`), which
 *   the server checks against the refs the hook sends.
 * @property targets Where it lands, when the git state was read and nothing was refused.
 */
public data class PushPlan(
    val force: Boolean,
    val standalone: Boolean,
    val explicitDestinations: List<String>,
    val targets: PushTargets?,
)
