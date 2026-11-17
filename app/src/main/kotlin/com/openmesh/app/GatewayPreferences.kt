package com.openmesh.app

import android.content.Context
import com.openmesh.transport.Fingerprint
import java.security.SecureRandom
import java.util.Base64

/**
 * Gateway settings stored in app-private preferences.
 *
 * The token is a bearer secret for the P2 lab. It is not world-readable, but
 * it is ordinary preference storage; moving it into the Android Keystore is a
 * documented hardening item. The fingerprint is public data and is meant to
 * be compared out of band.
 */
object GatewayPreferences {
    private const val FILE = "openmesh_gateway"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    fun port(context: Context): Int = context
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getInt(KEY_PORT, DEFAULT_PORT)

    fun savePort(context: Context, port: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PORT, port)
            .apply()
    }

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = newToken()
        prefs.edit().putString(KEY_TOKEN, generated).apply()
        return generated
    }

    fun regenerateToken(context: Context): String {
        val generated = newToken()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, generated)
            .apply()
        return generated
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    const val DEFAULT_PORT = 8443
}

/**
 * Client-side gateway configuration. It is also stored in app-private
 * preferences so a reboot does not silently point the client at a different
 * gateway.
 */
data class GatewayClientConfig(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
) {
    fun normalizedFingerprint(): String = Fingerprint.normalize(fingerprint)
}

object GatewayClientPreferences {
    private const val FILE = "openmesh_gateway_client"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_TOKEN = "token"

    fun load(context: Context): GatewayClientConfig {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val defaultHost = if (BuildConfig.DEBUG) "10.0.2.2" else ""
        return GatewayClientConfig(
            host = prefs.getString(KEY_HOST, defaultHost) ?: defaultHost,
            port = prefs.getInt(KEY_PORT, GatewayPreferences.DEFAULT_PORT),
            fingerprint = prefs.getString(KEY_FINGERPRINT, "") ?: "",
            token = prefs.getString(KEY_TOKEN, "") ?: "",
        )
    }

    fun save(context: Context, config: GatewayClientConfig) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_FINGERPRINT, config.fingerprint.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }
}

/**
 * Returns a user-facing error, or null when the configuration can be pinned.
 */
fun validateGatewayClientConfig(config: GatewayClientConfig): String? {
    if (config.host.isBlank()) return "Gateway host is required."
    if (config.port !in 1..65535) return "Gateway port must be between 1 and 65535."
    if (config.token.isBlank()) return "Gateway token is required."
    if (config.token.toByteArray(Charsets.UTF_8).size > 255) {
        return "Gateway token is too long (maximum 255 bytes)."
    }
    return try {
        config.normalizedFingerprint()
        null
    } catch (_: IllegalArgumentException) {
        "Gateway fingerprint must contain exactly 64 hexadecimal characters " +
            "(colons and spaces are allowed)."
    }
}
