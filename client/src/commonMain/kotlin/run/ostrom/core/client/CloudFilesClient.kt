// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.models.CloudFile
import run.ostrom.core.api.models.CloudFilePolicy

/**
 * Files sent with a message to a conversation session (`runners.cloud.files.v1`, TeamCode's cloud files). Reached
 * through [OstromClient.cloudFiles].
 */
public class CloudFilesClient internal constructor(private val transport: Transport) {
    /**
     * Uploads a file for the next message (`POST .../files?name=`, `runners.cloud.files.v1`); name its id in
     * the `fileIds` of a message. A retry with the same [idempotencyKey] and bytes returns the first file.
     */
    public suspend fun upload(
        id: String,
        name: String,
        bytes: ByteArray,
        idempotencyKey: String? = null,
    ): CloudFile =
        transport.send(HttpMethod.Post, "${path(id)}/files", query("name" to name)) {
            idempotencyKey?.let { header(IDEMPOTENCY_KEY, it) }
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }.body()

    /** Whether the organization can send files now, and its limits (`GET /api/v1/cloud/files/policy`). */
    public suspend fun policy(): CloudFilePolicy = transport.json(HttpMethod.Get, "/api/v1/cloud/files/policy")

    private fun path(id: String) = "/api/v1/cloud/sessions/${segment(id)}"
}
