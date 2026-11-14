// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.models.AnswerCloudPermissionRequest
import run.ostrom.core.api.models.CloudInput
import run.ostrom.core.api.models.CloudInputPage
import run.ostrom.core.api.models.SendCloudMessageRequest
import run.ostrom.core.api.models.StopCloudTaskRequest

/**
 * What members send to a conversation session's agent (`runners.cloud.conversation.v1`, TeamCode's cloud messages):
 * messages, interrupts, permission answers (`runners.cloud.permissions.v1`) and task stops (`runners.cloud.tasks.v1`),
 * each an input acknowledged `queued`, `delivered`, `consumed`, or finally `cancelled` or `rejected`. Reached through
 * [OstromClient.cloudMessages].
 */
public class CloudMessagesClient internal constructor(private val transport: Transport) {
    /**
     * Sends a message to the agent (`POST .../messages`). [idempotencyKey] goes in `Idempotency-Key`; give it or the
     * request's `clientMessageId`, not both (refused before any request). A retry with the same key returns the first
     * input.
     */
    public suspend fun send(id: String, request: SendCloudMessageRequest, idempotencyKey: String? = null): CloudInput {
        require(idempotencyKey == null || request.clientMessageId == null) {
            "Give the Idempotency-Key or the clientMessageId, not both."
        }
        return withKey(HttpMethod.Post, "${path(id)}/messages", request, idempotencyKey)
    }

    /** The session's inputs, newest first (`GET .../messages`). */
    public suspend fun inputs(id: String, before: String? = null, limit: Int? = null): CloudInputPage =
        transport.json(HttpMethod.Get, "${path(id)}/messages", query("before" to before, "limit" to limit?.toString()))

    /** One input and its acknowledgement (`GET .../messages/{input}`). */
    public suspend fun input(id: String, input: String): CloudInput =
        transport.json(HttpMethod.Get, "${path(id)}/messages/${segment(input)}")

    /** Stops the agent's current turn (`POST .../interrupt`). */
    public suspend fun interrupt(id: String): CloudInput = transport.json(HttpMethod.Post, "${path(id)}/interrupt")

    /** Answers a permission request (`POST .../permissions`, `runners.cloud.permissions.v1`). */
    public suspend fun answerPermission(
        id: String,
        request: AnswerCloudPermissionRequest,
        idempotencyKey: String? = null,
    ): CloudInput = withKey(HttpMethod.Post, "${path(id)}/permissions", request, idempotencyKey)

    /** Stops a background task of the agent (`POST .../tasks/stop`, `runners.cloud.tasks.v1`). */
    public suspend fun stopTask(id: String, taskId: String): CloudInput =
        transport.jsonBody(HttpMethod.Post, "${path(id)}/tasks/stop", StopCloudTaskRequest(taskId))

    private suspend inline fun <reified B> withKey(
        method: HttpMethod,
        path: String,
        body: B,
        key: String?,
    ): CloudInput =
        transport.send(method, path) {
            key?.let { header(IDEMPOTENCY_KEY, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"
}

/** The idempotency header of messages, answers and uploads. */
internal const val IDEMPOTENCY_KEY = "Idempotency-Key"
