// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.McpServer
import run.ostrom.core.api.models.McpServerList

/**
 * The caller's personal MCP servers (`mcp.personal.v1`, `/api/v1/me/mcp/servers`), relayed to them alone. Reached
 * through [OstromClient.myMcpServers].
 */
public class MyMcpServersClient internal constructor(private val transport: Transport) {
    /** The caller's personal MCP servers (`GET /api/v1/me/mcp/servers`). */
    public suspend fun list(): McpServerList = transport.json(HttpMethod.Get, "/api/v1/me/mcp/servers")

    /**
     * Declares a personal MCP server (`POST /api/v1/me/mcp/servers`). A hosted command is refused with 403
     * `hosted_not_allowed` unless an administrator allows it; 409 when the name is taken or past 20 servers.
     */
    public suspend fun create(server: McpServer): McpServer =
        transport.jsonBody(HttpMethod.Post, "/api/v1/me/mcp/servers", server)

    /**
     * Changes the personal server [name] (`PUT /api/v1/me/mcp/servers/{name}`). A hosted command's launcher, package,
     * args and env never change after creation; only its secrets may.
     */
    public suspend fun save(name: String, server: McpServer): McpServer =
        transport.jsonBody(HttpMethod.Put, "/api/v1/me/mcp/servers/${segment(name)}", server)

    /** Deletes the personal server [name] with its assignment (`DELETE /api/v1/me/mcp/servers/{name}`). */
    public suspend fun delete(name: String) {
        transport.noContent(HttpMethod.Delete, "/api/v1/me/mcp/servers/${segment(name)}")
    }
}
