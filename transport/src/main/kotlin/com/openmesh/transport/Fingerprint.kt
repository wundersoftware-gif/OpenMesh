package com.openmesh.transport

import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.Locale

/**
 * SHA-256 certificate fingerprints are the OpenMesh node identity in the P2
 * lab. They are rendered as colon-separated hex groups for humans and
 * normalised to lower-case hex for comparison.
 */
object Fingerprint {
    fun of(certificate: Certificate): String = of(certificate.encoded)

    fun of(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    fun normalize(value: String): String {
        val cleaned = value
            .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            .lowercase(Locale.US)
        require(cleaned.length == 64) {
            "certificate fingerprint must contain exactly 64 hex characters"
        }
        return cleaned
    }

    fun format(value: String): String =
        normalize(value).chunked(4).joinToString(":")

    private const val HEX = "0123456789abcdef"
}
