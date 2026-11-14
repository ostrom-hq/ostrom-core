// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.Device
import run.ostrom.core.api.models.DevicePage
import run.ostrom.core.api.models.RenameDeviceRequest

/** The workstations where the CLI is signed in (`devices.v1`). Reached through [OstromClient.devices]. */
public class DevicesClient internal constructor(private val transport: Transport) {
    /** The caller's devices (`GET /api/v1/devices`), registered installations first. */
    public suspend fun list(): DevicePage = transport.json(HttpMethod.Get, "/api/v1/devices")

    /** A member's devices, for an owner or an admin (`GET /api/v1/members/{memberId}/devices`). */
    public suspend fun listForMember(memberId: String): DevicePage =
        transport.json(HttpMethod.Get, "/api/v1/members/${segment(memberId)}/devices")

    /** Renames a device (`PATCH /api/v1/devices/{id}`), 1 to 120 characters. */
    public suspend fun rename(id: String, name: String): Device =
        transport.jsonBody(HttpMethod.Patch, "/api/v1/devices/${segment(id)}", RenameDeviceRequest(name))

    /** Removes a device and signs every one of its sign-ins out (`DELETE /api/v1/devices/{id}`). */
    public suspend fun delete(id: String) {
        transport.noContent(HttpMethod.Delete, "/api/v1/devices/${segment(id)}")
    }
}
