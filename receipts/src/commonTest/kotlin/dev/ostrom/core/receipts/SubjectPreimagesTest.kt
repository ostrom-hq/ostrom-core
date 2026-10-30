package dev.ostrom.core.receipts

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
