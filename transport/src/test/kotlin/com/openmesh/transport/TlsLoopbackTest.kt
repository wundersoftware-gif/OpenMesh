package com.openmesh.transport

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsLoopbackTest {
    @Test
    fun `fingerprint normalisation accepts human formatting`() {
        val identity = TlsIdentity.generate("openmesh-test")
        val canonical = identity.fingerprint
        assertEquals(canonical, Fingerprint.normalize(Fingerprint.format(canonical)))
        assertEquals(64, canonical.length)
        assertTrue(canonical.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `identity survives a PKCS12 round trip`() {
        val identity = TlsIdentity.generate("openmesh-persist")
        val file = Files.createTempFile("openmesh-identity", ".p12").toFile()
        try {
            identity.save(file)
            val loaded = TlsIdentity.load(file, identity.password)
            assertEquals(identity.fingerprint, loaded.fingerprint)
            assertEquals(
                identity.certificate.subjectX500Principal,
                loaded.certificate.subjectX500Principal,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `TLS 1_3 loopback with a pinned gateway fingerprint`() {
        val identity = TlsIdentity.generate("openmesh-gateway")
        val serverSocket = TlsServerSocketFactory(identity).create()
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val serverThread = Thread {
            serverSocket.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println("hello " + reader.readLine())
            }
        }.apply { isDaemon = true; start() }

        val raw = Socket()
        raw.connect(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverSocket.localPort),
            5_000,
        )
        val client = TlsClient.connect(
            raw = raw,
            host = "openmesh-gateway",
            port = serverSocket.localPort,
            trust = PinnedFingerprintTrustManager(identity.fingerprint),
            timeoutMillis = 5_000,
        )
        try {
            assertEquals("TLSv1.3", client.session.protocol)
            val writer = PrintWriter(client.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            writer.println("openmesh")
            assertEquals("hello openmesh", reader.readLine())
        } finally {
            client.close()
            serverSocket.close()
            serverThread.join(5_000)
        }
    }

    @Test
    fun `a different pinned fingerprint is rejected`() {
        val identity = TlsIdentity.generate("openmesh-gateway")
        val other = TlsIdentity.generate("openmesh-other")
        val serverSocket = TlsServerSocketFactory(identity).create()
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    // Perform the handshake so the client actually receives
                    // the certificate and rejects it. Closing immediately
                    // would produce a connection reset instead.
                    try {
                        (socket as javax.net.ssl.SSLSocket).startHandshake()
                    } catch (_: Exception) {
                        // The client is expected to abort the handshake.
                    }
                }
            } catch (_: Exception) {
                // The client is expected to abort the handshake.
            }
        }.apply { isDaemon = true; start() }

        val raw = Socket()
        raw.connect(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverSocket.localPort),
            5_000,
        )
        var failed = false
        try {
            TlsClient.connect(
                raw = raw,
                host = "openmesh-gateway",
                port = serverSocket.localPort,
                trust = PinnedFingerprintTrustManager(other.fingerprint),
                timeoutMillis = 5_000,
            ).close()
        } catch (_: java.io.IOException) {
            // JSSE reports a pin mismatch as an SSLHandshakeException; a
            // peer that closes during the alert can surface as a reset.
            // Either way the connection must not succeed.
            failed = true
        } finally {
            serverSocket.close()
            serverThread.join(5_000)
        }
        assertTrue("a mismatched pin must abort the TLS handshake", failed)
        assertNotEquals(identity.fingerprint, other.fingerprint)
    }

    @Test
    fun `server socket is TLS 1_3 only`() {
        val identity = TlsIdentity.generate("openmesh-gateway")
        val server = TlsServerSocketFactory(identity).create()
        try {
            assertEquals(listOf("TLSv1.3"), server.enabledProtocols.toList())
        } finally {
            server.close()
        }
    }
}
