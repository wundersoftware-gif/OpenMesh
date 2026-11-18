package com.openmesh.gateway.socks5

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Resolves one DNS query and returns the raw response.
 *
 * The P1 lab frontend uses plain UDP to a public resolver. The P2 client must
 * not send DNS outside the tunnel, so it uses DNS-over-TCP through the
 * authenticated gateway instead (see [SocksDnsResolver]).
 */
interface DnsResolver {
    fun resolve(query: ByteArray): ByteArray?
}

/**
 * Direct UDP DNS used by the P1 lab frontend only. It is never used on the P2
 * client path because it would leak the query to the local network.
 */
class UdpDnsResolver(
    private val upstreams: List<InetSocketAddress>,
    private val protector: SocketProtector = NoopSocketProtector,
    private val timeoutMillis: Int = 5_000,
    private val maxResponseBytes: Int = 4_096,
) : DnsResolver {
    override fun resolve(query: ByteArray): ByteArray? {
        for (upstream in upstreams) {
            val socket = DatagramSocket()
            try {
                protector.protect(socket)
                socket.soTimeout = timeoutMillis
                socket.connect(upstream)
                socket.send(DatagramPacket(query, query.size))
                val response = ByteArray(maxResponseBytes)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                if (packet.length >= 12) {
                    return response.copyOfRange(0, packet.length)
                }
            } catch (_: Exception) {
                // Try the next resolver.
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        return null
    }
}

/**
 * DNS-over-TCP sent through an [OutboundConnector]. On the P2 client the
 * connector is the authenticated TLS gateway, so the query and the answer
 * travel inside the same authenticated tunnel as application traffic. The
 * destination is always one of the configured public resolvers; the gateway
 * still applies its destination policy.
 */
class SocksDnsResolver(
    private val connector: OutboundConnector,
    private val upstreams: List<InetSocketAddress> = listOf(
        InetSocketAddress("1.1.1.1", 53),
        InetSocketAddress("9.9.9.9", 53),
    ),
    private val timeoutMillis: Int = 5_000,
    private val maxResponseBytes: Int = 4_096,
    private val ipv4Only: Boolean = false,
) : DnsResolver {
    override fun resolve(query: ByteArray): ByteArray? {
        if (query.size < 12 || query.size > 65_535) return null
        if (ipv4Only) {
            DnsQueryFilter.emptyIpv6Answer(query)?.let { return it }
        }
        for (upstream in upstreams) {
            var socket: Socket? = null
            try {
                socket = connector.connect(upstream.hostString, upstream.port)
                socket.soTimeout = timeoutMillis
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                output.writeShort(query.size)
                output.write(query)
                output.flush()

                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val length = input.readUnsignedShort()
                if (length < 12 || length > maxResponseBytes) continue
                val response = ByteArray(length)
                input.readFully(response)
                return response
            } catch (_: Exception) {
                // Try the next resolver.
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        return null
    }
}
