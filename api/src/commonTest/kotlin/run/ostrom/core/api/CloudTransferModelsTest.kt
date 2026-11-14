// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.AcceptCloudTransferRequest
import run.ostrom.core.api.models.CloudTransfer
import run.ostrom.core.api.models.CloudTransferStates
import run.ostrom.core.api.models.CreateCloudTransferRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The constants and defaults a server and a client of a session transfer share. */
class CloudTransferModelsTest {

    @Test
    fun theStatesAreDistinctAndInTheOrderOfATransfersLife() {
        assertEquals(
            listOf("offered", "accepted", "completed", "declined", "cancelled", "expired", "failed"),
            CloudTransferStates.all,
        )
    }

    @Test
    fun aRequestDoesNotResumeByDefaultAndAnAcceptanceKeepsTheOffer() {
        assertEquals(false, CreateCloudTransferRequest("bob").resume)
        assertNull(AcceptCloudTransferRequest().resume)
    }

    @Test
    fun aTransferFromANewerServerKeepsWhatItKnows() {
        val transfer = OstromJson.decodeFromString(
            CloudTransfer.serializer(),
            """{"id":"t","sessionId":"s","fromMemberId":"a","toMemberId":"b","state":"later","initiatedBy":"a",""" +
                """"resume":true,"createdAt":"x","updatedAt":"x","extra":1}""",
        )
        assertEquals("later", transfer.state)
        assertNull(transfer.failure)
    }
}
