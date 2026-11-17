package com.openmesh.gateway.socks5

import com.openmesh.gateway.GatewayAudit
import com.openmesh.gateway.GatewayMetrics
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SOCKS5 UDP ASSOCIATE relay, restricted to DNS.
 *
 * The tunnel engine (tun2socks) forwards application UDP into the local SOCKS
 * frontend. OpenMesh's first version supports no generic UDP: only datagrams
 * addressed to port 53 are accepted, and they are forwarded to a configured
 * public resolver. Everything else is dropped and counted. There is no
 * "pretend UDP works" path.
 */
internal class UdpDnsRelay(
    private val config: Socks5Server.Config,
    private val audit: GatewayAudit,
    private val metrics: GatewayMetrics,
    private val protector: SocketProtector,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val socket = DatagramSocket(null)
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "openmesh-udp-dns").apply { isDaemon = true }
    }
    private val permits = Semaphore(16)
    private val dropped = AtomicLong()
    private var thread: Thread? = null

    fun start(): InetSocketAddress {
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        protector.protect(socket)
        running.set(true)
        thread = Thread({ receiveLoop() }, "openmesh-udp-dns-rx").apply {
            isDaemon = true
            start()
        }
        return InetSocketAddress(InetAddress.getByName("127.0.0.1"), socket.localPort)
    }

    private fun receiveLoop() {
        val buffer = ByteArray(config.maxUdpDnsPayload + 512)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (!packet.address.isLoopbackAddress) {
                    dropped.incrementAndGet()
                    continue
                }
                val copy = packet.data.copyOfRange(0, packet.length)
                if (!permits.tryAcquire()) {
                    dropped.incrementAndGet()
                    continue
                }
                workers.execute {
                    try {
                        handle(copy, packet.address, packet.port)
                    } finally {
                        permits.release()
                    }
                }
            } catch (e: SocketTimeoutException) {
                // loop timeout is not used; keep for completeness
            } catch (e: Exception) {
                if (running.get()) {
                    // A single malformed packet must never kill the relay.
                }
            }
        }
    }

    private fun handle(raw: ByteArray, clientAddress: InetAddress, clientPort: Int) {
        val request = parse(raw) ?: run {
            dropped.incrementAndGet()
            return
        }
        if (request.port != 53) {
            dropped.incrementAndGet()
            return
        }
        val response = queryUpstream(request.payload) ?: return
        val header = buildHeader(request)
        val out = header + response
        if (out.size > 65_535) return
        try {
            socket.send(DatagramPacket(out, out.size, clientAddress, clientPort))
            metrics.udpDns()
            audit.onUdpDnsForwarded(request.payload.size, response.size)
        } catch (_: Exception) {
            dropped.incrementAndGet()
        }
    }

    private fun queryUpstream(query: ByteArray): ByteArray? {
        for (upstream in config.dnsUpstreams) {
            val upstreamSocket = DatagramSocket()
            try {
                protector.protect(upstreamSocket)
                upstreamSocket.soTimeout = 5_000
                upstreamSocket.connect(upstream)
                upstreamSocket.send(DatagramPacket(query, query.size))
                val response = ByteArray(config.maxUdpDnsPayload)
                val packet = DatagramPacket(response, response.size)
                upstreamSocket.receive(packet)
                if (packet.length >= 12) {
                    return response.copyOfRange(0, packet.length)
                }
            } catch (_: Exception) {
                // Try the next resolver.
            } finally {
                try {
                    upstreamSocket.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
        return null
    }

    private fun buildHeader(request: UdpRequest): ByteArray {
        val prefix = byteArrayOf(0, 0, 0, request.atyp.toByte())
        val port = byteArrayOf(
            ((request.port ushr 8) and 0xFF).toByte(),
            (request.port and 0xFF).toByte(),
        )
        return prefix + request.rawAddress + port
    }

    private fun parse(raw: ByteArray): UdpRequest? {
        if (raw.size < 4 + 1 + 1 + 2 + 12) return null
        if (raw[0].toInt() != 0 || raw[1].toInt() != 0) return null
        val fragment = raw[2].toInt() and 0xFF
        if (fragment != 0) return null // fragmentation is not supported
        val atyp = raw[3].toInt() and 0xFF
        var offset = 4
        val addressLength = when (atyp) {
            Socks5.ATYP_IPV4 -> 4
            Socks5.ATYP_IPV6 -> 16
            Socks5.ATYP_DOMAIN -> {
                if (offset >= raw.size) return null
                val length = raw[offset].toInt() and 0xFF
                offset += 1
                length
            }
            else -> return null
        }
        if (addressLength == 0) return null
        if (offset + addressLength + 2 > raw.size) return null
        val rawAddress = raw.copyOfRange(offset, offset + addressLength)
        offset += addressLength
        val port = ((raw[offset].toInt() and 0xFF) shl 8) or
            (raw[offset + 1].toInt() and 0xFF)
        offset += 2
        if (port != 53) return null
        val payload = raw.copyOfRange(offset, raw.size)
        if (payload.size < 12 || payload.size > config.maxUdpDnsPayload) return null
        return UdpRequest(atyp, rawAddress, port, payload)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try {
            socket.close()
        } catch (_: Exception) {
            // ignore
        }
        thread?.interrupt()
        workers.shutdownNow()
    }

    private data class UdpRequest(
        val atyp: Int,
        val rawAddress: ByteArray,
        val port: Int,
        val payload: ByteArray,
    )
}
