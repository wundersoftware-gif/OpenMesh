package com.openmesh.gateway.socks5

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the restricted SOCKS5 server over real loopback
 * sockets. The lab allowlist is used only to reach the local echo server;
 * production policy is exercised separately in [DestinationPolicyTest].
 */
class Socks5ServerTest {

    private class EchoServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread({
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        output.flush()
                    }
                }
            } catch (e: Throwable) {
                if (!server.isClosed) failure.set(e)
            }
        }, "openmesh-test-echo").apply {
            isDaemon = true
            start()
        }

        val port: Int get() = server.localPort

        override fun close() {
            try {
                server.close()
            } catch (_: Exception) {
                // ignore
            }
            thread.join(2_000)
            failure.get()?.let { throw AssertionError("echo server failed", it) }
        }
    }

    private fun credentials(user: String, pass: String) = Socks5Credentials(user, pass)

    @Test
    fun authenticatedConnectRoundTripsPayload() {
        EchoServer().use { echo ->
            val policy = DestinationPolicy(labAllowlist = setOf("127.0.0.1:${echo.port}"))
            val server = Socks5Server(
                config = Socks5Server.Config(
                    credentials = credentials("node", "s3cret"),
                    allowUdpDns = false,
                ),
                connector = DirectSocketConnector(policy),
            ).start()
            try {
                Socket().use { client ->
                    client.connect(server.boundAddress, 5_000)
                    Socks5Client.handshake(
                        client,
                        "127.0.0.1",
                        echo.port,
                        credentials("node", "s3cret"),
                    )
                    val payload = "hola openmesh".toByteArray()
                    client.getOutputStream().apply {
                        write(payload)
                        flush()
                    }
                    val echoed = readFully(client.getInputStream(), payload.size)
                    assertArrayEquals(payload, echoed)
                }
                // The pipe threads record byte counters when both directions
                // have finished. `closed` is incremented before the byte
                // counters, so wait on the non-zero byte count itself.
                val deadline = System.currentTimeMillis() + 5_000
                while (server.metrics.snapshot().let {
                        it.closed == 0L ||
                            it.bytesToRemote == 0L ||
                            it.bytesFromRemote == 0L
                    } && System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(20)
                }
                val snapshot = server.metrics.snapshot()
                assertEquals(1, snapshot.accepted)
                assertEquals(0, snapshot.denied)
                assertEquals(1, snapshot.closed)
                assertTrue(snapshot.bytesToRemote >= 12)
                assertTrue(snapshot.bytesFromRemote >= 12)
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun noAuthConnectWorksWhenNoCredentialsConfigured() {
        EchoServer().use { echo ->
            val policy = DestinationPolicy(labAllowlist = setOf("127.0.0.1:${echo.port}"))
            val server = Socks5Server(
                config = Socks5Server.Config(allowUdpDns = false),
                connector = DirectSocketConnector(policy),
            ).start()
            try {
                Socket().use { client ->
                    client.connect(server.boundAddress, 5_000)
                    Socks5Client.handshake(client, "127.0.0.1", echo.port)
                    val payload = byteArrayOf(1, 2, 3, 4)
                    client.getOutputStream().apply {
                        write(payload)
                        flush()
                    }
                    assertArrayEquals(payload, readFully(client.getInputStream(), payload.size))
                }
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun wrongPasswordIsRejectedAndCountedAsDenied() {
        val policy = DestinationPolicy()
        val server = Socks5Server(
            config = Socks5Server.Config(credentials = credentials("node", "right")),
            connector = DirectSocketConnector(policy),
        ).start()
        try {
            Socket().use { client ->
                client.connect(server.boundAddress, 5_000)
                assertThrows(Socks5Exception::class.java) {
                    Socks5Client.handshake(
                        client,
                        "example.com",
                        443,
                        credentials("node", "wrong"),
                    )
                }
            }
            val deadline = System.currentTimeMillis() + 3_000
            while (server.metrics.snapshot().denied == 0L &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            assertEquals(1, server.metrics.snapshot().denied)
            assertEquals(0, server.metrics.snapshot().accepted)
        } finally {
            server.close()
        }
    }

    @Test
    fun privateDestinationIsDeniedByPolicy() {
        val server = Socks5Server(
            config = Socks5Server.Config(allowUdpDns = false),
            connector = DirectSocketConnector(DestinationPolicy()),
        ).start()
        try {
            Socket().use { client ->
                client.connect(server.boundAddress, 5_000)
                val error = assertThrows(Socks5Exception::class.java) {
                    Socks5Client.handshake(client, "10.0.0.1", 80)
                }
                assertEquals(Socks5.REPLY_NOT_ALLOWED, error.replyCode)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun bindCommandIsRejected() {
        val server = Socks5Server(
            config = Socks5Server.Config(allowUdpDns = false),
            connector = DirectSocketConnector(DestinationPolicy()),
        ).start()
        try {
            Socket().use { client ->
                client.connect(server.boundAddress, 5_000)
                client.soTimeout = 5_000
                val output = client.getOutputStream()
                val input = client.getInputStream()
                output.write(byteArrayOf(Socks5.VERSION.toByte(), 1, Socks5.METHOD_NO_AUTH.toByte()))
                output.flush()
                assertEquals(Socks5.VERSION, input.read())
                assertEquals(Socks5.METHOD_NO_AUTH, input.read())
                // VER, CMD=BIND, RSV, ATYP=IPv4, 127.0.0.1, port 8080
                output.write(
                    byteArrayOf(
                        Socks5.VERSION.toByte(),
                        Socks5.COMMAND_BIND.toByte(),
                        0,
                        Socks5.ATYP_IPV4.toByte(),
                        127,
                        0,
                        0,
                        1,
                        0x1F,
                        0x90.toByte(),
                    ),
                )
                output.flush()
                assertEquals(Socks5.VERSION, input.read())
                assertEquals(Socks5.REPLY_COMMAND_NOT_SUPPORTED, input.read())
            }
        } finally {
            server.close()
        }
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) throw IllegalStateException("stream closed after $read/$size bytes")
            read += n
        }
        return out
    }

    @Suppress("unused")
    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray())
}
