// SPDX-License-Identifier: Apache-2.0
package dev.ostrom.core.receipts

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.ReceiptNode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubjectPreimagesTest {

    @Test
    fun aPullRequestGateNamesThePullRequestAndHowTheYesWasAsked() {
        // bridge/index.ts: `pr` and `askedVia` are written only for a pull request gate, `ci` for a CI check
        val gate = SubjectPreimages.gate(
            gate = JsonNull, cls = "merge-main", command = "gh pr merge 12", repo = "acme-api", sessionId = null,
            ci = buildJsonObject { put("sha", "abc") }, shown = "d1",
            pr = buildJsonObject { put("number", "12") }, askedVia = listOf("slack", "app"),
        )
        assertEquals(
            """{"askedVia":["slack","app"],"ci":{"sha":"abc"},"class":"merge-main","command":"gh pr merge 12",""" +
                """"gate":null,"pr":{"number":"12"},"repo":"acme-api","sessionId":null,"shown":"d1"}""",
            CanonicalJson.encode(gate),
        )
    }

    @Test
    fun aGateWithoutPullRequestOrCiWritesANullCiAndNothingElse() {
        val gate = SubjectPreimages.gate(
            gate = JsonPrimitive("g"), cls = "push-main", command = "git push", repo = null, sessionId = "s",
            ci = null, shown = "d",
        )
        assertEquals(
            """{"ci":null,"class":"push-main","command":"git push","gate":"g",""" +
                """"repo":null,"sessionId":"s","shown":"d"}""",
            CanonicalJson.encode(gate),
        )
    }

    @Test
    fun theAttestationOfACheckIsSignedAndAnotherNodeWritesNone() {
        // a declared run and an attested one must not share a preimage: the attestation is in the signed part
        val gate = ReceiptNode("n2", "w1", "gate", "Push to main", "approved")
        fun preimage(attestation: CheckAttestation): String {
            val check = ReceiptNode("n1", "w1", "check", "Tests", "passed", attestation = attestation)
            return CanonicalJson.encode(SubjectPreimages.workflow("s1", "p1", listOf(check, gate)))
        }
        assertEquals(
            """{"nodes":[{"attestation":"ci","id":"n1","kind":"check","label":"Tests","outcome":"passed",""" +
                """"workflowId":"w1"},{"id":"n2","kind":"gate","label":"Push to main","outcome":"approved",""" +
                """"workflowId":"w1"}],"package":"p1","session":"s1"}""",
            preimage(CheckAttestation.CI),
        )
        assertNotEquals(preimage(CheckAttestation.CI), preimage(CheckAttestation.WORKSTATION_DECLARED))
    }
}
