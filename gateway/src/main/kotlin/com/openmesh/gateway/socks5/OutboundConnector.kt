package com.openmesh.gateway.socks5

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Lets the Android VpnService exempt OpenMesh's own sockets from the tunnel.
 * On a pure JVM the default implementation is a no-op.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean = true
    fun protect(socket: DatagramSocket): Boolean = true
    fun protect(socket: ServerSocket): Boolean = true
}

object NoopSocketProtector : SocketProtector

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM = HostResolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

interface OutboundConnector {
    /**
     * Opens a TCP stream to [host]:[port]. Implementations must apply their
     * own destination policy before dialling and must dial only an address
     * that was validated.
     */
    fun connect(host: String, port: Int): Socket
}

/**
 * Lab/direct connector. It is used only when OpenMesh is explicitly in
 * "direct SOCKS lab" mode; production traffic must go through a gateway.
 */
class DirectSocketConnector(
    private val policy: DestinationPolicy,
    private val protector: SocketProtector = NoopSocketProtector,
    private val resolver: HostResolver = HostResolver.SYSTEM,
    private val connectTimeoutMillis: Int = 10_000,
) : OutboundConnector {
    override fun connect(host: String, port: Int): Socket {
        val resolved = try {
            resolver.resolve(host)
        } catch (e: Exception) {
            throw Socks5Exception(
                Socks5.REPLY_HOST_UNREACHABLE,
                "DNS resolution failed for $host",
                e,
            )
        }
        val approved = try {
            policy.approve(host, port, resolved)
        } catch (e: PolicyDeniedException) {
            throw Socks5Exception(Socks5.REPLY_NOT_ALLOWED, e.message ?: "destination denied", e)
        }
        var lastError: Exception? = null
        for (address in approved) {
            val socket = Socket()
            try {
                protector.protect(socket)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
                return socket
            } catch (e: Exception) {
                lastError = e
                try {
                    socket.close()
                } catch (_: Exception) {
                    // best effort
                }
                if (e is SocketException && e.message?.contains("denied", true) == true) {
                    break
                }
            }
        }
        throw Socks5Exception(
            Socks5.REPLY_HOST_UNREACHABLE,
            "cannot connect to $host:$port",
            lastError,
        )
    }
}
