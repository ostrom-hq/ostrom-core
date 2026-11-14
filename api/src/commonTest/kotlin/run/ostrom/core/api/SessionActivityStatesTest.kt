// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.SessionActivityStates
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionActivityStatesTest {
    @Test
    fun theTwoStatesAreTheContractsInItsOrder() {
        // api/openapi.yaml, SessionActivityRequest.state and Session.activity
        assertEquals(listOf("working", "waiting_for_human"), SessionActivityStates.ALL)
    }
}
