package com.openmesh.vpn.android

import com.openmesh.gateway.GatewayAudit
import com.openmesh.gateway.GatewayMetrics
import com.openmesh.gateway.NoopGatewayAudit
import com.openmesh.gateway.client.GatewayEndpoint
import com.openmesh.gateway.client.RemoteGatewayConnector
import com.openmesh.gateway.socks5.SocketProtector
import com.openmesh.gateway.socks5.Socks5Credentials
import com.openmesh.gateway.socks5.Socks5Server
import com.openmesh.gateway.socks5.SocksDnsResolver
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64

/**
 * P2 client frontend: tun2socks talks SOCKS5 to this loopback server, which
 * opens an authenticated TLS 1.3 session to a remote OpenMesh gateway for
 * every destination. DNS is sent as DNS-over-TCP through the same gateway.
 *
 * The frontend starts even when the gateway is unreachable. That is
 * intentional: tun2socks connections then fail closed instead of leaking
 * through the underlying network.
 */
class RemoteSocksFrontend(
    private val protector: SocketProtector,
    private val endpoint: GatewayEndpoint,
    private val audit: GatewayAudit = NoopGatewayAudit,
) : SocksFrontend {
    private var server: Socks5Server? = null
    private var credentials: Socks5Credentials? = null
    val metrics: GatewayMetrics = GatewayMetrics()

    override val port: Int
        get() = server?.boundPort ?: error("remote SOCKS frontend is not running")

    override val socksCredentials: Socks5Credentials
        get() = credentials ?: error("remote SOCKS frontend is not running")

    fun start(): RemoteSocksFrontend {
        check(server == null) { "already started" }
        val random = SecureRandom()
        fun token(bytes: Int): String {
            val data = ByteArray(bytes)
            random.nextBytes(data)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        }
        val localCredentials = Socks5Credentials(
            username = "om-" + token(12),
            password = token(24),
        )
        credentials = localCredentials
        val connector = RemoteGatewayConnector(endpoint = endpoint, protector = protector)
        val resolver = SocksDnsResolver(
            connector = connector,
            upstreams = listOf(
                InetSocketAddress("1.1.1.1", 53),
                InetSocketAddress("9.9.9.9", 53),
            ),
        )
        val socks = Socks5Server(
            config = Socks5Server.Config(
                bindAddress = InetAddress.getByName("127.0.0.1"),
                port = 0,
                credentials = localCredentials,
                maxConnections = 128,
                idleTimeoutMillis = 300_000,
                allowUdpDns = true,
            ),
            connector = connector,
            audit = audit,
            metrics = metrics,
            protector = protector,
            dnsResolver = resolver,
        ).start()
        server = socks
        return this
    }

    override fun close() {
        server?.close()
        server = null
        credentials = null
    }
}
