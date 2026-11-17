package com.openmesh.transport

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS 1.3 contexts. The P2 lab authenticates the gateway by pinning the
 * SHA-256 fingerprint of its self-signed certificate. Hostname verification is
 * deliberately disabled because the certificate is not issued by a public CA;
 * the pinned fingerprint is the authentication check.
 */
object TlsContexts {
    private const val PROTOCOL = "TLSv1.3"

    fun server(identity: TlsIdentity): SSLContext {
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm(),
        )
        keyManagerFactory.init(identity.keyStore, identity.password)
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, SecureRandom())
        }
    }

    fun client(trust: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trust), SecureRandom())
        }

    fun enableTls13(socket: SSLSocket) {
        check(socket.supportedProtocols.contains(PROTOCOL)) {
            "this device does not support TLS 1.3; OpenMesh requires it"
        }
        socket.sslParameters = socket.sslParameters.apply {
            protocols = arrayOf(PROTOCOL)
            endpointIdentificationAlgorithm = null
        }
    }

    fun enableTls13(socket: SSLServerSocket) {
        check(socket.supportedProtocols.contains(PROTOCOL)) {
            "this device does not support TLS 1.3; OpenMesh requires it"
        }
        socket.enabledProtocols = arrayOf(PROTOCOL)
    }
}

class TlsServerSocketFactory(identity: TlsIdentity) {
    private val context = TlsContexts.server(identity)

    fun create(): SSLServerSocket {
        val socket = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.needClientAuth = false
        socket.wantClientAuth = false
        TlsContexts.enableTls13(socket)
        return socket
    }
}

object TlsClient {
    fun connect(
        raw: Socket,
        host: String,
        port: Int,
        trust: X509ExtendedTrustManager,
        timeoutMillis: Int = 10_000,
    ): SSLSocket = connect(
        raw = raw,
        host = host,
        port = port,
        context = TlsContexts.client(trust),
        timeoutMillis = timeoutMillis,
    )

    fun connect(
        raw: Socket,
        host: String,
        port: Int,
        context: SSLContext,
        timeoutMillis: Int = 10_000,
    ): SSLSocket {
        val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMillis
            TlsContexts.enableTls13(socket)
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
                // best effort
            }
            throw e
        }
    }
}

/**
 * Accepts exactly one peer certificate fingerprint. Any other chain is a hard
 * authentication failure; there is no fallback to the system trust store.
 */
class PinnedFingerprintTrustManager(expectedFingerprint: String) : X509ExtendedTrustManager() {
    private val expected = Fingerprint.normalize(expectedFingerprint)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted by this endpoint")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        checkChain(chain)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        throw CertificateException("client certificates are not accepted by this endpoint")
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = checkChain(chain)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        throw CertificateException("client certificates are not accepted by this endpoint")
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = checkChain(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun checkChain(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("peer presented no certificate")
        val actual = Fingerprint.of(leaf)
        if (actual != expected) {
            throw CertificateException(
                "gateway certificate fingerprint mismatch; expected $expected, got $actual",
            )
        }
    }
}
