package dev.ostrom.core.actions

/**
 * What the `git push` invocations of a command mean for the gate: their classes, a refusal, and their plan.
 *
 * @property note A line naming where the push lands, added to the text declared patterns read (the prototype's
 *   `# ostrom: git push to: main`), so a pattern on `git … push … main` sees a push that lands on main without
 *   naming it.
 */
internal class PushOutcome(
    val classes: Set<String>,
    val refusal: Refusal?,
    val detail: String?,
    val plan: PushPlan,
    val note: String,
) {
    companion object {
        /** The outcome of the pushes of [scan]; null when it has none, aliases that may push included. */
        fun of(scan: ShellScan, git: GitStateReader?, protectedBranches: Set<String>): PushOutcome? {
            val pushes = scan.pushes
            // An alias is read where it runs; one that may push is a push Ostrom cannot read.
            val aliasPush = git != null && scan.aliases.any { alias ->
                aliasMayPush(git.read(alias.directories)?.config?.get("alias.${alias.verb}"))
            }
            if (pushes.isEmpty() && !aliasPush) return null
            val explicit = pushes.flatMap { it.explicitDestinations() }
            var refusal: Refusal? = when {
                scan.redirectAssignment || pushes.any { it.redirect } -> Refusal.REDIRECTED
                aliasPush || scan.tooManyPushes || pushes.any { !it.plain } -> Refusal.NOT_PLAIN
                else -> pushes.firstNotNullOfOrNull { it.arguments.refusal }
            }
            var detail: String? = null
            var targets: PushTargets? = null
            if (refusal == null && git != null) {
                when (val resolution = resolveAll(pushes, git)) {
                    is Resolution.Refused -> {
                        refusal = resolution.refusal
                        detail = resolution.detail
                    }
                    is Resolution.Landed -> targets = resolution.targets
                }
            }
            val destinations = targets?.refs?.map { it.destination } ?: explicit
            val classes = HashSet<String>()
            // A push Ostrom cannot read may land on main: the gate of main refuses it.
            val protected = destinations.any { d -> protectedBranches.any { it.equals(d, ignoreCase = true) } }
            if (refusal != null || protected) {
                classes += BuiltInActions.PUSH_MAIN
            }
            val sources = pushes.flatMap { push -> push.arguments.refspecs.map { splitRefspec(it).first } }
            if (pushes.any { it.arguments.tags } || (sources + destinations).any(::tagLike)) {
                classes += BuiltInActions.PUSH_TAG
            }
            val note = if (targets == null) "" else "\n# ostrom: git push to: ${destinations.joinToString(", ")}"
            val standalone = pushes.isNotEmpty() && pushes.all { it.standalone }
            val plan = PushPlan(pushes.any { it.arguments.force }, standalone, explicit, targets)
            return PushOutcome(classes, refusal, detail, plan, note)
        }

        /** Where every push of the command lands: one destination URL, or a refusal. */
        private fun resolveAll(pushes: List<PushInvocation>, git: GitStateReader): Resolution {
            val first = resolveOne(pushes[0], git)
            if (first !is Resolution.Landed) return first
            var landed = first.targets
            for (push in pushes.drop(1)) {
                val next = resolveOne(push, git)
                if (next !is Resolution.Landed) return next
                if (next.targets.url != landed.url) return Resolution.Refused(Refusal.SEVERAL_DESTINATIONS, null)
                landed = landed.copy(refs = landed.refs + next.targets.refs, force = landed.force || next.targets.force)
            }
            return Resolution.Landed(landed)
        }

        private fun resolveOne(push: PushInvocation, git: GitStateReader): Resolution {
            val state = git.read(push.directories) ?: return Resolution.Refused(Refusal.GIT_STATE_UNREADABLE, null)
            return push.resolve(state)
        }
    }
}
