// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.SessionTodo

/** The todo lists of agent sessions (`/api/v1/todos`), read only. Reached through [OstromClient.todos]. */
public class TodosClient internal constructor(private val transport: Transport) {
    /**
     * A session's todo (`GET /api/v1/todos/session?sessionId=`): [SessionTodo.todo] is null while the agent has
     * created none. Read by the session's person, and by an owner or an administrator of the organization.
     *
     * @param sessionId The session, or one of its segments (`~r<n>`).
     */
    public suspend fun session(sessionId: String): SessionTodo =
        transport.json(HttpMethod.Get, "/api/v1/todos/session", query("sessionId" to sessionId))
}
