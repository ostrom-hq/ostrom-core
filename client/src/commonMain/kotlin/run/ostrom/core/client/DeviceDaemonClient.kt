// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import run.ostrom.core.api.models.CloudSession
import run.ostrom.core.api.models.DaemonListingAnswer
import run.ostrom.core.api.models.DaemonPoll
import run.ostrom.core.api.models.DaemonPollRequest
import run.ostrom.core.api.models.DeviceFolder
import run.ostrom.core.api.models.DeviceFolders
import run.ostrom.core.api.models.PublishDeviceFoldersRequest
import run.ostrom.core.api.models.RefuseDaemonOrderRequest
import run.ostrom.core.api.models.ResumableSessionPage

/**
 * A device's daemon (`runners.cloud.remote.daemon.v1`, TeamCode's device daemon routes): what the daemon of the
 * caller's own device calls (`/api/v1/devices/current/...`), and what a member reads of one of their devices to start a
 * session on it. Reached through [OstromClient.deviceDaemon].
 */
public class DeviceDaemonClient internal constructor(private val transport: Transport) {
    /** One of the caller's devices: its daemon and the folders it published (`GET /api/v1/devices/{id}/folders`). */
    public suspend fun folders(deviceId: String): DeviceFolders =
        transport.json(HttpMethod.Get, "/api/v1/devices/${segment(deviceId)}/folders")

    /**
     * The Claude Code conversations of a device's folder a session can continue, the most recent first
     * (`GET /api/v1/devices/{id}/folders/{folderId}/resumable`, `runners.cloud.remote.resume.v1`).
     *
     * @param limit The page size (1 to 50); 20 when null.
     * @param before The [ResumableSessionPage.next] of the previous page.
     */
    public suspend fun resumable(
        deviceId: String,
        folderId: String,
        limit: Int? = null,
        before: String? = null,
    ): ResumableSessionPage =
        transport.json(
            HttpMethod.Get,
            "/api/v1/devices/${segment(deviceId)}/folders/${segment(folderId)}/resumable",
            query("limit" to limit?.toString(), "before" to before),
        )

    /** The daemon's long poll, from the device of the caller's sign-in (`POST /api/v1/devices/current/daemon/poll`). */
    public suspend fun poll(request: DaemonPollRequest): DaemonPoll =
        transport.jsonBody(HttpMethod.Post, "$CURRENT/daemon/poll", request)

    /** The daemon stops: the device is offline at once (`DELETE /api/v1/devices/current/daemon`). */
    public suspend fun stop() {
        transport.noContent(HttpMethod.Delete, "$CURRENT/daemon")
    }

    /** The daemon refuses to host a session (`POST /api/v1/devices/current/daemon/sessions/{id}/refuse`). */
    public suspend fun refuse(sessionId: String, reason: String): CloudSession =
        transport.jsonBody(
            HttpMethod.Post,
            "$CURRENT/daemon/sessions/${segment(sessionId)}/refuse",
            RefuseDaemonOrderRequest(reason),
        )

    /** The daemon answers a listing (`POST /api/v1/devices/current/daemon/listings/{requestId}`). */
    public suspend fun answerListing(requestId: String, answer: DaemonListingAnswer) {
        transport.send(HttpMethod.Post, "$CURRENT/daemon/listings/${segment(requestId)}") {
            contentType(ContentType.Application.Json)
            setBody(answer)
        }
    }

    /** The folders the device offers, replacing the previous list (`PUT /api/v1/devices/current/folders`). */
    public suspend fun publishFolders(folders: List<DeviceFolder>): List<DeviceFolder> =
        transport.jsonBody(HttpMethod.Put, "$CURRENT/folders", PublishDeviceFoldersRequest(folders))

    private companion object {
        const val CURRENT = "/api/v1/devices/current"
    }
}
