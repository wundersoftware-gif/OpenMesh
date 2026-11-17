package com.openmesh.gateway

import com.openmesh.gateway.client.GatewayEndpoint
import com.openmesh.gateway.client.RemoteGatewayConnector
import com.openmesh.gateway.socks5.DestinationPolicy
import com.openmesh.gateway.socks5.Socks5
import com.openmesh.gateway.socks5.Socks5Credentials
import com.openmesh.gateway.socks5.Socks5Exception
import com.openmesh.gateway.socks5.SocksDnsResolver
import com.openmesh.transport.TlsIdentity
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GatewayTlsTest {
    private val identity = TlsIdentity.generate("openmesh-gateway")
    private val credentials = Socks5Credentials("openmesh", "test-token-1234567890")

    @Test
    fun `a client reaches a destination through pinned TLS and auth`() {
        withEchoServer { echoPort ->
            withGateway(policy = DestinationPolicy(allowPrivateDestinations = true)) { gateway ->
                val connector = RemoteGatewayConnector(endpoint(gateway), connectTimeoutMillis = 5_000)
                val socket = connector.connect("127.0.0.1", echoPort)
                try {
                    assertEquals("TLSv1.3", (socket as SSLSocket).session.protocol)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    writer.println("ping")
                    assertEquals("echo:ping", reader.readLine())
                } finally {
                    socket.close()
                }
            }
        }
    }

    @Test
    fun `a wrong gateway fingerprint is rejected`() {
        withGateway(policy = DestinationPolicy(allowPrivateDestinations = true)) { gateway ->
            val wrong = endpoint(gateway).copy(fingerprint = identity.fingerprint.reversed())
            val connector = RemoteGatewayConnector(wrong, connectTimeoutMillis = 5_000)
            try {
                connector.openAuthenticated().close()
                fail("a mismatched pin must not authenticate")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    @Test
    fun `a wrong token is rejected`() {
        withGateway(policy = DestinationPolicy(allowPrivateDestinations = true)) { gateway ->
            val wrong = endpoint(gateway).copy(token = "not-the-gateway-token")
            val connector = RemoteGatewayConnector(wrong, connectTimeoutMillis = 5_000)
            try {
                connector.openAuthenticated().close()
                fail("an invalid RFC 1929 secret must not authenticate")
            } catch (_: Exception) {
                // expected
            }
        }
    }

    @Test
    fun `the gateway refuses a private destination by default`() {
        withGateway(policy = DestinationPolicy()) { gateway ->
            val connector = RemoteGatewayConnector(endpoint(gateway), connectTimeoutMillis = 5_000)
            try {
                connector.connect("127.0.0.1", 9).close()
                fail("loopback must be denied by the default policy")
            } catch (e: Socks5Exception) {
                assertEquals(Socks5.REPLY_NOT_ALLOWED, e.replyCode)
            }
        }
    }

    @Test
    fun `DNS over TCP travels through the authenticated gateway`() {
        val query = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val response = query.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }
        withTcpServer(
            handle = { socket ->
                val input = DataInputStream(socket.getInputStream())
                val length = input.readUnsignedShort()
                val received = ByteArray(length)
                input.readFully(received)
                val output = DataOutputStream(socket.getOutputStream())
                output.writeShort(response.size)
                output.write(response)
                output.flush()
            },
            block = { dnsPort ->
                withGateway(policy = DestinationPolicy(allowPrivateDestinations = true)) { gateway ->
                    val connector =
                        RemoteGatewayConnector(endpoint(gateway), connectTimeoutMillis = 5_000)
                    val resolver = SocksDnsResolver(
                        connector = connector,
                        upstreams = listOf(InetSocketAddress("127.0.0.1", dnsPort)),
                        timeoutMillis = 5_000,
                    )
                    val answer = resolver.resolve(query)
                    assertArrayEquals(response, answer)
                }
            },
        )
    }

    private fun endpoint(gateway: GatewayServer): GatewayEndpoint = GatewayEndpoint(
        host = "127.0.0.1",
        port = gateway.boundPort,
        fingerprint = identity.fingerprint,
        token = credentials.password,
        username = credentials.username,
    )

    private fun withGateway(
        policy: DestinationPolicy,
        block: (GatewayServer) -> Unit,
    ) {
        val gateway = GatewayServer(
            identity = identity,
            credentials = credentials,
            listenAddress = InetAddress.getByName("127.0.0.1"),
            port = 0,
            policy = policy,
            resolver = { host -> InetAddress.getAllByName(host).toList() },
        ).start()
        try {
            block(gateway)
        } finally {
            gateway.close()
        }
    }

    private fun withEchoServer(block: (Int) -> Unit) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val line = reader.readLine() ?: return@use
                    writer.println("echo:$line")
                }
            } catch (_: Exception) {
                // test teardown
            }
        }.apply { isDaemon = true; start() }
        try {
            block(server.localPort)
        } finally {
            server.close()
            worker.join(TimeUnit.SECONDS.toMillis(5))
        }
    }

    private fun withTcpServer(
        handle: (java.net.Socket) -> Unit,
        block: (Int) -> Unit,
    ) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                server.accept().use(handle)
            } catch (_: Exception) {
                // test teardown
            }
        }.apply { isDaemon = true; start() }
        try {
            block(server.localPort)
        } finally {
            server.close()
            worker.join(TimeUnit.SECONDS.toMillis(5))
        }
    }
}
