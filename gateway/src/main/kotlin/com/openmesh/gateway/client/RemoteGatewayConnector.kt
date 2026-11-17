package com.openmesh.gateway.client

import com.openmesh.gateway.socks5.NoopSocketProtector
import com.openmesh.gateway.socks5.OutboundConnector
import com.openmesh.gateway.socks5.SocketProtector
import com.openmesh.gateway.socks5.Socks5Client
import com.openmesh.gateway.socks5.Socks5Credentials
import com.openmesh.transport.Fingerprint
import com.openmesh.transport.PinnedFingerprintTrustManager
import com.openmesh.transport.TlsClient
import com.openmesh.transport.TlsContexts
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * Everything a client needs to reach one OpenMesh gateway.
 *
 * - [fingerprint] is the SHA-256 fingerprint of the gateway's self-signed
 *   certificate. It is pinned: a different certificate is a hard failure.
 * - [token] is the per-gateway bearer secret used by RFC 1929 authentication
 *   inside the TLS session. It never travels in clear text.
 */
data class GatewayEndpoint(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val username: String = "openmesh",
) {
    init {
        require(host.isNotBlank()) { "gateway host must not be blank" }
        require(port in 1..65535) { "invalid gateway port $port" }
        Fingerprint.normalize(fingerprint)
        require(token.toByteArray(Charsets.UTF_8).size in 1..255) {
            "gateway token must be 1..255 bytes"
        }
        require(username.toByteArray(Charsets.UTF_8).size in 1..255) {
            "gateway username must be 1..255 bytes"
        }
    }
}

/**
 * Opens authenticated TLS 1.3 sessions to a gateway and speaks SOCKS5 through
 * them. One TCP/TLS connection is used per destination; there is no
 * multiplexing and no connection reuse in P2.
 */
class RemoteGatewayConnector(
    val endpoint: GatewayEndpoint,
    private val protector: SocketProtector = NoopSocketProtector,
    private val connectTimeoutMillis: Int = 10_000,
) : OutboundConnector {
    private val tlsContext = TlsContexts.client(
        PinnedFingerprintTrustManager(endpoint.fingerprint),
    )
    private val credentials = Socks5Credentials(endpoint.username, endpoint.token)

    /**
     * Connects and authenticates to the gateway, but sends no SOCKS request.
     * Used to distinguish "gateway unreachable or untrusted" from "gateway
     * authenticated but the destination failed".
     */
    fun openAuthenticated(): SSLSocket {
        val raw = Socket()
        try {
            protector.protect(raw)
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            val tls = TlsClient.connect(
                raw = raw,
                host = SNI_HOST,
                port = endpoint.port,
                context = tlsContext,
                timeoutMillis = connectTimeoutMillis,
            )
            try {
                Socks5Client.authenticate(tls, credentials)
                return tls
            } catch (e: Exception) {
                closeQuietly(tls)
                throw e
            }
        } catch (e: Exception) {
            closeQuietly(raw)
            throw e
        }
    }

    override fun connect(host: String, port: Int): Socket {
        val socket = openAuthenticated()
        try {
            Socks5Client.connect(socket, host, port)
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            closeQuietly(socket)
            throw e
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
            // best effort
        }
    }

    private companion object {
        // The certificate is not validated by hostname; the pin is the
        // authentication check. A stable SNI value avoids sending an IP
        // literal as a server name.
        const val SNI_HOST = "openmesh-gateway"
    }
}
