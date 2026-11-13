// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpMethod
import run.ostrom.core.api.models.PushDevice
import run.ostrom.core.api.models.RegisterPushDeviceRequest

/** The caller's phones registered for push notifications. Reached through [OstromClient.pushDevices]. */
public class PushDevicesClient internal constructor(private val transport: Transport) {
    /** Registers this phone (`POST /api/v1/push-devices`); keep [PushDevice.id] to [delete] it. */
    public suspend fun register(
        pushToken: String,
        provider: String,
        platform: String,
        locale: String? = null,
    ): PushDevice = transport.jsonBody(
        HttpMethod.Post,
        "/api/v1/push-devices",
        RegisterPushDeviceRequest(pushToken, provider, platform, locale),
    )

    /** Stops notifying a phone (`DELETE /api/v1/push-devices/{id}`), before a sign-out for example. */
    public suspend fun delete(id: String) {
        transport.noContent(HttpMethod.Delete, "/api/v1/push-devices/${segment(id)}")
    }
}
