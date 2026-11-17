package com.openmesh.vpn.android

import com.openmesh.gateway.GatewayAudit
import com.openmesh.gateway.GatewayMetrics
import com.openmesh.gateway.socks5.DestinationPolicy
import com.openmesh.gateway.socks5.DirectSocketConnector
import com.openmesh.gateway.socks5.SocketProtector
import com.openmesh.gateway.socks5.Socks5Credentials
import com.openmesh.gateway.socks5.Socks5Server
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64

/**
 * P1 lab frontend: tun2socks speaks SOCKS5 to this loopback-only server, which
 * dials destinations directly. It exists to validate TUN -> tun2socks -> SOCKS
 * before the mesh gateway path is available, and it is never used in a release
 * build.
 */
class LocalSocksFrontend(
    private val protector: SocketProtector,
    private val labAllowlist: Set<String>,
    private val audit: GatewayAudit,
) : SocksFrontend {
    private var server: Socks5Server? = null
    private var credentials: Socks5Credentials? = null
    val metrics: GatewayMetrics = GatewayMetrics()

    override val port: Int
        get() = server?.boundPort ?: error("local SOCKS frontend is not running")

    override val socksCredentials: Socks5Credentials
        get() = credentials ?: error("local SOCKS frontend is not running")

    fun start(): LocalSocksFrontend {
        check(server == null) { "already started" }
        val random = SecureRandom()
        fun token(bytes: Int): String {
            val data = ByteArray(bytes)
            random.nextBytes(data)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        }
        val creds = Socks5Credentials(
            username = "om-" + token(12),
            password = token(24),
        )
        credentials = creds
        val policy = DestinationPolicy(
            allowPrivateDestinations = false,
            labAllowlist = labAllowlist,
        )
        val serverConfig = Socks5Server.Config(
            bindAddress = InetAddress.getByName("127.0.0.1"),
            port = 0,
            credentials = creds,
            maxConnections = 128,
            idleTimeoutMillis = 300_000,
            allowUdpDns = true,
            dnsUpstreams = listOf(
                InetSocketAddress("1.1.1.1", 53),
                InetSocketAddress("9.9.9.9", 53),
            ),
        )
        val socks = Socks5Server(
            config = serverConfig,
            connector = DirectSocketConnector(
                policy = policy,
                protector = protector,
                connectTimeoutMillis = 10_000,
            ),
            audit = audit,
            metrics = metrics,
            protector = protector,
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
