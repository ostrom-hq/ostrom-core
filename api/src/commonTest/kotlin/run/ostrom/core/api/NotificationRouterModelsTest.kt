// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.NotificationKinds
import run.ostrom.core.api.models.PushRelay
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationRouterModelsTest {

    @Test
    fun theFourKindsAreTeamCodes() {
        assertEquals(listOf("waiting", "turn_completed", "turn_failed", "session_failed"), NotificationKinds.ALL)
    }

    @Test
    fun aRelayRequestIsSignedOverItsMethodPathTimeAndBodyHash() {
        assertEquals(
            "POST\n/v1/send\n1761069900\nab12",
            PushRelay.signingString("post", "/v1/send", 1761069900, "ab12"),
        )
    }
}
