package com.openmesh.app

import android.content.Context
import com.openmesh.transport.TlsIdentity
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The gateway's long-lived self-signed TLS 1.3 identity.
 *
 * The SHA-256 certificate fingerprint is what a client pins. It is persisted
 * in app-private storage so the gateway keeps the same identity across
 * restarts. P2 uses a software key; Android Keystore-backed keys are a
 * documented hardening item.
 */
object GatewayIdentityStore {
    private const val FILE_NAME = "openmesh-gateway-identity.p12"
    private val PASSWORD = "openmesh-pkcs12".toCharArray()
    private val cached = AtomicReference<TlsIdentity?>(null)

    @Synchronized
    fun loadOrCreate(context: Context): TlsIdentity {
        cached.get()?.let { return it }
        val file = File(context.filesDir, FILE_NAME)
        val identity = try {
            if (file.exists()) {
                TlsIdentity.load(file, PASSWORD)
            } else {
                generateAndSave(file)
            }
        } catch (_: Exception) {
            // A corrupt or unreadable identity must not prevent the gateway
            // from starting. Regenerating changes the fingerprint, which the
            // UI displays so the client pin can be updated deliberately.
            file.delete()
            generateAndSave(file)
        }
        cached.set(identity)
        return identity
    }

    private fun generateAndSave(file: File): TlsIdentity =
        TlsIdentity.generate("openmesh-gateway").also { it.save(file) }
}
