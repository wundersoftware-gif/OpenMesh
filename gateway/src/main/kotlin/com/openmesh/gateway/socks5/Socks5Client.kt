package com.openmesh.gateway.socks5

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

/**
 * Minimal SOCKS5 client used by the device-side frontend when it talks to an
 * OpenMesh gateway over an end-to-end TLS stream. It supports CONNECT only;
 * UDP and BIND are intentionally absent.
 */
object Socks5Client {
    fun handshake(
        socket: Socket,
        host: String,
        port: Int,
        credentials: Socks5Credentials? = null,
    ) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        negotiate(input, output, credentials)
        sendConnect(input, output, host, port)
    }

    private fun negotiate(
        input: InputStream,
        output: OutputStream,
        credentials: Socks5Credentials?,
    ) {
        val methods = if (credentials != null) {
            byteArrayOf(Socks5.METHOD_USER_PASSWORD.toByte())
        } else {
            byteArrayOf(Socks5.METHOD_NO_AUTH.toByte())
        }
        Socks5Io.writeAll(
            output,
            byteArrayOf(Socks5.VERSION.toByte(), methods.size.toByte()) + methods,
        )
        val version = Socks5Io.readU8(input)
        if (version != Socks5.VERSION) {
            throw Socks5Exception(Socks5.REPLY_GENERAL_FAILURE, "unexpected SOCKS version $version")
        }
        val method = Socks5Io.readU8(input)
        when (method) {
            Socks5.METHOD_NO_AUTH -> {
                if (credentials != null) {
                    throw Socks5Exception(
                        Socks5.REPLY_GENERAL_FAILURE,
                        "gateway selected no-auth but credentials were required",
                    )
                }
            }
            Socks5.METHOD_USER_PASSWORD -> {
                if (credentials == null) {
                    throw Socks5Exception(
                        Socks5.REPLY_GENERAL_FAILURE,
                        "gateway requires credentials",
                    )
                }
                authenticate(input, output, credentials)
            }
            Socks5.METHOD_NONE_ACCEPTABLE -> throw Socks5Exception(
                Socks5.REPLY_GENERAL_FAILURE,
                "gateway rejected all authentication methods",
            )
            else -> throw Socks5Exception(
                Socks5.REPLY_GENERAL_FAILURE,
                "gateway selected unsupported method $method",
            )
        }
    }

    private fun authenticate(
        input: InputStream,
        output: OutputStream,
        credentials: Socks5Credentials,
    ) {
        val user = credentials.username.toByteArray(Charsets.UTF_8)
        val pass = credentials.password.toByteArray(Charsets.UTF_8)
        val request = byteArrayOf(Socks5.AUTH_VERSION.toByte(), user.size.toByte()) +
            user +
            byteArrayOf(pass.size.toByte()) +
            pass
        Socks5Io.writeAll(output, request)
        val version = Socks5Io.readU8(input)
        val status = Socks5Io.readU8(input)
        if (version != Socks5.AUTH_VERSION || status != 0) {
            throw Socks5Exception(Socks5.REPLY_NOT_ALLOWED, "SOCKS5 authentication failed")
        }
    }

    private fun sendConnect(
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
    ) {
        if (port !in 1..65535) throw IllegalArgumentException("invalid port $port")
        val address = encodeAddress(host)
        val request = byteArrayOf(
            Socks5.VERSION.toByte(),
            Socks5.COMMAND_CONNECT.toByte(),
            0,
        ) + address + byteArrayOf(
            ((port ushr 8) and 0xFF).toByte(),
            (port and 0xFF).toByte(),
        )
        Socks5Io.writeAll(output, request)
        val version = Socks5Io.readU8(input)
        val reply = Socks5Io.readU8(input)
        Socks5Io.readU8(input) // reserved
        if (version != Socks5.VERSION) {
            throw Socks5Exception(reply, "bad SOCKS5 reply version $version")
        }
        if (reply != Socks5.REPLY_SUCCESS) {
            throw Socks5Exception(reply, "SOCKS5 CONNECT failed with reply $reply")
        }
        // Consume the bound address so that the stream is aligned for the
        // application payload.
        val atyp = Socks5Io.readU8(input)
        when (atyp) {
            Socks5.ATYP_IPV4 -> Socks5Io.readFully(input, 4)
            Socks5.ATYP_IPV6 -> Socks5Io.readFully(input, 16)
            Socks5.ATYP_DOMAIN -> {
                val length = Socks5Io.readU8(input)
                Socks5Io.readFully(input, length)
            }
            else -> throw Socks5Exception(reply, "bad SOCKS5 bound address type $atyp")
        }
        Socks5Io.readFully(input, 2) // bound port
    }

    private fun encodeAddress(host: String): ByteArray {
        val literal = parseLiteral(host)
        if (literal != null) {
            return if (literal is java.net.Inet4Address) {
                byteArrayOf(Socks5.ATYP_IPV4.toByte()) + literal.address
            } else {
                byteArrayOf(Socks5.ATYP_IPV6.toByte()) + literal.address
            }
        }
        val bytes = host.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 255) {
            throw IllegalArgumentException("invalid domain name")
        }
        return byteArrayOf(Socks5.ATYP_DOMAIN.toByte(), bytes.size.toByte()) + bytes
    }

    private fun parseLiteral(host: String): InetAddress? {
        // Do not call getByName here: it would resolve a hostname. Only accept
        // strings that are already numeric addresses.
        return try {
            if (host.contains(':')) {
                InetAddress.getByName(host)
            } else {
                val parts = host.split('.')
                if (parts.size != 4) return null
                if (parts.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 }) {
                    return null
                }
                InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            }
        } catch (_: Exception) {
            null
        }
    }
}
