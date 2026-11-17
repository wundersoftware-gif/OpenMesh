package com.openmesh.vpn.android

import com.openmesh.core.model.InternetState
import com.openmesh.core.model.RouteState
import com.openmesh.gateway.client.RemoteGatewayConnector
import com.openmesh.gateway.socks5.Socks5Client
import com.openmesh.gateway.socks5.Socks5Exception
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Honest end-to-end check for the P2 path.
 *
 * 1. TLS 1.3 to the gateway, pinned certificate, RFC 1929 token.
 * 2. SOCKS5 CONNECT to example.com:443 through the gateway.
 * 3. A second, end-to-end TLS 1.3 session to example.com with the system
 *    trust store and hostname verification.
 * 4. A HEAD request with a 2xx/3xx response.
 *
 * Step 1 proves the gateway route and its credentials. Only step 4 proves
 * that the Internet is actually reachable through it.
 */
class RemoteGatewayProbe(private val connector: RemoteGatewayConnector) {

    data class Outcome(
        val route: RouteState,
        val internet: InternetState,
        val detail: String,
    )

    fun run(): Outcome {
        val gatewaySocket = try {
            connector.openAuthenticated()
        } catch (e: Exception) {
            return Outcome(
                route = RouteState.NONE,
                internet = InternetState.FAILED,
                detail = "gateway unreachable or untrusted: ${describe(e)}",
            )
        }
        try {
            gatewaySocket.soTimeout = CONNECT_TIMEOUT_MILLIS
            try {
                Socks5Client.connect(gatewaySocket, PROBE_HOST, PROBE_PORT)
            } catch (e: Exception) {
                val reply = (e as? Socks5Exception)?.replyCode?.let { " (SOCKS reply $it)" } ?: ""
                return Outcome(
                    route = RouteState.SELECTED,
                    internet = InternetState.FAILED,
                    detail = "gateway authenticated, but $PROBE_HOST:$PROBE_PORT failed$reply: " +
                        describe(e),
                )
            }

            val tls = try {
                openEndToEndTls(gatewaySocket)
            } catch (e: Exception) {
                return Outcome(
                    route = RouteState.SELECTED,
                    internet = InternetState.FAILED,
                    detail = "gateway authenticated, but TLS to $PROBE_HOST failed: ${describe(e)}",
                )
            }

            return try {
                tls.soTimeout = READ_TIMEOUT_MILLIS
                val statusLine = request(tls)
                val code = parseStatusCode(statusLine)
                if (code != null && code in 200..399) {
                    Outcome(
                        route = RouteState.SELECTED,
                        internet = InternetState.VERIFIED,
                        detail = "$PROBE_HOST answered HTTP $code via gateway (TLS 1.3)",
                    )
                } else {
                    Outcome(
                        route = RouteState.SELECTED,
                        internet = InternetState.FAILED,
                        detail = "unexpected probe response: ${statusLine.take(120)}",
                    )
                }
            } catch (e: Exception) {
                Outcome(
                    route = RouteState.SELECTED,
                    internet = InternetState.FAILED,
                    detail = "HTTPS probe to $PROBE_HOST failed: ${describe(e)}",
                )
            } finally {
                closeQuietly(tls)
            }
        } finally {
            closeQuietly(gatewaySocket)
        }
    }

    private fun openEndToEndTls(gatewaySocket: Socket): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val tls = context.socketFactory.createSocket(
            gatewaySocket,
            PROBE_HOST,
            PROBE_PORT,
            true,
        ) as SSLSocket
        tls.soTimeout = READ_TIMEOUT_MILLIS
        tls.sslParameters = tls.sslParameters.apply {
            protocols = arrayOf("TLSv1.3")
            endpointIdentificationAlgorithm = "HTTPS"
        }
        tls.startHandshake()
        return tls
    }

    private fun request(tls: SSLSocket): String {
        val output = tls.outputStream
        output.write(
            (
                "HEAD / HTTP/1.1\r\n" +
                    "Host: $PROBE_HOST\r\n" +
                    "User-Agent: OpenMeshProbe/0.1\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        output.flush()
        val reader = BufferedReader(InputStreamReader(tls.inputStream, Charsets.US_ASCII))
        return reader.readLine() ?: ""
    }

    private fun parseStatusCode(statusLine: String): Int? {
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) return null
        return parts[1].toIntOrNull()
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return "${error.javaClass.simpleName}: $message"
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
            // best effort
        }
    }

    private companion object {
        const val PROBE_HOST = "example.com"
        const val PROBE_PORT = 443
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}
