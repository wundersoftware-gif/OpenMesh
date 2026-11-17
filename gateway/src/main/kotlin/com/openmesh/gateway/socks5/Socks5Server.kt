package com.openmesh.gateway.socks5

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.openmesh.gateway.GatewayAudit
import com.openmesh.gateway.GatewayMetrics
import com.openmesh.gateway.NoopGatewayAudit

/**
 * A restricted SOCKS5 server.
 *
 * - CONNECT only for application streams; BIND is rejected.
 * - UDP ASSOCIATE exists solely for DNS (port 53), forwarded to configured
 *   public resolvers. Generic UDP is not provided.
 * - Authentication is either none (loopback lab frontend) or RFC 1929
 *   username/password with a per-launch random secret.
 * - Every destination is validated before connecting.
 * - Every connection has handshake, idle and size limits.
 */
class Socks5Server(
    private val config: Config,
    private val connector: OutboundConnector,
    private val audit: GatewayAudit = NoopGatewayAudit,
    val metrics: GatewayMetrics = GatewayMetrics(),
    private val protector: SocketProtector = NoopSocketProtector,
) : AutoCloseable {

    data class Config(
        val bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
        val port: Int = 0,
        val credentials: Socks5Credentials? = null,
        val maxConnections: Int = 64,
        val handshakeTimeoutMillis: Int = 15_000,
        val idleTimeoutMillis: Int = 300_000,
        val connectTimeoutMillis: Int = 10_000,
        val allowUdpDns: Boolean = true,
        val dnsUpstreams: List<InetSocketAddress> = listOf(
            InetSocketAddress("1.1.1.1", 53),
            InetSocketAddress("9.9.9.9", 53),
        ),
        val maxUdpDnsPayload: Int = 4096,
    ) {
        init {
            require(port in 0..65535)
            require(maxConnections in 1..1024)
        }
    }

    private val running = AtomicBoolean(false)
    private val sessionIds = AtomicLong(1000)
    private val permits = Semaphore(config.maxConnections)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "openmesh-socks5").apply { isDaemon = true }
    }
    private val executor = Executors.newCachedThreadPool(threadFactory)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val boundAddress: InetSocketAddress
        get() = serverSocket?.localSocketAddress as? InetSocketAddress
            ?: error("server not started")

    val boundPort: Int
        get() = boundAddress.port

    fun start(): Socks5Server {
        check(!running.get()) { "already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        protector.protect(socket)
        socket.bind(InetSocketAddress(config.bindAddress, config.port))
        serverSocket = socket
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket) }, "openmesh-socks5-accept").apply {
            isDaemon = true
            start()
        }
        return this
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val client = server.accept()
                if (!permits.tryAcquire()) {
                    metrics.denied()
                    safeClose(client)
                    continue
                }
                activeSockets.add(client)
                executor.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeSockets.remove(client)
                        safeClose(client)
                        permits.release()
                    }
                }
            } catch (e: SocketException) {
                if (running.get()) {
                    // Unexpected accept failure. Keep the loop alive with a
                    // short pause so a transient error cannot spin.
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = config.handshakeTimeoutMillis
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val sessionId = sessionIds.incrementAndGet()
        var remote: Socket? = null
        var udpRelay: UdpDnsRelay? = null
        var request: Socks5Request? = null
        try {
            val method = negotiate(input, output)
            if (method == Socks5.METHOD_USER_PASSWORD) {
                if (!authenticate(input, output)) {
                    metrics.denied()
                    return
                }
            }
            request = readRequest(input)
            when (request.command) {
                Socks5.COMMAND_CONNECT -> {
                    try {
                        remote = connector.connect(request.host, request.port)
                    } catch (e: Exception) {
                        val code = replyCodeFor(e)
                        sendReply(output, code)
                        metrics.denied()
                        audit.onConnectionDenied(
                            request.host,
                            request.port,
                            e.message ?: e.javaClass.simpleName,
                        )
                        return
                    }
                    sendReply(output, Socks5.REPLY_SUCCESS)
                    metrics.accepted()
                    audit.onConnectionAccepted(sessionId, request.host, request.port)
                    client.soTimeout = config.idleTimeoutMillis
                    remote.soTimeout = config.idleTimeoutMillis
                    val (up, down) = pipe(client, remote)
                    metrics.closed(up, down)
                    audit.onConnectionClosed(sessionId, request.host, request.port, up, down)
                }
                Socks5.COMMAND_UDP_ASSOCIATE -> {
                    if (!config.allowUdpDns) {
                        sendReply(output, Socks5.REPLY_COMMAND_NOT_SUPPORTED)
                        return
                    }
                    udpRelay = UdpDnsRelay(
                        config = config,
                        audit = audit,
                        metrics = metrics,
                        protector = protector,
                    )
                    val relayAddress = udpRelay.start()
                    sendReply(
                        output,
                        Socks5.REPLY_SUCCESS,
                        relayAddress.address,
                        relayAddress.port,
                    )
                    // The association lives as long as this TCP control
                    // connection. Wait for the client to close it.
                    client.soTimeout = 0
                    val sink = ByteArray(64)
                    while (running.get()) {
                        val n = input.read(sink)
                        if (n < 0) break
                    }
                }
                else -> {
                    sendReply(output, Socks5.REPLY_COMMAND_NOT_SUPPORTED)
                }
            }
        } catch (e: SocketTimeoutException) {
            // Handshake or idle timeout; no reply is possible or needed.
        } catch (e: Socks5Exception) {
            try {
                sendReply(output, e.replyCode)
            } catch (_: Exception) {
                // client vanished
            }
        } catch (e: Exception) {
            try {
                sendReply(output, Socks5.REPLY_GENERAL_FAILURE)
            } catch (_: Exception) {
                // client vanished
            }
        } finally {
            udpRelay?.close()
            if (remote != null) {
                safeClose(remote)
            }
            safeClose(client)
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream): Int {
        val version = Socks5Io.readU8(input)
        if (version != Socks5.VERSION) {
            throw Socks5Exception(Socks5.REPLY_GENERAL_FAILURE, "bad SOCKS version")
        }
        val count = Socks5Io.readU8(input)
        if (count == 0) {
            throw Socks5Exception(Socks5.REPLY_GENERAL_FAILURE, "no auth methods")
        }
        val methods = Socks5Io.readFully(input, count)
        val wantUserPass = config.credentials != null
        val selected = when {
            wantUserPass && methods.contains(Socks5.METHOD_USER_PASSWORD.toByte()) ->
                Socks5.METHOD_USER_PASSWORD
            !wantUserPass && methods.contains(Socks5.METHOD_NO_AUTH.toByte()) ->
                Socks5.METHOD_NO_AUTH
            else -> Socks5.METHOD_NONE_ACCEPTABLE
        }
        Socks5Io.writeAll(
            output,
            byteArrayOf(Socks5.VERSION.toByte(), selected.toByte()),
        )
        if (selected == Socks5.METHOD_NONE_ACCEPTABLE) {
            throw Socks5Exception(Socks5.REPLY_NOT_ALLOWED, "no acceptable auth method")
        }
        return selected
    }

    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val expected = config.credentials ?: return true
        val version = Socks5Io.readU8(input)
        if (version != Socks5.AUTH_VERSION) return false
        val userLength = Socks5Io.readU8(input)
        if (userLength == 0) return false
        val user = Socks5Io.readFully(input, userLength)
        val passLength = Socks5Io.readU8(input)
        if (passLength == 0) return false
        val pass = Socks5Io.readFully(input, passLength)
        val ok = Socks5Io.constantTimeEquals(
            user,
            expected.username.toByteArray(Charsets.UTF_8),
        ) && Socks5Io.constantTimeEquals(
            pass,
            expected.password.toByteArray(Charsets.UTF_8),
        )
        Socks5Io.writeAll(
            output,
            byteArrayOf(
                Socks5.AUTH_VERSION.toByte(),
                if (ok) 0 else 1,
            ),
        )
        return ok
    }

    private fun readRequest(input: InputStream): Socks5Request {
        val version = Socks5Io.readU8(input)
        if (version != Socks5.VERSION) {
            throw Socks5Exception(Socks5.REPLY_GENERAL_FAILURE, "bad request version")
        }
        val command = Socks5Io.readU8(input)
        val reserved = Socks5Io.readU8(input)
        if (reserved != 0) {
            throw Socks5Exception(Socks5.REPLY_GENERAL_FAILURE, "bad reserved byte")
        }
        val atyp = Socks5Io.readU8(input)
        val rawAddress: ByteArray
        val host: String
        when (atyp) {
            Socks5.ATYP_IPV4 -> {
                rawAddress = Socks5Io.readFully(input, 4)
                host = InetAddress.getByAddress(rawAddress).hostAddress
            }
            Socks5.ATYP_IPV6 -> {
                rawAddress = Socks5Io.readFully(input, 16)
                host = InetAddress.getByAddress(rawAddress).hostAddress
            }
            Socks5.ATYP_DOMAIN -> {
                val length = Socks5Io.readU8(input)
                if (length == 0) {
                    throw Socks5Exception(
                        Socks5.REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
                        "empty domain",
                    )
                }
                rawAddress = Socks5Io.readFully(input, length)
                if (rawAddress.any { (it.toInt() and 0xFF) > 0x7F }) {
                    throw Socks5Exception(
                        Socks5.REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
                        "non-ASCII domain",
                    )
                }
                host = String(rawAddress, Charsets.US_ASCII)
                if (host.any { it == '\u0000' || it.isWhitespace() }) {
                    throw Socks5Exception(
                        Socks5.REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
                        "invalid domain",
                    )
                }
            }
            else -> throw Socks5Exception(
                Socks5.REPLY_ADDRESS_TYPE_NOT_SUPPORTED,
                "unsupported address type $atyp",
            )
        }
        val port = (Socks5Io.readU8(input) shl 8) or Socks5Io.readU8(input)
        if (port == 0) {
            throw Socks5Exception(Socks5.REPLY_NOT_ALLOWED, "port 0")
        }
        return Socks5Request(command, atyp, host, port, rawAddress)
    }

    private fun sendReply(
        output: OutputStream,
        replyCode: Int,
        address: InetAddress? = null,
        port: Int = 0,
    ) {
        val addr = address ?: InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        val atyp = if (addr.address.size == 4) Socks5.ATYP_IPV4 else Socks5.ATYP_IPV6
        val reply = byteArrayOf(
            Socks5.VERSION.toByte(),
            replyCode.toByte(),
            0,
            atyp.toByte(),
        ) + addr.address + byteArrayOf(
            ((port ushr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte(),
        )
        Socks5Io.writeAll(output, reply)
    }

    private fun pipe(client: Socket, remote: Socket): Pair<Long, Long> {
        val up = AtomicLong()
        val down = AtomicLong()
        val done = CountDownLatch(2)
        executor.execute {
            try {
                up.set(copyStream(client.getInputStream(), remote.getOutputStream(), remote))
            } catch (_: Exception) {
                // The other direction may still deliver data; the close below
                // unblocks it.
            } finally {
                done.countDown()
            }
        }
        executor.execute {
            try {
                down.set(copyStream(remote.getInputStream(), client.getOutputStream(), client))
            } catch (_: Exception) {
                // see above
            } finally {
                done.countDown()
            }
        }
        try {
            // Wait forever for the first direction, then give the other a
            // short grace period to flush and finish.
            while (!done.await(1, TimeUnit.SECONDS)) {
                if (!running.get()) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        safeClose(client)
        safeClose(remote)
        return up.get() to down.get()
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        other: Socket,
    ): Long {
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        try {
            while (running.get()) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, 0, n)
                output.flush()
                total += n
            }
        } finally {
            try {
                other.shutdownOutput()
            } catch (_: Exception) {
                // best effort
            }
        }
        return total
    }

    private fun replyCodeFor(e: Exception): Int = when (e) {
        is Socks5Exception -> e.replyCode
        is PolicyDeniedException -> Socks5.REPLY_NOT_ALLOWED
        is SocketTimeoutException -> Socks5.REPLY_HOST_UNREACHABLE
        is ConnectException -> Socks5.REPLY_CONNECTION_REFUSED
        is NoRouteToHostException -> Socks5.REPLY_NETWORK_UNREACHABLE
        else -> Socks5.REPLY_HOST_UNREACHABLE
    }

    private fun safeClose(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // ignore
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // ignore
        }
        acceptThread?.interrupt()
        activeSockets.forEach { safeClose(it) }
        activeSockets.clear()
        executor.shutdownNow()
    }
}
