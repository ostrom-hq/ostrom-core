// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0): the origin check, HTTP configuration
// and lifetime of client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/TeamCodeClient.kt.
package run.ostrom.core.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import run.ostrom.core.api.OstromJson
import run.ostrom.core.api.models.Capabilities

/**
 * The typed client of one Ostrom server, for one account: the companion app holds one per signed-in account, the CLI
 * one per command. It owns no credential store: [token] and [organization] are read at each call, so a sign-in, a
 * sign-out or a change of organization takes effect at once. The caller supplies the HTTP engine through
 * [createHttpClient] (Darwin on iOS, OkHttp on Android, Curl or WinHttp in the CLI), called once here with the
 * configuration to apply; [close] closes the client it returned. Creating one is cheap: one HTTP client, no request.
 *
 * Every call sends `Authorization: Bearer` when [token] gives one, `Ostrom-Org` when [organization] gives one, and
 * `Accept: application/json`. A non-2xx answer throws [OstromException]; a network failure propagates as the engine
 * reports it ([networkFailure] tells its family). Redirects are never followed, so a credential never reaches another
 * origin.
 *
 * @param serverUrl The server's origin: `https://…`, or `http://` on loopback only, without credentials, path, query
 *   or fragment.
 * @param token The bearer token of the account, or null before sign-in.
 * @param organization The organization the calls act in (`Ostrom-Org`), or null to let the server choose the only one.
 * @param createHttpClient Builds an independently owned [HttpClient] with the configuration it is given, as
 *   `{ configure -> HttpClient(Darwin) { configure(this) } }`.
 * @param userAgent Sent as `User-Agent` when set (`ostrom-app/1.2.0 (iOS 18.1)`).
 */
public class OstromClient(
    serverUrl: String,
    token: suspend () -> String?,
    organization: suspend () -> String? = { null },
    createHttpClient: (HttpClientConfig<*>.() -> Unit) -> HttpClient,
    userAgent: String? = null,
) : AutoCloseable {
    /** The normalized origin every call goes to, without a trailing slash (`https://ostrom.example`). */
    public val origin: String = normalizedOrigin(serverUrl)

    internal val transport: Transport = Transport(
        origin = origin,
        token = token,
        organization = organization,
        userAgent = userAgent,
        http = createHttpClient {
            expectSuccess = false
            // A redirect must not carry the account's credential to another origin.
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                socketTimeoutMillis = CALL_TIMEOUT_MILLIS
                requestTimeoutMillis = CALL_TIMEOUT_MILLIS
            }
            install(ContentNegotiation) { json(OstromJson) }
        },
    )

    /** Sign-in, the caller and their account. */
    public val auth: AuthClient = AuthClient(transport)

    /** The caller's organizations. */
    public val organizations: OrganizationsClient = OrganizationsClient(transport)

    /** The caller's yes queue and their answers. */
    public val approvals: ApprovalsClient = ApprovalsClient(transport)

    /** Signed receipts and their keys. */
    public val receipts: ReceiptsClient = ReceiptsClient(transport)

    /** What the agents cost. */
    public val spending: SpendingClient = SpendingClient(transport)

    /** The organization's activity feed. */
    public val activity: ActivityClient = ActivityClient(transport)

    /** The caller's phones registered for push notifications. */
    public val pushDevices: PushDevicesClient = PushDevicesClient(transport)

    /** What the caller is notified of. */
    public val notifications: NotificationsClient = NotificationsClient(transport)

    /** The waits a person answers. */
    public val waits: WaitsClient = WaitsClient(transport)

    /** Published artifacts. */
    public val artifacts: ArtifactsClient = ArtifactsClient(transport)

    /** The organization's live events. */
    public val events: EventsClient = EventsClient(transport)

    /**
     * What the server speaks and serves (`GET /api/v1/capabilities`). Without a token the server answers `401`
     * `authentication_required`, which says "an Ostrom server, sign in next".
     */
    public suspend fun capabilities(): Capabilities = transport.json(HttpMethod.Get, "/api/v1/capabilities")

    /** Closes the HTTP client [createHttpClient] built; the client is not used afterwards. */
    override fun close() {
        transport.http.close()
    }

    /** Constants of the client. */
    public companion object {
        /**
         * The [OstromException.type] of a stream event this client cannot read (undecodable or over the size bound).
         * Reconnecting replays the same event, so a caller stops (or updates) instead of retrying forever.
         */
        public const val MALFORMED_EVENT: String = "malformed_event"

        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val CALL_TIMEOUT_MILLIS = 30_000L
        private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /** [serverUrl] reduced to its origin, after the checks of the class documentation. */
        internal fun normalizedOrigin(serverUrl: String): String {
            require(serverUrl.isNotBlank()) { "An Ostrom server URL is required." }
            val url = Url(serverUrl.trim())
            require(url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP && url.host in LOOPBACK) {
                "Ostrom requires HTTPS except on loopback."
            }
            require(
                url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.encodedPath in setOf("", "/") &&
                    url.parameters.isEmpty() && url.fragment.isEmpty(),
            ) { "Use a server origin without credentials, path, query or fragment." }
            return url.toString().trimEnd('/')
        }
    }
}
