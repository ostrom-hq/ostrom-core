// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.DeviceLimits
import run.ostrom.core.api.models.DevicePlatforms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceModelsTest {
    @Test
    fun thePlatformsAreTeamCodesInContractOrder() {
        assertEquals(listOf("macos", "windows", "linux", "ios", "android"), DevicePlatforms.ALL)
    }

    @Test
    fun anInstallationIdIsALowercaseUuid() {
        assertTrue(DeviceLimits.INSTALLATION_ID.matches("0f8a3c6e-2b1d-4e5f-9a7b-1c2d3e4f5a6b"))
        assertFalse(DeviceLimits.INSTALLATION_ID.matches("0F8A3C6E-2B1D-4E5F-9A7B-1C2D3E4F5A6B"))
        assertFalse(DeviceLimits.INSTALLATION_ID.matches("laptop"))
        val bounds = listOf(DeviceLimits.MAX_NAME, DeviceLimits.MAX_OS_VERSION, DeviceLimits.MAX_DEVICES)
        assertEquals(listOf(120, 64, 50), bounds)
    }
}
