package dev.ostrom.core.actions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The subject of a tool call, for every agent the prototype's `subjectOf` knew. */
class ToolSubjectTest {

    private fun input(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in entries) {
            put(
                key,
                when (value) {
                    is String -> JsonPrimitive(value)
                    is Int -> JsonPrimitive(value)
                    is List<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                    else -> JsonNull
                },
            )
        }
    }

    @Test
    fun shellToolsOfEveryAgentGiveTheCommand() {
        for (tool in listOf(
            "Bash", "shell", "exec_command", "local_shell", "container.exec", "run_terminal_command",
            "run_shell_command",
            "terminal", "BASH",
        )) {
            assertEquals(
                ToolSubject(SubjectKind.SHELL, tool, "git push"),
                ToolSubject.of(tool, input("command" to "git push")),
                tool,
            )
        }
        assertEquals(
            ToolSubject(SubjectKind.SHELL, "exec_command", "ls"),
            ToolSubject.of("exec_command", input("cmd" to "ls")),
        )
        assertEquals(ToolSubject(SubjectKind.SHELL, "Bash", ""), ToolSubject.of("Bash", input()))
        assertEquals(ToolSubject(SubjectKind.SHELL, "Bash", ""), ToolSubject.of("Bash", input("command" to 3)))
        assertEquals(ToolSubject(SubjectKind.SHELL, "Bash", ""), ToolSubject.of("Bash", input("command" to null)))
        assertEquals(ToolSubject.shell("x"), ToolSubject(SubjectKind.SHELL, "Bash", "x"))
    }

    @Test
    fun anArgvArrayIsTheScriptOfAShellOrItsQuotedWords() {
        val subject = { argv: List<String> -> ToolSubject.of("shell", input("command" to argv)).text }
        assertEquals("git push origin main", subject(listOf("bash", "-lc", "git push origin main")))
        assertEquals("git push", subject(listOf("/bin/zsh", "-c", "git push")))
        assertEquals("git push", subject(listOf("sh", "-ec", "git push")))
        assertEquals("git push origin main", subject(listOf("git", "push", "origin", "main")))
        assertEquals("git commit -m 'a b' '' 'it'\\''s'", subject(listOf("git", "commit", "-m", "a b", "", "it's")))
        assertEquals("python -lc x", subject(listOf("python", "-lc", "x")))
        assertEquals("bash -x y", subject(listOf("bash", "-x", "y")))
        assertEquals("bash c y", subject(listOf("bash", "c", "y")))
        assertEquals("bash -c", subject(listOf("bash", "-c")))
        assertEquals("a=b,c:d@e%f+g.h/i_j-k", subject(listOf("a=b,c:d@e%f+g.h/i_j-k")))
        val mixed = buildJsonObject { put("command", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x")))) }
        assertEquals("'' x", ToolSubject.of("shell", mixed).text)
    }

    @Test
    fun writeToolsGiveThePath() {
        val tools = listOf(
            "Write", "Edit", "MultiEdit", "apply_patch", "write_file", "replace", "edit_file", "search_replace",
            "patch",
        )
        for (tool in tools) {
            assertEquals(
                ToolSubject(SubjectKind.WRITE, tool, "/r/a.kt"),
                ToolSubject.of(tool, input("file_path" to "/r/a.kt")),
                tool,
            )
        }
        assertEquals("/r/b.kt", ToolSubject.of("write_file", input("path" to "/r/b.kt")).text)
        assertEquals("{\"patch\":\"*** a\"}", ToolSubject.of("apply_patch", input("patch" to "*** a")).text)
        assertEquals(
            ToolSubject.MAX_INPUT_TEXT,
            ToolSubject.of("apply_patch", input("patch" to "x".repeat(5000))).text.length,
        )
    }

    @Test
    fun mcpToolsGiveTheirNameThenTheirInput() {
        assertEquals(
            ToolSubject(
                SubjectKind.MCP,
                "mcp__slack__chat_postMessage",
                "mcp__slack__chat_postMessage {\"text\":\"hi\"}",
            ),
            ToolSubject.of("mcp__slack__chat_postMessage", input("text" to "hi")),
        )
        assertEquals(
            "mcp__a__b ".length + ToolSubject.MAX_INPUT_TEXT,
            ToolSubject.of("mcp__a__b", input("text" to "x".repeat(5000))).text.length,
        )
    }

    @Test
    fun anyOtherToolIsNothing() {
        assertEquals(ToolSubject(SubjectKind.OTHER, "Read", ""), ToolSubject.of("Read", input("file_path" to "/a")))
        assertEquals(ToolSubject(SubjectKind.OTHER, "mcp_x", ""), ToolSubject.of("mcp_x", input()))
    }
}
