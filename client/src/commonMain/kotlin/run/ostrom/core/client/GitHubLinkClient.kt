// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.GitHubDeviceLink
import run.ostrom.core.api.models.GitHubDeviceLinkPoll
import run.ostrom.core.api.models.GitHubLink
import run.ostrom.core.api.models.GitHubLinkPage

/**
 * The GitHub account a member links so their cloud sessions use it (`github.link.v1`, `/api/v1/github/link`).
 * Reached through [OstromClient.githubLink]. A person only: a service key or an agent token is refused. No call
 * returns a GitHub token. The app and the CLI link with the device flow: [startDevice], show the code, then
 * [pollDevice] every [GitHubDeviceLink.interval] seconds.
 */
public class GitHubLinkClient internal constructor(private val transport: Transport) {
    /** The caller's own link (`GET /api/v1/github/link`). */
    public suspend fun get(): GitHubLink = transport.json(HttpMethod.Get, "/api/v1/github/link")

    /** Unlinks the caller's account (`DELETE /api/v1/github/link`). */
    public suspend fun unlink() {
        transport.noContent(HttpMethod.Delete, "/api/v1/github/link")
    }

    /** Starts GitHub's device flow (`POST /api/v1/github/link/device`). */
    public suspend fun startDevice(): GitHubDeviceLink = transport.json(HttpMethod.Post, "/api/v1/github/link/device")

    /** Polls a device flow (`POST /api/v1/github/link/device/{id}`). */
    public suspend fun pollDevice(id: String): GitHubDeviceLinkPoll =
        transport.json(HttpMethod.Post, "/api/v1/github/link/device/${segment(id)}")

    /** Every member's link (`GET /api/v1/github/links`), owners and admins. */
    public suspend fun list(): GitHubLinkPage = transport.json(HttpMethod.Get, "/api/v1/github/links")

    /** Another member's link (`GET /api/v1/github/links/{memberId}`), owners and admins. */
    public suspend fun member(memberId: String): GitHubLink =
        transport.json(HttpMethod.Get, "/api/v1/github/links/${segment(memberId)}")

    /** Unlinks another member's account (`DELETE /api/v1/github/links/{memberId}`), owners and admins. */
    public suspend fun unlinkMember(memberId: String) {
        transport.noContent(HttpMethod.Delete, "/api/v1/github/links/${segment(memberId)}")
    }
}
