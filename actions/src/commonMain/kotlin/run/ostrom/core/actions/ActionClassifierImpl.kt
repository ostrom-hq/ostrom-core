// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.actions

/**
 * The classifier of the prototype's catalogue (`harness.ts`), with the night audit's fixes (30/10/2026): options before
 * the verb, pushes that land on main without naming it, force-pushes, redirected pushes, merges through the API.
 *
 * @param declared The actions the gates of the session's repository declare.
 * @param protectedBranches The branches `push-main` covers.
 */
public class ActionClassifierImpl(
    declared: List<DeclaredAction> = emptyList(),
    private val protectedBranches: Set<String> = ActionClassifier.DEFAULT_PROTECTED_BRANCHES,
) : ActionClassifier {

    private class Compiled(val id: String, val kinds: Set<SubjectKind>, val patterns: List<Regex>)

    override val catalogue: List<ActionClass>
    override val rejectedPatterns: List<RejectedPattern>
    private val compiled: List<Compiled>

    init {
        val classes = LinkedHashMap<String, ActionClass>()
        BuiltInActions.all.forEach { classes[it.id] = it }
        val patterns = LinkedHashMap<String, MutableList<Regex>>()
        val rejected = ArrayList<RejectedPattern>()
        for (action in declared) {
            val kinds = action.tools.mapNotNullTo(LinkedHashSet(), ::kindOfTool)
            action.tools.filter { kindOfTool(it) == null }.forEach {
                rejected += RejectedPattern(action.classId, it, "not a tool Ostrom reads: $it")
            }
            var known = classes.getOrPut(action.classId) {
                ActionClass(action.classId, action.label, kinds, emptyList())
            }
            // A custom class declared twice applies to the tools of both declarations; a built-in keeps its own.
            if (known !in BuiltInActions.all && !known.kinds.containsAll(kinds)) {
                known = known.copy(kinds = known.kinds + kinds)
                classes[known.id] = known
            }
            val list = patterns.getOrPut(known.id) { ArrayList() }
            for (pattern in action.patterns) {
                val problem = when {
                    list.size >= PatternSafety.MAX_PER_CLASS ->
                        "more than ${PatternSafety.MAX_PER_CLASS} patterns for one class"
                    else -> PatternSafety.problem(pattern)
                }
                val regex = if (problem == null) compile(pattern) else null
                if (regex == null) {
                    rejected += RejectedPattern(action.classId, pattern, problem ?: "not a valid regular expression")
                } else {
                    list += regex
                }
            }
        }
        catalogue = classes.values.toList()
        rejectedPatterns = rejected
        compiled = patterns.filterValues { it.isNotEmpty() }
            .map { (id, list) -> Compiled(id, classes.getValue(id).kinds, list) }
    }

    // One short step per kind of subject and per refusal, in the order the hook reads them.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override fun classify(subject: ToolSubject, git: GitStateReader?): Classification {
        val text = subject.text
        if (subject.kind == SubjectKind.OTHER || text.isEmpty()) return Classification(emptyList())
        if (text.length > ActionClassifier.MAX_COMMAND_LENGTH) {
            return Classification(catalogue.filter { subject.kind in it.kinds }.map { it.id }, Refusal.TOO_LARGE)
        }
        val found = HashSet<String>()
        var refusal: Refusal? = null
        var detail: String? = null
        var push: PushPlan? = null
        var pullRequest: PullRequestRef? = null
        var note = ""
        when (subject.kind) {
            SubjectKind.MCP -> {
                val tool = subject.tool.lowercase()
                if (CLIENT_MESSAGE_TOOLS.any { it in tool }) found += BuiltInActions.CLIENT_MESSAGE
            }
            SubjectKind.WRITE -> if (migrationPath(text)) found += BuiltInActions.MIGRATION
            else -> {
                val scan = ShellScan(protectedBranches)
                val script = ShellParser.parse(text)
                scan.scan(script, quoteBlind = false)
                // A malformed command may run otherwise than it reads: look at it without quotes and substitutions too.
                if (script.malformed) scan.scan(ShellParser.parse(quoteBlind(text)), quoteBlind = true)
                if (migrationPath(text)) found += BuiltInActions.MIGRATION
                found += scan.found
                pullRequest = scan.pullRequest
                val outcome = PushOutcome.of(scan, git, protectedBranches)
                if (outcome != null) {
                    found += outcome.classes
                    refusal = outcome.refusal
                    detail = outcome.detail
                    push = outcome.plan
                    note = outcome.note
                }
                // A program named at run time may be any of them: it falls under every class of its kind.
                if (scan.dynamicCommand) {
                    catalogue.filter { SubjectKind.SHELL in it.kinds }.forEach { found += it.id }
                    refusal = refusal ?: Refusal.DYNAMIC_COMMAND
                }
                if (scan.severalMerges) refusal = refusal ?: Refusal.SEVERAL_MERGES
            }
        }
        val patternText = if (subject.kind == SubjectKind.MCP) subject.tool.lowercase() else text + note
        val budgeted = BudgetedText(patternText, ActionClassifier.PATTERN_BUDGET)
        var unread = patternText.length > ActionClassifier.MAX_PATTERN_TEXT
        for (entry in compiled) {
            if (subject.kind !in entry.kinds) continue
            // A text Ostrom could not read to the end falls under the class: the gate refuses rather than asks.
            if (unread || matches(entry.patterns, budgeted)) found += entry.id
            unread = unread || budgeted.exhausted
            if (unread) refusal = refusal ?: Refusal.TOO_LARGE
        }
        return Classification(catalogue.map { it.id }.filter { it in found }, refusal, detail, push, pullRequest)
    }

    /** Whether one of [patterns] matches; true too when the reading budget runs out. */
    private fun matches(patterns: List<Regex>, text: BudgetedText): Boolean = try {
        patterns.any { it.containsMatchIn(text) }
    } catch (expected: BudgetedText.Exhausted) {
        // The pattern read more than the budget allows: what it would have found is unknown, so assume a match.
        true
    }

    private companion object {
        /** Where migrations live (Flyway, Rails, Prisma), relative paths included. */
        val MIGRATION_PATHS = listOf("db/migration/", "db/migrate/", "prisma/migrations/")

        fun migrationPath(text: String): Boolean = MIGRATION_PATHS.any { text.contains(it, ignoreCase = true) }

        fun kindOfTool(tool: String): SubjectKind? {
            val lower = tool.lowercase()
            return when {
                lower in ToolSubject.SHELL_TOOLS -> SubjectKind.SHELL
                lower in ToolSubject.WRITE_TOOLS -> SubjectKind.WRITE
                lower == "mcp" || lower.startsWith("mcp__") -> SubjectKind.MCP
                else -> null
            }
        }

        /** The pattern compiled, or null when it is not a valid regular expression. */
        fun compile(pattern: String): Regex? =
            // not coroutine-reachable: compiled once, in the constructor, outside any coroutine.
            runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

        /**
         * The command with its quotes and substitution marks turned into spaces: what a regex over the raw text would
         * have seen. A malformed command (or one nested too deep to parse) is read this way too.
         */
        fun quoteBlind(text: String): String =
            CharArray(text.length) { if (text[it] in BLINDED) ' ' else text[it] }.concatToString()

        const val BLINDED = "'\"`$(){}"
    }
}

/** The MCP tools that write to a client (the support's validated message, Discord, Slack, e-mail). */
private val CLIENT_MESSAGE_TOOLS = listOf(
    "send_validated_message", "discord_send", "discord_reply", "chat_postmessage", "send_email", "slack_post",
)
