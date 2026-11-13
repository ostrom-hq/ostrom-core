// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Stand-ins named like each engine's exceptions: the classifier reads names and messages, never engine types.
private class CurlIllegalStateException(message: String) : IllegalStateException(message)
private class DarwinHttpRequestException(message: String) : IllegalStateException(message)
private class WinHttpIllegalStateException(message: String) : IllegalStateException(message)
private class SSLHandshakeException(message: String) : IllegalStateException("$message CurlRequestData(")
private class UnknownHostException(message: String) : IllegalStateException("$message CurlRequestData(")
private class TimeoutException(message: String) : IllegalStateException("$message CurlRequestData(")
private class ConnectException(message: String) : IllegalStateException("$message CurlRequestData(")
private class EOFException(message: String) : IllegalStateException("$message CurlRequestData(")

/** [networkFailure] by family, for each engine Ostrom's clients use, ported from teamcode's CliErrorsTest. */
class NetworkFailureTest {

    @Test
    fun eachEngineFailureIsClassifiedByItsFamily() {
        val cases = listOf(
            CurlIllegalStateException("Connection failed for request. Reason: Could not resolve hostname") to
                NetworkFailure.DNS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1004 \"Could not connect.\"") to
                NetworkFailure.REFUSED,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1001") to NetworkFailure.TIMEOUT,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1202 \"bad\"") to NetworkFailure.TLS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1003 x") to NetworkFailure.DNS,
            DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-999 x") to NetworkFailure.OTHER,
            WinHttpIllegalStateException("Error 12029: the server could not be reached") to NetworkFailure.REFUSED,
            WinHttpIllegalStateException("Error 12007") to NetworkFailure.DNS,
            WinHttpIllegalStateException("Error 12002") to NetworkFailure.TIMEOUT,
            WinHttpIllegalStateException("Error 12175") to NetworkFailure.TLS,
            WinHttpIllegalStateException("Error 120290") to NetworkFailure.OTHER,
            ConnectTimeoutException("Connect timeout has expired", null) to NetworkFailure.TIMEOUT,
            SocketTimeoutException("Socket timeout", null) to NetworkFailure.TIMEOUT,
            HttpRequestTimeoutException("https://ostrom.example/api/v1/capabilities", 30_000) to NetworkFailure.TIMEOUT,
            CurlIllegalStateException("Timeout was reached") to NetworkFailure.TIMEOUT,
            CurlIllegalStateException("Reason: Send failure") to NetworkFailure.OTHER,
            CurlIllegalStateException("Could not resolve proxy: proxy.example") to NetworkFailure.PROXY,
            CurlIllegalStateException("Received HTTP code 407 from the web proxy") to NetworkFailure.PROXY,
            CurlIllegalStateException("Connection refused") to NetworkFailure.REFUSED,
            CurlIllegalStateException("TLS verification failed for request") to NetworkFailure.TLS,
            CurlIllegalStateException("Header line length limit exceeded") to NetworkFailure.HEADERS,
            SSLHandshakeException("handshake") to NetworkFailure.TLS,
            UnknownHostException("ostrom.example") to NetworkFailure.DNS,
            TimeoutException("read") to NetworkFailure.TIMEOUT,
            ConnectException("refused") to NetworkFailure.REFUSED,
            ConnectException("CONNECT tunnel failed") to NetworkFailure.PROXY,
            EOFException("closed") to NetworkFailure.CLOSED,
            IllegalStateException("wrapped", CurlIllegalStateException("Couldn't connect to server")) to
                NetworkFailure.REFUSED,
            // No message, no class name: what is left still classifies.
            IllegalStateException(null, CurlIllegalStateException("Failed to connect")) to NetworkFailure.REFUSED,
            object : IllegalStateException("Connection refused CurlRequestData(") {} to NetworkFailure.REFUSED,
        )
        for ((error, family) in cases) {
            assertEquals(family, networkFailure(error), error.toString())
        }
    }

    @Test
    fun aPortNumberInAnotherEngineMessageIsNotAnEngineCode() {
        assertEquals(NetworkFailure.OTHER, networkFailure(CurlIllegalStateException("https://ostrom.example:12002/ x")))
        // A lower-case host is not a TLS failure.
        assertEquals(
            NetworkFailure.REFUSED,
            networkFailure(CurlIllegalStateException("https://tls.example.com: Could not connect to server")),
        )
    }

    @Test
    fun aFailureOutsideTheHttpStackIsNotANetworkFailure() {
        assertNull(networkFailure(IllegalStateException("No space left on device")))
        assertNull(networkFailure(IllegalArgumentException("handler bug")))
    }
}
