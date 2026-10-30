package dev.ostrom.core.actions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What kind of tool an agent is about to call. */
public enum class SubjectKind {
    /** A shell command (Claude Code `Bash`, Codex `exec_command`, Gemini `run_shell_command`…). */
    SHELL,

    /** A file write or edit: the subject text is the path. */
    WRITE,

    /** An MCP tool (`mcp__server__tool`): the subject text is the tool name, then its input. */
    MCP,

    /** Anything else: never a frontier action. */
    OTHER,
}

/**
 * What a tool call is about to do, as text the classifier reads.
 *
 * @property kind The kind of tool.
 * @property tool The tool name as the agent sent it.
 * @property text The shell command, the path written, or the MCP tool name and input.
 */
public data class ToolSubject(val kind: SubjectKind, val tool: String, val text: String) {

    /** Builders from an agent's tool call. */
    public companion object {
        /** Input text kept for a write or an MCP call, as the prototype did. */
        public const val MAX_INPUT_TEXT: Int = 2000

        private val SHELL_TOOLS = setOf(
            "bash", "shell", "exec_command", "local_shell", "container.exec", "run_terminal_command",
            "run_shell_command",
            "terminal",
        )
        private val WRITE_TOOLS = setOf(
            "write", "edit", "multiedit", "apply_patch", "write_file", "replace", "edit_file", "search_replace",
            "patch",
        )
        private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ksh", "ash", "fish")

        /** A shell command. */
        public fun shell(command: String): ToolSubject = ToolSubject(SubjectKind.SHELL, "Bash", command)

        /**
         * The subject of a tool call, whatever the agent (same mapping as the prototype's `subjectOf`).
         *
         * @param tool The tool name (`Bash`, `exec_command`, `Write`, `mcp__slack__chat_postMessage`…).
         * @param input The tool input.
         */
        public fun of(tool: String, input: JsonObject): ToolSubject {
            val lower = tool.lowercase()
            return when {
                lower in SHELL_TOOLS ->
                    ToolSubject(SubjectKind.SHELL, tool, commandOf(input["command"] ?: input["cmd"]))
                lower in WRITE_TOOLS -> {
                    val path = (input["file_path"] ?: input["path"]).stringOrNull()
                    ToolSubject(SubjectKind.WRITE, tool, path ?: input.toString().take(MAX_INPUT_TEXT))
                }
                tool.startsWith("mcp__") ->
                    ToolSubject(SubjectKind.MCP, tool, "$tool ${input.toString().take(MAX_INPUT_TEXT)}")
                else -> ToolSubject(SubjectKind.OTHER, tool, "")
            }
        }

        private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A command given as a string, or as an argv array (Codex): `["bash", "-lc", "<script>"]` is the script. */
        private fun commandOf(element: JsonElement?): String {
            if (element !is JsonArray) return element.stringOrNull().orEmpty()
            val argv = element.map { it.stringOrNull().orEmpty() }
            val shellScript = argv.size == 3 && argv[0].substringAfterLast('/') in SHELLS &&
                argv[1].startsWith("-") && 'c' in argv[1]
            return if (shellScript) argv[2] else argv.joinToString(" ", transform = ::quote)
        }

        private fun quote(arg: String): String =
            if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in SAFE_CHARS }) {
                arg
            } else {
                "'" + arg.replace("'", "'\\''") + "'"
            }

        private const val SAFE_CHARS = "_./:=@%+,-"
    }
}
