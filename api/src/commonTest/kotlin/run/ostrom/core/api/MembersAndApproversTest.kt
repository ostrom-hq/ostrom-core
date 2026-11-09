// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.ApproverGate
import run.ostrom.core.api.models.ApproverLine
import run.ostrom.core.api.models.ApproversGraph
import run.ostrom.core.api.models.Member
import run.ostrom.core.api.models.MemberInvite
import run.ostrom.core.api.models.MemberRole
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MembersAndApproversTest {

    @Test
    fun aMemberCarriesTheIdTheArtifactRoutesTake() {
        val member = OstromJson.decodeFromString(
            Member.serializer(),
            """{"id":"$THEO_ID","login":"theo","avatarUrl":"https://a/theo.png","role":"member"}""",
        )

        assertEquals(THEO_ID, member.id)
        assertEquals("theo", member.login)
    }

    @Test
    fun aMemberWithoutAnIdIsRefused() {
        assertFailsWith<SerializationException> {
            OstromJson.decodeFromString(
                Member.serializer(),
                """{"login":"theo","avatarUrl":"https://a/theo.png","role":"member"}""",
            )
        }
    }

    @Test
    fun anInviteWithOnlyALoginWritesOnlyTheLogin() {
        assertEquals("""{"login":"theo"}""", OstromJson.encodeToString(MemberInvite.serializer(), MemberInvite("theo")))
    }

    @Test
    fun anInviteReadsItsRoleAndTeam() {
        val invite = OstromJson.decodeFromString(
            MemberInvite.serializer(),
            """{"login":"theo","role":"approver","team":"platform"}""",
        )

        assertEquals(MemberInvite("theo", MemberRole.APPROVER, "platform"), invite)
    }

    @Test
    fun theApproversGraphReadsWhatTheGatesModuleServes() {
        val served = """
            {"approvers":[{"approver":"lyes","gates":[{"workflowId":"wf-1","workflowName":"Release","nodeId":"g1",
            "label":"Prod","enforce":true,"actions":["deploy","push-main"]}]}],
            "unanswered":[{"workflowId":"wf-2","workflowName":"Draft","nodeId":"g2","label":"Nobody",
            "enforce":false,"actions":["merge"]}],"ungated":["commit","push-any"]}
        """.trimIndent()

        val graph = OstromJson.decodeFromString(ApproversGraph.serializer(), served)

        val prod = ApproverGate("wf-1", "Release", "g1", "Prod", enforce = true, listOf("deploy", "push-main"))
        val nobody = ApproverGate("wf-2", "Draft", "g2", "Nobody", enforce = false, listOf("merge"))
        assertEquals(
            ApproversGraph(listOf(ApproverLine("lyes", listOf(prod))), listOf(nobody), listOf("commit", "push-any")),
            graph,
        )
    }

    private companion object {
        const val THEO_ID = "0b5e3f8a-6a52-4c1e-9d3f-2f1c8f0d9a11"
    }
}
