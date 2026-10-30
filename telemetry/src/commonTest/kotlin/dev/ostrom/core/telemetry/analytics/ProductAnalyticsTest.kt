package dev.ostrom.core.telemetry.analytics

import dev.ostrom.core.telemetry.export.ExportResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProductAnalyticsTest {
    private val at = 1_793_000_000_000L
    private val config = PostHogConfig("https://eu.i.posthog.com/", "phc_test")

    @Test
    fun theFactoriesFollowTheTaxonomy() {
        val events = listOf(
            ProductEvents.signedUp("u1", SignupMethod.MAGIC_LINK, at),
            ProductEvents.orgCreated("u1", "o1", Plan.TEAM, at),
            ProductEvents.memberJoined("u2", "o1", "approver", at),
            ProductEvents.agentSessionStarted("u1", "o1", AgentKind.CODEX, SessionPlace.CLOUD, true, at),
            ProductEvents.gateRequested("u1", "o1", GateKind.PUSH_MAIN, at),
            ProductEvents.gateAnswered("u2", "o1", GateAnswer.YES, AnswerChannel.SLACK, 42.0, at),
            ProductEvents.firstYes("u2", "o1", 600.0, at),
            ProductEvents.subscriptionStarted("u1", "o1", Plan.BUSINESS, BillingInterval.YEAR, at),
            ProductEvents.subscriptionCanceled("u1", "o1", Plan.ENTERPRISE, at),
        )

        assertEquals(
            listOf(
                "signed_up", "org_created", "member_joined", "agent_session_started", "gate_requested",
                "gate_answered", "first_yes", "subscription_started", "subscription_canceled",
            ),
            events.map { it.name },
        )
        assertEquals(mapOf("method" to PropertyValue.Text("magic_link")), events[0].properties)
        assertEquals(null, events[0].organizationId)
        assertEquals("o1", events[1].organizationId)
        assertEquals(
            mapOf(
                "agent" to PropertyValue.Text("codex"),
                "where" to PropertyValue.Text("cloud"),
                "first_for_org" to PropertyValue.Flag(true),
            ),
            events[3].properties,
        )
        assertEquals(
            mapOf(
                "answer" to PropertyValue.Text("yes"),
                "channel" to PropertyValue.Text("slack"),
                "seconds_to_answer" to PropertyValue.Number(42.0),
            ),
            events[5].properties,
        )
        assertEquals(mapOf("seconds_since_signup" to PropertyValue.Number(600.0)), events[6].properties)
        assertEquals(
            mapOf("plan" to PropertyValue.Text("business"), "interval" to PropertyValue.Text("year")),
            events[7].properties,
        )
    }

    @Test
    fun everyEnumHasAStableWireName() {
        assertEquals(listOf("github", "google", "magic_link"), SignupMethod.entries.map { it.wire })
        assertEquals(listOf("free", "team", "business", "enterprise"), Plan.entries.map { it.wire })
        assertEquals(listOf("claude", "codex", "other"), AgentKind.entries.map { it.wire })
        assertEquals(listOf("local", "cloud"), SessionPlace.entries.map { it.wire })
        assertEquals(listOf("push_main", "merge", "deploy", "custom", "other"), GateKind.entries.map { it.wire })
        assertEquals(listOf("yes", "no"), GateAnswer.entries.map { it.wire })
        assertEquals(listOf("web", "app", "slack", "cli"), AnswerChannel.entries.map { it.wire })
        assertEquals(listOf("month", "year"), BillingInterval.entries.map { it.wire })
    }

    @Test
    fun cliEventsAreAnonymousAndBucketed() {
        val platform = CliPlatform("linux", "x64", "0.1.0")
        val ok = ProductEvents.cliCommandRun("install-1", "claude", 0, 12, platform, at)
        val failed = ProductEvents.cliCommandRun("install-1", "login", 2, 50_000, platform, at)

        assertEquals("cli_command_run", ok.name)
        assertEquals(PropertyValue.Text("success"), ok.properties["exit_code_class"])
        assertEquals(PropertyValue.Text("10-30"), ok.properties["duration_ms_bucket"])
        assertEquals(PropertyValue.Flag(false), ok.properties["\$process_person_profile"])
        assertEquals(PropertyValue.Text("failure"), failed.properties["exit_code_class"])
        assertEquals(PropertyValue.Text(">=10000"), failed.properties["duration_ms_bucket"])
        assertEquals("<10", ProductEvents.durationBucket(3))
        assertEquals("3000-10000", ProductEvents.durationBucket(9_999))
    }

    @Test
    fun theBatchBodyHasTheKeyTheGroupAndNoIp() {
        val encoder = PostHogEncoder(config, "ostrom-server", "1.0.0")
        val body = encoder.encode(
            listOf(
                ProductEvents.gateAnswered("u2", "o1", GateAnswer.NO, AnswerChannel.WEB, 1.5, at),
                ProductEvents.signedUp("u3", SignupMethod.GITHUB, at),
                ProductEvents.agentSessionStarted("u1", "o1", AgentKind.CLAUDE, SessionPlace.LOCAL, false, at),
            ),
        )

        val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
        val batch = json.getValue("batch").jsonArray.map { it.jsonObject }
        assertEquals("\"phc_test\"", json.getValue("api_key").toString())
        assertEquals(
            Json.parseToJsonElement(
                """
                {"event":"gate_answered","distinct_id":"u2","timestamp":"2026-10-26T07:33:20Z",
                 "properties":{"answer":"no","channel":"web","seconds_to_answer":1.5,"${'$'}lib":"ostrom-server",
                 "${'$'}lib_version":"1.0.0","${'$'}ip":null,"${'$'}groups":{"organization":"o1"}}}
                """.trimIndent(),
            ),
            batch[0],
        )
        val signedUp = batch[1].getValue("properties") as JsonObject
        assertEquals(false, signedUp.containsKey("\$groups"))
        assertEquals("false", (batch[2].getValue("properties") as JsonObject).getValue("first_for_org").toString())
    }

    @Test
    fun theClientPostsToBatchAndClassifiesTheAnswer() = runTest {
        var url = ""
        var body = ""
        val ok = PostHogClient(
            HttpClient(
                MockEngine { request ->
                    url = request.url.toString()
                    body = request.body.toByteArray().decodeToString()
                    respond("{}", HttpStatusCode.OK)
                },
            ),
            config,
        ).send("{}".encodeToByteArray())

        assertEquals(ExportResult.Success, ok)
        assertEquals("https://eu.i.posthog.com/batch/", url)
        assertEquals("{}", body)
        assertEquals(ExportResult.Retryable(), client(HttpStatusCode.ServiceUnavailable).send(byteArrayOf()))
        assertEquals(ExportResult.Rejected(401), client(HttpStatusCode.Unauthorized).send(byteArrayOf()))
        assertEquals(
            ExportResult.Retryable(),
            PostHogClient(HttpClient(MockEngine { error("reset") }), config).send(byteArrayOf()),
        )
        assertFailsWith<CancellationException> {
            PostHogClient(HttpClient(MockEngine { throw CancellationException("gone") }), config).send(byteArrayOf())
        }
    }

    @Test
    fun theConfigNeverPrintsItsKey() {
        assertEquals("PostHogConfig(https://eu.i.posthog.com/)", config.toString())
        assertEquals("https://eu.i.posthog.com", PostHogConfig.EU_HOST)
    }

    private fun client(status: HttpStatusCode) =
        PostHogClient(HttpClient(MockEngine { respond("", status) }), config)
}
