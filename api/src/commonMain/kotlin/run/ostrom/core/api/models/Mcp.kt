// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

// MCP servers relayed by Ostrom, as a member reads and declares their personal ones (`mcp.personal.v1`, TeamCode
// #269). The same model is the request and the answer: the server ignores the read-only properties it is sent.

/**
 * An MCP server relayed by Ostrom; its credentials stay on the server.
 *
 * @property name 1 to 64 characters of `[a-z0-9._-]`, starting with a letter or a digit.
 * @property url The upstream URL of an HTTP server; for a stdio server, answers read `stdio:<launcher>:<package>`.
 * @property transport `http` or `stdio`.
 * @property secretNames The secrets the server uses (a member's personal secrets for a personal server).
 * @property tools The tools it exposes, when known.
 * @property agentName The name the agent sees, in a member's list (read only).
 * @property state A stdio server's process as last recorded (`idle`, `starting`, `ready`, `failed`, `backoff`,
 *   `unknown`), in a member's list (read only).
 * @property failure The last failure of that process not yet followed by a successful start (read only).
 * @property path The relay endpoint, in a member's list (read only).
 * @property credentialHeader The header carrying the credential, when not `Authorization: Bearer`.
 * @property stdio A command run by the MCP host; its launcher, package, args and env never change after creation, only
 *   its secrets may.
 * @property version Bumped at each change (read only).
 * @property owner The member a personal server belongs to; null for a team server (read only).
 */
@Serializable
public data class McpServer(
    val name: String,
    val url: String = "",
    val transport: String,
    val secretNames: List<String> = emptyList(),
    val tools: List<String>? = null,
    val enabled: Boolean = true,
    val scope: String = "shared",
    val agentName: String? = null,
    val state: String? = null,
    val failure: String? = null,
    val path: String? = null,
    val credentialHeader: String? = null,
    val stdio: McpStdioSpec? = null,
    val version: Int? = null,
    val createdAt: String? = null,
    val owner: String? = null,
)

/**
 * A server run by the MCP host from a pinned package.
 *
 * @property launcher `npm` or `pypi`.
 * @property packageName `name@1.2.3` (npm) or `name==1.2.3` (PyPI).
 * @property env Plain environment variables of the process.
 * @property secretEnv Variable name to the name of the secret the server injects.
 */
@Serializable
public data class McpStdioSpec(
    val launcher: String,
    val packageName: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secretEnv: Map<String, String> = emptyMap(),
)

/** The caller's personal MCP servers (`GET /api/v1/me/mcp/servers`). */
@Serializable
public data class McpServerList(
    val items: List<McpServer>,
    val next: String? = null,
)
