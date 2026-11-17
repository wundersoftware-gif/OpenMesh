package com.openmesh.gateway

import com.openmesh.gateway.socks5.DestinationPolicy
import com.openmesh.gateway.socks5.DirectSocketConnector
import com.openmesh.gateway.socks5.HostResolver
import com.openmesh.gateway.socks5.NoopSocketProtector
import com.openmesh.gateway.socks5.Socks5Credentials
import com.openmesh.gateway.socks5.Socks5Server
import com.openmesh.transport.TlsIdentity
import com.openmesh.transport.TlsServerSocketFactory
import java.net.InetAddress
import javax.net.ssl.SSLSocket

/**
 * P2 direct gateway: a TLS 1.3 listener that speaks an authenticated,
 * destination-restricted SOCKS5 dialect and exits directly to the Internet.
 *
 * It is deliberately not a relay: there is no mesh routing, no peer
 * forwarding and no central service. A client either reaches this gateway or
 * fails closed.
 */
class GatewayServer(
    identity: TlsIdentity,
    credentials: Socks5Credentials,
    listenAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    port: Int = 8443,
    policy: DestinationPolicy = DestinationPolicy(),
    resolver: HostResolver = HostResolver.SYSTEM,
    audit: GatewayAudit = NoopGatewayAudit,
    gatewayMetrics: GatewayMetrics = GatewayMetrics(),
    connectTimeoutMillis: Int = 15_000,
) : AutoCloseable {
    private val tlsFactory = TlsServerSocketFactory(identity)
    private val socks = Socks5Server(
        config = Socks5Server.Config(
            bindAddress = listenAddress,
            port = port,
            credentials = credentials,
            maxConnections = 128,
            idleTimeoutMillis = 300_000,
            connectTimeoutMillis = connectTimeoutMillis,
            // Application UDP is never relayed. DNS arrives as TCP through
            // the SOCKS CONNECT path instead.
            allowUdpDns = false,
        ),
        connector = DirectSocketConnector(
            policy = policy,
            protector = NoopSocketProtector,
            resolver = resolver,
            connectTimeoutMillis = connectTimeoutMillis,
        ),
        audit = audit,
        metrics = gatewayMetrics,
        protector = NoopSocketProtector,
        serverSocketFactory = { tlsFactory.create() },
        connectionInitializer = { socket ->
            (socket as SSLSocket).startHandshake()
        },
    )

    val metrics: GatewayMetrics = gatewayMetrics

    val boundPort: Int
        get() = socks.boundPort

    fun start(): GatewayServer {
        socks.start()
        return this
    }

    override fun close() {
        socks.close()
    }
}
