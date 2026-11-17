package com.openmesh.gateway.socks5

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Socks5 {
    const val VERSION: Int = 0x05
    const val AUTH_VERSION: Int = 0x01

    const val METHOD_NO_AUTH: Int = 0x00
    const val METHOD_USER_PASSWORD: Int = 0x02
    const val METHOD_NONE_ACCEPTABLE: Int = 0xFF

    const val COMMAND_CONNECT: Int = 0x01
    const val COMMAND_BIND: Int = 0x02
    const val COMMAND_UDP_ASSOCIATE: Int = 0x03

    const val REPLY_SUCCESS: Int = 0x00
    const val REPLY_GENERAL_FAILURE: Int = 0x01
    const val REPLY_NOT_ALLOWED: Int = 0x02
    const val REPLY_NETWORK_UNREACHABLE: Int = 0x03
    const val REPLY_HOST_UNREACHABLE: Int = 0x04
    const val REPLY_CONNECTION_REFUSED: Int = 0x05
    const val REPLY_TTL_EXPIRED: Int = 0x06
    const val REPLY_COMMAND_NOT_SUPPORTED: Int = 0x07
    const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED: Int = 0x08

    const val ATYP_IPV4: Int = 0x01
    const val ATYP_DOMAIN: Int = 0x03
    const val ATYP_IPV6: Int = 0x04
}

data class Socks5Credentials(val username: String, val password: String) {
    init {
        require(username.toByteArray(Charsets.UTF_8).size in 1..255) {
            "SOCKS5 username must be 1..255 bytes"
        }
        require(password.toByteArray(Charsets.UTF_8).size in 1..255) {
            "SOCKS5 password must be 1..255 bytes"
        }
    }
}

class Socks5Exception(
    val replyCode: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A parsed SOCKS5 request. */
data class Socks5Request(
    val command: Int,
    val addressType: Int,
    val host: String,
    val port: Int,
    val rawAddress: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Socks5Request) return false
        return command == other.command &&
            addressType == other.addressType &&
            host == other.host &&
            port == other.port &&
            rawAddress.contentEquals(other.rawAddress)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + addressType
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + rawAddress.contentHashCode()
        return result
    }
}

internal object Socks5Io {
    fun readFully(input: InputStream, size: Int): ByteArray {
        require(size >= 0)
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) throw EOFException("SOCKS5 peer closed during handshake")
            if (n == 0) continue
            read += n
        }
        return out
    }

    fun readU8(input: InputStream): Int {
        val v = input.read()
        if (v < 0) throw EOFException("SOCKS5 peer closed during handshake")
        return v and 0xFF
    }

    fun writeU8(output: OutputStream, value: Int) {
        output.write(value and 0xFF)
    }

    fun writeAll(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Constant-time comparison for the tiny SOCKS5 credentials. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
