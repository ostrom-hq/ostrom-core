// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.ApprovalAnswer
import run.ostrom.core.api.models.CloudAgentKind
import run.ostrom.core.api.models.CloudEnvironment
import run.ostrom.core.api.models.CloudRepository
import run.ostrom.core.api.models.CloudSetup
import run.ostrom.core.api.models.CreateCloudSessionRequest
import run.ostrom.core.api.models.EnvironmentVariantCreate
import run.ostrom.core.api.models.EnvironmentVariantUpdate
import run.ostrom.core.api.models.McpServer
import run.ostrom.core.api.models.NotificationPreferences
import run.ostrom.core.api.models.QuietHours
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WaitStatus
import run.ostrom.core.api.models.WorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Each resource client: the method, path, query and body of its calls, and what it reads back. */
class ResourceClientsTest {

    private fun assertJson(expected: String, actual: String) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), actual)
    }

    private fun HttpRequestData.call(): String = "${method.value} ${url.encodedPath}" +
        if (url.parameters.isEmpty()) "" else "?" + url.encodedQuery

    private val approval = """{"id":"apr_1","title":"Push to main","requestedFrom":"ada","channel":"app",""" +
        """"status":"pending","shownDigest":"sha256:1"}"""
    private val approvalView = """{"approval":$approval,"you":{"login":"ada","self":false,"canApprove":true}}"""
    private val receipt = """{"id":"rcp_1","subject":{"kind":"task","id":"t1","digest":"d"},"initiator":"ada",""" +
        """"rules":[],"decisions":[],"createdAt":"2026-11-13T00:00:00Z","signature":"sig"}"""
    private val wait = """{"id":"w/1","kind":"question","status":"open","assignee":"ada",""" +
        """"question":{"title":"Ship?"},"createdAt":"2026-11-13T00:00:00Z"}"""
    private val artifact = """{"id":"a1","title":"Report","owner":"ada","access":"team","latestVersion":2,""" +
        """"url":"https://ostrom.example/artifacts/a1","createdAt":"t","updatedAt":"t"}"""

    @Test
    fun theApprovalQueueAViewAndAnAnswerAreSentToTheirRoutes() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/approvals/queue" -> json(
                    """{"operator":"ada","you":{"login":"ada","via":"app"},"pending":[$approvalView],"recent":[]}""",
                )
                else -> json(approvalView)
            }
        }
        server.client().use { client ->
            val queue = client.approvals.queue()
            assertEquals("apr_1", queue.pending.single().approval.id)
            assertEquals("app", queue.you.via)
            assertEquals(true, client.approvals.view("apr_1").you.canApprove)
            client.approvals.answer("apr 1", ApprovalAnswer.APPROVE, "sha256:1")
            client.approvals.answer("apr_1", ApprovalAnswer.REJECT, "sha256:1", note = "Not on Friday.")
        }
        assertEquals(
            listOf(
                "GET /api/v1/approvals/queue",
                "GET /api/v1/approvals/apr_1/view",
                "POST /api/v1/approvals/apr%201/answer",
                "POST /api/v1/approvals/apr_1/answer",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"answer":"approve","shownDigest":"sha256:1"}""", server.requests[2].bodyText())
        assertJson(
            """{"answer":"reject","shownDigest":"sha256:1","note":"Not on Friday."}""",
            server.requests[3].bodyText(),
        )
    }

    @Test
    fun aRefusalWithoutAMessageKeepsItsTypeAndRequestBound() = runTest {
        val server = ScriptedServer {
            json(
                """{"error":{"type":"step_up_required","requestBound":true}}""",
                HttpStatusCode.Forbidden,
            )
        }
        val error = server.client().use { client ->
            assertFailsWith<OstromException> { client.approvals.answer("apr_1", ApprovalAnswer.APPROVE, "d") }
        }
        assertEquals(403 to "step_up_required", error.status to error.type)
        assertEquals(true, error.requestBound)
        assertEquals("Ostrom answered HTTP 403.", error.message)
    }

    @Test
    fun receiptsArePagedReadOneByOneAndTheirKeysListed() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/receipts" -> json("""{"items":[$receipt],"next":"c2"}""")
                "/api/v1/receipts/keys" -> json(
                    """{"keys":[{"kid":"k1","alg":"Ed25519","publicKeyPem":"PEM",""" +
                        """"rotation":{"kid":"k0","signature":"s"}}]}""",
                )
                else -> json(receipt)
            }
        }
        server.client().use { client ->
            assertEquals("c2", client.receipts.list().next)
            client.receipts.list(before = "c2", limit = 50)
            assertEquals("rcp_1", client.receipts.get("rcp_1").id)
            assertEquals("k0", client.receipts.keys().single().rotation?.kid)
        }
        assertEquals(
            listOf(
                "GET /api/v1/receipts",
                "GET /api/v1/receipts?before=c2&limit=50",
                "GET /api/v1/receipts/rcp_1",
                "GET /api/v1/receipts/keys",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun rawReceiptsKeepTheFieldsThisVersionDoesNotKnow() = runTest {
        val newer = receipt.trimEnd().removeSuffix("}") + ""","futureField":{"x":1}}"""
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/receipts" -> json("""{"items":[$newer],"next":"c2"}""")
                else -> json(newer)
            }
        }
        server.client().use { client ->
            val page = client.receipts.listRaw(before = "c1", limit = 10)
            assertEquals("c2", page.next)
            assertEquals("""{"x":1}""", page.items.single()["futureField"].toString())
            assertEquals("""{"x":1}""", client.receipts.getRaw("rcp_1")["futureField"].toString())
            assertEquals(null, client.receipts.listRaw().items.single()["absent"])
        }
        assertEquals(
            listOf("GET /api/v1/receipts?before=c1&limit=10", "GET /api/v1/receipts/rcp_1", "GET /api/v1/receipts"),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun spendingAndActivityCarryTheirFilters() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/spending" -> json(
                    """{"period":"week","from":"a","to":"b","totalUsd":12.5,"byAgent":[],"byRepo":[],""" +
                        """"byPerson":[{"key":"m1","label":"ada","usd":12.5,"sessions":3}],""" +
                        """"daily":[{"date":"d","usd":1.0}]}""",
                )
                else -> json(
                    """{"items":[{"id":"e1","at":"t","kind":"gate.decided","actor":{"kind":"person","name":"ada"},""" +
                        """"summary":"Yes."}],"next":"c"}""",
                )
            }
        }
        server.client().use { client ->
            val spending = client.spending.report("month")
            assertEquals(3, spending.byPerson.single().sessions)
            assertEquals(emptyList(), spending.exceeded)
            client.spending.report()
            assertEquals("gate.decided", client.activity.page().items.single().kind)
            client.activity.page(before = "c", repo = "acme/api")
        }
        assertEquals(
            listOf(
                "GET /api/v1/spending?period=month",
                "GET /api/v1/spending",
                "GET /api/v1/activity",
                "GET /api/v1/activity?before=c&repo=acme%2Fapi",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun aPhoneIsRegisteredThenDeleted() = runTest {
        val server = ScriptedServer { request ->
            if (request.method == HttpMethod.Delete) {
                json("", HttpStatusCode.NoContent)
            } else {
                json("""{"id":"pd_1","provider":"fcm","platform":"android","createdAt":"t"}""", HttpStatusCode.Created)
            }
        }
        server.client().use { client ->
            assertEquals("pd_1", client.pushDevices.register("tok", "fcm", "android").id)
            client.pushDevices.register("tok", "fcm", "ios", locale = "fr", publicKey = "MFkw")
            client.pushDevices.delete("pd_1")
        }
        assertJson("""{"pushToken":"tok","provider":"fcm","platform":"android"}""", server.requests[0].bodyText())
        assertJson(
            """{"pushToken":"tok","provider":"fcm","platform":"ios","locale":"fr","publicKey":"MFkw"}""",
            server.requests[1].bodyText(),
        )
        assertEquals("DELETE /api/v1/push-devices/pd_1", server.requests[2].call())
    }

    @Test
    fun notificationPreferencesAreReadAndReplaced() = runTest {
        val body = """{"requests":true,"decisions":false,"spending":true,""" +
            """"quietHours":{"start":"22:00","end":"07:00","timeZone":"Europe/Paris"}}"""
        val server = ScriptedServer { json(body) }
        server.client().use { client ->
            val preferences = client.notifications.preferences()
            assertEquals("Europe/Paris", preferences.quietHours?.timeZone)
            val quietHours = QuietHours("22:00", "07:00", "Europe/Paris")
            client.notifications.save(NotificationPreferences(true, false, true, quietHours))
        }
        assertEquals(
            listOf("GET /api/v1/me/notifications", "PUT /api/v1/me/notifications"),
            server.requests.map { it.call() },
        )
        assertJson(body, server.requests[1].bodyText())
    }

    @Test
    fun notificationSettingsAreReadAndOneSwitchIsChanged() = runTest {
        val body = """{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":false}]}"""
        val server = ScriptedServer { json(body) }
        server.client().use { client ->
            val settings = client.notifications.settings()
            assertEquals(listOf("push"), settings.channels)
            assertEquals(false, settings.settings.single().enabled)
            assertEquals("waiting", client.notifications.setSetting("push", "waiting", true).settings.single().kind)
        }
        assertEquals(
            listOf("GET /api/v1/notifications/settings", "PUT /api/v1/notifications/settings"),
            server.requests.map { it.call() },
        )
        assertJson("""{"channel":"push","kind":"waiting","enabled":true}""", server.requests[1].bodyText())
    }

    @Test
    fun aTestPushAnswersEachPhoneAndARefusalKeepsItsCode() = runTest {
        var answer = 0
        val server = ScriptedServer {
            when (answer++) {
                0 -> json("""{"ok":true,"results":[{"status":"sent"},{"status":"rejected","error":"bad_token"}]}""")
                1 -> json("""{"error":{"type":"push_no_device","message":"No phone."}}""", HttpStatusCode.Conflict)
                else -> json(
                    """{"error":{"type":"push_test_rate_limited","message":"Later."}}""",
                    HttpStatusCode.TooManyRequests,
                    listOf(HttpHeaders.RetryAfter to "10"),
                )
            }
        }
        server.client().use { client ->
            val results = client.notifications.sendTestPush()
            assertEquals(true, results.ok)
            assertEquals(listOf("sent", "rejected"), results.results.map { it.status })
            assertEquals("bad_token", results.results[1].error)
            val noDevice = assertFailsWith<OstromException> { client.notifications.sendTestPush() }
            assertEquals(409, noDevice.status)
            assertEquals("push_no_device", noDevice.type)
            val limited = assertFailsWith<OstromException> { client.notifications.sendTestPush() }
            assertEquals(429, limited.status)
            assertEquals(10L, limited.retryAfterSeconds)
        }
        assertEquals(List(3) { "POST /api/v1/notifications/push/test" }, server.requests.map { it.call() })
    }

    @Test
    fun sessionsAreListedWithTheirFiltersAndReadOneByOne() = runTest {
        val session = """{"id":"s/1","agent":"claude-code","dev":"ada","startedAt":"2026-11-14T00:00:00Z"}"""
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/sessions" -> json("""{"items":[$session],"next":"s0"}""")
                else -> json("""{"session":$session}""")
            }
        }
        server.client().use { client ->
            val page = client.sessions.list()
            assertEquals("s0", page.next)
            assertEquals("ada", page.items.single().dev)
            client.sessions.list(repo = "acme/api", dev = "ada", open = true, before = "s0", limit = 20)
            val one = client.sessions.get("s/1")
            assertEquals("claude-code", one.session.agent)
            assertNull(one.receipt)
        }
        assertEquals(
            listOf(
                "GET /api/v1/sessions",
                "GET /api/v1/sessions?repo=acme%2Fapi&dev=ada&open=true&before=s0&limit=20",
                "GET /api/v1/sessions/s%2F1",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun aSessionTodoIsReadByItsSessionAndMayBeAbsent() = runTest {
        val todo = """{"id":"t1","sessionId":"s1","reference":"Ship it","status":"active","items":[""" +
            """{"id":"a","name":"Write","description":"d","doneWhen":{"text":"merged","checks":[]},""" +
            """"status":"todo","origin":"plan"}],"createdAt":"t","updatedAt":"t"}"""
        var answer = """{"todo":$todo}"""
        val server = ScriptedServer { json(answer) }
        server.client().use { client ->
            assertEquals("Ship it", client.todos.session("s1~r2").todo?.reference)
            answer = """{"todo":null}"""
            assertNull(client.todos.session("s1").todo)
            assertFailsWith<IllegalArgumentException> { client.todos.session(" ") }
        }
        assertEquals(
            listOf("GET /api/v1/todos/session?sessionId=s1~r2", "GET /api/v1/todos/session?sessionId=s1"),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun workflowsAndTheirRunsAreListedAndReadOneByOne() = runTest {
        val workflow = """{"id":"wf/1","name":"Release","domain":"eng","nodes":[],"edges":[],""" +
            """"status":"accepted","foundAt":"t"}"""
        val run = """{"id":"r1","workflowId":"wf/1","workflowName":"Release","workflowVersion":3,""" +
            """"status":"waiting","startedBy":"ada","source":"manual","startedAt":"t"}"""
        val server = ScriptedServer { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/v1/workflows" -> json("""{"items":[$workflow]}""")
                path.endsWith("/runs") -> json("""{"items":[$run],"next":"r0"}""")
                path.startsWith("/api/v1/runs/") -> json(run)
                else -> json(workflow)
            }
        }
        server.client().use { client ->
            assertEquals(WorkflowStatus.ACCEPTED, client.workflows.list().items.single().status)
            client.workflows.list(repo = "acme/api", status = "accepted")
            assertEquals("Release", client.workflows.get("wf/1").name)
            val runs = client.workflows.runs("wf/1")
            assertEquals("r0", runs.next)
            assertEquals(RunStatus.WAITING, runs.items.single().status)
            client.workflows.runs("wf/1", status = "waiting", before = "r0")
            client.workflows.allRuns()
            client.workflows.allRuns(status = "running", before = "r0")
            assertEquals(3, client.workflows.run("r1").workflowVersion)
        }
        assertEquals(
            listOf(
                "GET /api/v1/workflows",
                "GET /api/v1/workflows?repo=acme%2Fapi&status=accepted",
                "GET /api/v1/workflows/wf%2F1",
                "GET /api/v1/workflows/wf%2F1/runs",
                "GET /api/v1/workflows/wf%2F1/runs?status=waiting&before=r0",
                "GET /api/v1/runs",
                "GET /api/v1/runs?status=running&before=r0",
                "GET /api/v1/runs/r1",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun waitsAreListedReadAndAnsweredWithTheirValues() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/waits" -> json("""{"items":[$wait]}""")
                else -> json(wait)
            }
        }
        server.client().use { client ->
            val page = client.waits.list()
            assertNull(page.next)
            assertEquals(WaitStatus.OPEN, page.items.single().status)
            client.waits.list(status = "open", mine = true, before = "w0", limit = 10, resolver = "human")
            assertEquals("Ship?", client.waits.get("w/1").question.title)
            client.waits.answer("w/1", "approve")
            client.waits.answer("w/1", "approve", mapOf("reason" to JsonPrimitive("ok")))
        }
        assertEquals(
            listOf(
                "GET /api/v1/waits",
                "GET /api/v1/waits?status=open&mine=true&before=w0&limit=10&resolver=human",
                "GET /api/v1/waits/w%2F1",
                "POST /api/v1/waits/w%2F1/answer",
                "POST /api/v1/waits/w%2F1/answer",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"action":"approve"}""", server.requests[3].bodyText())
        assertJson("""{"action":"approve","values":{"reason":"ok"}}""", server.requests[4].bodyText())
    }

    @Test
    fun artifactsAreListedReadVersionedAndOpenedWithATicket() = runTest {
        val server = ScriptedServer { request ->
            when (request.url.encodedPath) {
                "/api/v1/artifacts" -> json("""{"items":[$artifact],"next":"n"}""")
                "/api/v1/artifacts/a1/versions" -> json(
                    """{"items":[{"number":2,"files":["index.html"],"entry":"index.html",""" +
                        """"createdAt":"t","createdBy":"ada"}]}""",
                )
                "/api/v1/artifacts/a1/tickets" -> json(
                    """{"artifactId":"a1","version":2,"url":"https://c.example/x",""" +
                        """"contentUrl":"https://c.example/x","expiresAt":"t"}""",
                    HttpStatusCode.Created,
                )
                else -> json(artifact)
            }
        }
        server.client().use { client ->
            assertEquals("n", client.artifacts.list().next)
            client.artifacts.list(before = "n", limit = 20)
            assertEquals(2, client.artifacts.get("a1").latestVersion)
            assertEquals("index.html", client.artifacts.versions("a1").items.single().entry)
            assertEquals("https://c.example/x", client.artifacts.ticket("a1").url)
            client.artifacts.ticket("a1", version = 1)
        }
        assertEquals(
            listOf(
                "GET /api/v1/artifacts",
                "GET /api/v1/artifacts?before=n&limit=20",
                "GET /api/v1/artifacts/a1",
                "GET /api/v1/artifacts/a1/versions",
                "POST /api/v1/artifacts/a1/tickets",
                "POST /api/v1/artifacts/a1/tickets",
            ),
            server.requests.map { it.call() },
        )
        assertJson("{}", server.requests[4].bodyText())
        assertJson("""{"version":1}""", server.requests[5].bodyText())
    }

    @Test
    fun anIdThatIsNotAPathSegmentIsRefusedBeforeAnyRequest() = runTest {
        val server = ScriptedServer()
        server.client().use { client ->
            assertFailsWith<IllegalArgumentException> { client.approvals.view("..") }
            assertFailsWith<IllegalArgumentException> { client.waits.answer(".", "approve") }
            assertFailsWith<IllegalArgumentException> { client.pushDevices.delete("") }
        }
        assertEquals(emptyList(), server.requests.map { it.call() })
    }

    @Test
    fun theEnvironmentGivesNamesOnlyAndThePersonalVariantsAreManaged() = runTest {
        val variant = """{"id":"v 1","variable":"API_KEY","name":"Mine","binding":"shared","owner":"m1",""" +
            """"hasValue":true,"version":2,"createdAt":"t","updatedAt":"t"}"""
        val server = ScriptedServer { request ->
            when {
                request.url.encodedPath == "/api/v1/environment" ->
                    json("""{"variables":{"ZED":"secret-z","API_KEY":"secret-a"},"unavailable":["TOKEN","BROKEN"]}""")
                request.method == HttpMethod.Get -> json("""{"items":[$variant]}""")
                request.method == HttpMethod.Delete -> json("", HttpStatusCode.NoContent)
                else -> json(variant, HttpStatusCode.Created)
            }
        }
        server.client().use { client ->
            val names = client.environment.names()
            assertEquals(EnvironmentNames(listOf("API_KEY", "ZED"), listOf("BROKEN", "TOKEN")), names)
            assertEquals(false, names.toString().contains("secret"))
            assertEquals("m1", client.environment.listMine().items.single().owner)
            val created = client.environment.createMine(EnvironmentVariantCreate("API_KEY", "Mine", value = "s3cret"))
            assertEquals(2, created.version)
            client.environment.updateMine("v 1", EnvironmentVariantUpdate(expectedVersion = 2, name = "Renamed"))
            client.environment.deleteMine("v 1")
        }
        assertEquals(
            listOf(
                "GET /api/v1/environment",
                "GET /api/v1/me/environment/variants",
                "POST /api/v1/me/environment/variants",
                "PATCH /api/v1/me/environment/variants/v%201",
                "DELETE /api/v1/me/environment/variants/v%201",
            ),
            server.requests.map { it.call() },
        )
        assertJson("""{"variable":"API_KEY","name":"Mine","value":"s3cret"}""", server.requests[2].bodyText())
        assertJson("""{"expectedVersion":2,"name":"Renamed"}""", server.requests[3].bodyText())
    }

    @Test
    fun personalMcpServersAreListedCreatedSavedAndDeleted() = runTest {
        val hosted = """{"name":"docs","transport":"stdio","enabled":true,"scope":"org","owner":"m1",""" +
            """"state":"ready","stdio":{"launcher":"npm","packageName":"docs-mcp@1.2.3","args":["--fast"],""" +
            """"env":{"MODE":"ro"},"secretEnv":{"TOKEN":"docs-token"}}}"""
        val server = ScriptedServer { request ->
            when (request.method) {
                HttpMethod.Get -> json("""{"items":[$hosted]}""")
                HttpMethod.Delete -> json("", HttpStatusCode.NoContent)
                else -> json(hosted)
            }
        }
        server.client().use { client ->
            val listed = client.myMcpServers.list().items.single()
            assertEquals("docs-mcp@1.2.3", listed.stdio?.packageName)
            assertEquals(mapOf("TOKEN" to "docs-token"), listed.stdio?.secretEnv)
            val remote = McpServer(
                name = "remote",
                url = "https://mcp.example/x",
                transport = "http",
                enabled = true,
                scope = "org",
            )
            client.myMcpServers.create(remote)
            client.myMcpServers.save("a/b", listed)
            client.myMcpServers.delete("docs")
        }
        assertEquals(
            listOf(
                "GET /api/v1/me/mcp/servers",
                "POST /api/v1/me/mcp/servers",
                "PUT /api/v1/me/mcp/servers/a%2Fb",
                "DELETE /api/v1/me/mcp/servers/docs",
            ),
            server.requests.map { it.call() },
        )
        assertJson(
            """{"name":"remote","url":"https://mcp.example/x","transport":"http","enabled":true,"scope":"org"}""",
            server.requests[1].bodyText(),
        )
    }

    private val cloudSession = """{"id":"cs_1","owner":"m1","agent":"claude","status":"creating",""" +
        """"phase":"cloning_repositories",""" +
        """"epoch":1,"failure":{"code":"preparation_failed","message":"Clone failed.","retryable":true},""" +
        """"limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},""" +
        """"profile":{"id":"p1","version":3},"environment":{"repositories":[{"url":"https://github.com/o/r"}],""" +
        """"setup":{"create":["make"]}},"createdAt":"t"}"""

    @Test
    fun cloudSessionsAreListedWithTheirPhaseAndFailureAndReadOneByOne() = runTest {
        val server = ScriptedServer { request ->
            if (request.url.encodedPath == "/api/v1/cloud/sessions") {
                json("""{"items":[$cloudSession],"next":"c2"}""")
            } else {
                json(cloudSession)
            }
        }
        server.client().use { client ->
            val page = client.cloudSessions.list(status = "paused", before = "c1", limit = 20)
            assertEquals("c2", page.next)
            val read = page.items.single()
            assertEquals("cloning_repositories", read.phase)
            assertEquals(true, read.failure?.retryable)
            assertEquals(listOf("make"), read.environment?.setup?.create)
            assertEquals(emptyList(), read.environment?.setup?.start)
            client.cloudSessions.list()
            assertEquals(1L, client.cloudSessions.get("cs_1").epoch)
        }
        assertEquals(
            listOf(
                "GET /api/v1/cloud/sessions?status=paused&before=c1&limit=20",
                "GET /api/v1/cloud/sessions",
                "GET /api/v1/cloud/sessions/cs_1",
            ),
            server.requests.map { it.call() },
        )
    }

    @Test
    fun cloudSessionsAreCreatedWithARecipeOrARepoPausedResumedAndStopped() = runTest {
        val server = ScriptedServer { json(cloudSession) }
        server.client().use { client ->
            client.cloudSessions.create(
                CreateCloudSessionRequest(
                    agent = CloudAgentKind.CLAUDE,
                    prompt = "Fix it",
                    environment = CloudEnvironment(
                        repositories = listOf(
                            CloudRepository("https://github.com/o/r", path = "r", ref = "main", branch = "task"),
                        ),
                        workingDirectory = "r",
                        setup = CloudSetup(create = listOf("npm ci"), start = listOf("npm run dev &")),
                        requiredVariables = listOf("API_KEY"),
                        requireGitHub = true,
                        gitEmailDomain = "example.com",
                    ),
                ),
            )
            client.cloudSessions.create(
                CreateCloudSessionRequest(CloudAgentKind.SHELL, repo = "https://github.com/o/r", ref = "dev"),
            )
            client.cloudSessions.pause("cs_1")
            client.cloudSessions.resume("cs_1")
            client.cloudSessions.stop("cs_1")
        }
        assertEquals(
            listOf(
                "POST /api/v1/cloud/sessions",
                "POST /api/v1/cloud/sessions",
                "POST /api/v1/cloud/sessions/cs_1/pause",
                "POST /api/v1/cloud/sessions/cs_1/resume",
                "DELETE /api/v1/cloud/sessions/cs_1",
            ),
            server.requests.map { it.call() },
        )
        assertJson(
            """{"agent":"claude","prompt":"Fix it","environment":{"repositories":[{"url":"https://github.com/o/r",""" +
                """"path":"r","ref":"main","branch":"task"}],"workingDirectory":"r","setup":{"create":["npm ci"],""" +
                """"start":["npm run dev &"]},"requiredVariables":["API_KEY"],"requireGitHub":true,""" +
                """"gitEmailDomain":"example.com"}}""",
            server.requests[0].bodyText(),
        )
        assertJson("""{"agent":"shell","repo":"https://github.com/o/r","ref":"dev"}""", server.requests[1].bodyText())
    }

    @Test
    fun anAgentKindThisVersionDoesNotKnowReadsAsUnknown() {
        assertEquals(
            CloudAgentKind.UNKNOWN,
            OstromJson.decodeFromString(CloudAgentKind.serializer(), "\"aider\""),
        )
    }
}
