// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import run.ostrom.core.api.models.DirectivePace
import run.ostrom.core.api.models.DirectiveStage
import run.ostrom.core.api.models.PacingSettings
import run.ostrom.core.api.models.PoolSizeAccountStatus
import run.ostrom.core.api.models.PoolSizeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The capacity controller's settings and the advice it gives to agents. */
class PacingClientTest {
    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private val directive = """{"id":"d1","pace":"slow_down","maxConcurrent":2,"minIntervalMillis":5000,""" +
        """"message":"Space your calls.","createdAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T00:03:00Z"}"""

    @Test
    fun theSettingsAreReadAndWrittenAndAFieldLeftOutIsNotSent() = runTest {
        val server = ScriptedServer { request ->
            if (request.method.value == "PUT") json("""{"observationOnly":true,"autoPoolSize":false}""")
            else json("{}")
        }
        server.client().use { client ->
            assertNull(client.pacing.settings().observationOnly)
            val saved = client.pacing.setSettings(PacingSettings(observationOnly = true))
            assertEquals(true, saved.observationOnly)
            assertEquals(false, saved.autoPoolSize)
        }
        assertEquals(
            listOf("GET /api/v1/policy/pacing", "PUT /api/v1/policy/pacing"),
            server.requests.map { "${it.method.value} ${it.url.encodedPath}" },
        )
        // The server keeps a field left out: none is sent as null.
        assertJson("""{"observationOnly":true}""", server.requests[1].bodyText())
    }

    @Test
    fun thePreviewAndTheCurrentAdviceAreRead() = runTest {
        val server = ScriptedServer { request ->
            when {
                request.url.encodedPath.endsWith("/preview") ->
                    json("""{"settings":{"observationOnly":false},"directive":$directive}""")
                request.method.value == "GET" -> json("""{"directive":$directive}""")
                else -> json("{}")
            }
        }
        server.client().use { client ->
            val preview = client.pacing.preview()
            assertEquals(DirectivePace.SLOW_DOWN, preview.directive?.pace)
            assertEquals(5000L, preview.directive?.minIntervalMillis)
            assertEquals("d1", client.pacing.current().directive?.id)
            assertNull(client.pacing.session("s1").directive)
        }
        val (request, body) = server.requests.last().let { it to it.bodyText() }
        assertEquals("POST /api/v1/directives/current", "${request.method.value} ${request.url.encodedPath}")
        assertEquals("""{"sessionId":"s1"}""", body)
    }

    @Test
    fun anUnknownPaceOfANewerServerReadsAsUnknown() = runTest {
        val newer = ScriptedServer { json("""{"directive":${directive.replace("slow_down", "hold_still")}}""") }
        newer.client().use { assertEquals(DirectivePace.UNKNOWN, it.pacing.current().directive?.pace) }
    }

    @Test
    fun aClaimAndItsReceiptAreSentWithTheirStages() = runTest {
        val server = ScriptedServer { request ->
            if (request.url.encodedPath.endsWith("/claim")) json("""{"directive":$directive,"claimId":"c1"}""")
            else json("", HttpStatusCode.NoContent)
        }
        server.client().use { client ->
            val claim = client.pacing.claim("s1", DirectiveStage.RECEIVED_BY_CLI)
            assertEquals("c1", claim.claimId)
            client.pacing.receipt("d1", DirectiveStage.EMITTED_TO_HOOK, "s1", "c1")
            client.pacing.receipt("d1", DirectiveStage.EMITTED_TO_HOOK)
        }
        assertTrue(server.requests.map { it.bodyText() }.contains("""{"sessionId":"s1","stage":"received_by_cli"}"""))
        assertTrue(
            server.requests.map { it.bodyText() }
                .contains("""{"id":"d1","stage":"emitted_to_hook","sessionId":"s1","claimId":"c1"}"""),
        )
        assertTrue(server.requests.map { it.bodyText() }.contains("""{"id":"d1","stage":"emitted_to_hook"}"""))
        assertEquals("/api/v1/directives/receipt", server.requests.last().url.encodedPath)
    }

    @Test
    fun theAutomaticPoolSizeIsReadAndAnUnknownStatusReadsAsUnknown() = runTest {
        val server = ScriptedServer {
            json(
                """{"enabled":true,"manualDailyMicros":5000000,"automaticDailyMicros":8000000,""" +
                    """"automaticComputedAt":"2026-01-01T00:00:00Z","effectiveDailyMicros":8000000,""" +
                    """"source":"automatic","estimatedDailyMicros":7500000,"unpricedModels":["x"],""" +
                    """"accounts":[{"accountId":"a1","status":"counted","dailyMicros":7500000,""" +
                    """"binding":"five_hour","windows":[{"window":"five_hour","fullWindowMicros":1000,""" +
                    """"capacityMicros":1200,"samples":4}],"usage":[{"window":"five_hour",""" +
                    """"since":"2026-01-01T00:00:00Z","observedAt":"2026-01-01T01:00:00Z",""" +
                    """"resetsAt":"2026-01-01T05:00:00Z","observedUtilization":0.4,"ostromMicros":300}]},""" +
                    """{"accountId":"a2","status":"added-later","dailyMicros":0,"windows":[],"usage":[]}],""" +
                    """"capacityDailyMicros":9000000,"capacityUnknownAccounts":1,"outsideUnknownAccounts":0,""" +
                    """"models":[{"provider":"anthropic","model":"m","inputTokens":1,"cacheWriteTokens":2,""" +
                    """"cacheReadTokens":3,"outputTokens":4,"unpricedTokens":0}]}""",
            )
        }
        server.client().use { client ->
            val size = client.pacing.poolSize()
            assertEquals(PoolSizeSource.AUTOMATIC, size.source)
            assertEquals(8_000_000L, size.effectiveDailyMicros)
            assertNull(size.reason)
            assertEquals(PoolSizeAccountStatus.COUNTED, size.accounts[0].status)
            assertEquals(PoolSizeAccountStatus.UNKNOWN, size.accounts[1].status)
            assertEquals(300L, size.accounts[0].usage[0].ostromMicros)
            assertNull(size.accounts[0].usage[0].outsideMicros)
        }
        val request = server.requests.single()
        assertEquals("GET /api/v1/pools/size", "${request.method.value} ${request.url.encodedPath}")
    }
}
