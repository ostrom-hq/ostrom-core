// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (https://github.com/nathanfallet/teamcode, Apache-2.0):
// client/src/commonMain/kotlin/me/nathanfallet/teamcode/client/NetworkFailures.kt.
package run.ostrom.core.client

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Network failures by family, for a caller that tells the person what to check. [HEADERS] is an answer whose headers
 * the engine's parser refused (a header line over 8 192 bytes for ktor-http-cio, used by the curl and WinHttp
 * engines); [PROXY] a proxy that refused or failed the connection.
 */
public enum class NetworkFailure { DNS, REFUSED, TIMEOUT, TLS, CLOSED, PROXY, HEADERS, OTHER }

/**
 * The family of [error], detected from Ktor's common types and each engine's error names and messages (inspected,
 * never printed); null when the error was not raised by the HTTP stack. A local disk or permission failure (also an
 * IOException on the JVM) must not read as an unreachable server.
 */
public fun networkFailure(error: Throwable): NetworkFailure? {
    val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
    val failure = FailureText(chain)
    return when {
        failure.mentions(HEADER_TEXT) -> NetworkFailure.HEADERS
        !failure.fromHttpStack() -> null
        else -> failure.byName() ?: failure.byMessage()
    }
}

private class FailureText(private val chain: List<Throwable>) {
    private val text = chain.joinToString("\n") { it.message.orEmpty() }
    private val names = chain.map { it::class.simpleName.orEmpty() }
    private val timeout = chain.any {
        it is ConnectTimeoutException || it is SocketTimeoutException || it is HttpRequestTimeoutException
    }

    fun mentions(fragments: List<String>): Boolean = fragments.any { text.contains(it) }

    fun named(candidates: List<String>): Boolean = names.any { name -> candidates.any { name.contains(it) } }

    // The curl engine throws plain IllegalStateExceptions; its messages name the request it failed.
    fun fromHttpStack(): Boolean {
        val packages = chain.map { it::class.qualifiedName.orEmpty() }
        // Ktor's own timeouts are io.ktor classes, found by package.
        return packages.any { name -> HTTP_PACKAGES.any { name.startsWith(it) } } || named(ENGINE_NAMES) ||
            mentions(CURL_TEXT)
    }

    fun byName(): NetworkFailure? = when {
        named(TLS_NAMES) -> NetworkFailure.TLS
        named(DNS_NAMES) -> NetworkFailure.DNS
        timeout || named(TIMEOUT_NAMES) -> NetworkFailure.TIMEOUT
        named(REFUSED_NAMES) -> if (mentions(CURL_PROXY_TEXT)) NetworkFailure.PROXY else NetworkFailure.REFUSED
        named(CLOSED_NAMES) -> NetworkFailure.CLOSED
        else -> null
    }

    fun byMessage(): NetworkFailure = when {
        // curl's own proxy errors first: "Could not resolve proxy" would otherwise read as DNS.
        mentions(CURL_PROXY_TEXT) -> NetworkFailure.PROXY
        mentions(TLS_TEXT) || code(DARWIN_TLS, WINHTTP_TLS) -> NetworkFailure.TLS
        mentions(DNS_TEXT) || code(DARWIN_DNS, WINHTTP_DNS) -> NetworkFailure.DNS
        mentions(TIMEOUT_TEXT) || code(DARWIN_TIMEOUT, WINHTTP_TIMEOUT) -> NetworkFailure.TIMEOUT
        mentions(PROXY_TEXT) -> NetworkFailure.PROXY
        mentions(REFUSED_TEXT) || code(DARWIN_REFUSED, WINHTTP_REFUSED) -> NetworkFailure.REFUSED
        else -> NetworkFailure.OTHER
    }

    // Numeric engine codes only count for their own engine, so a port number elsewhere cannot match.
    private fun code(darwinCodes: List<Int>, winHttpCodes: List<Int>): Boolean {
        val darwin = named(listOf("Darwin")) && darwinCodes.any { darwinCode(it).containsMatchIn(text) }
        val winHttp = named(listOf("WinHttp")) && winHttpCodes.any { Regex("(^|\\D)$it(\\D|$)").containsMatchIn(text) }
        return darwin || winHttp
    }
}

// `Code=-1004 ` or `Code=-1004"` inside NSError's description, or at its very end.
private fun darwinCode(code: Int) = Regex("Code=$code([ \"]|$)")

private const val MAX_CAUSES = 8

private val HTTP_PACKAGES = listOf(
    "io.ktor.", "java.net.", "javax.net.", "okhttp3.", "java.nio.channels.UnresolvedAddress",
)

// Engines: OkHttp (Android and JVM), Curl (Linux), Darwin (Apple) and WinHttp (Windows). Case-sensitive on purpose:
// a lowercase host name such as `tls.example.com` in an engine message must not look like a TLS failure.
private val ENGINE_NAMES = listOf("Curl", "Darwin", "WinHttp")
private val TLS_NAMES = listOf("SSL", "Tls", "TLS", "Certificate")
private val DNS_NAMES = listOf("UnknownHost", "UnresolvedAddress")
private val TIMEOUT_NAMES = listOf("Timeout")
private val REFUSED_NAMES = listOf("ConnectException", "NoRouteToHost", "PortUnreachable")
private val CLOSED_NAMES = listOf("EOFException", "ClosedReceiveChannel", "ClosedByteChannel")
private val CURL_TEXT = listOf(
    "CurlRequestData(", "Connection failed for request", "TLS verification failed for request",
)

// ktor-http-cio's own wording (HttpParser, and the curl and WinHttp engines that use it), not generic parser errors.
private val HEADER_TEXT = listOf("Header line length limit exceeded", "Failed to parse response header")
private val CURL_PROXY_TEXT = listOf(
    "resolve proxy", "from proxy after CONNECT", "CONNECT tunnel failed", "Proxy CONNECT aborted",
)
private val PROXY_TEXT = listOf("proxy server", "web proxy", "Proxy Authentication")
private val TLS_TEXT = listOf("SSL", "TLS", "certificate", "Certificate", "security error")
private val DNS_TEXT = listOf(
    "resolve host", "Could not resolve", "Couldn't resolve", "could not be resolved", "Unable to resolve",
    "nodename nor servname", "Name or service not known",
)
private val TIMEOUT_TEXT = listOf("timed out", "Timeout was reached")
private val REFUSED_TEXT = listOf(
    "Couldn't connect", "Could not connect", "Connection refused", "Failed to connect", "could not be established",
)
private val DARWIN_TLS = listOf(-1200, -1201, -1202, -1203, -1204, -1205, -1206, -2000)
private val WINHTTP_TLS = listOf(12175, 12037, 12038, 12044, 12045, 12057, 12169)
private val DARWIN_DNS = listOf(-1003, -1006)
private val WINHTTP_DNS = listOf(12007)
private val DARWIN_TIMEOUT = listOf(-1001)
private val WINHTTP_TIMEOUT = listOf(12002)
private val DARWIN_REFUSED = listOf(-1004, -1009, -1005)
private val WINHTTP_REFUSED = listOf(12029, 12030)
