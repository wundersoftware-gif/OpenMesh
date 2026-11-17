package com.openmesh.vpn.android

import android.content.Context
import com.openmesh.core.model.VpnStatus
import com.openmesh.gateway.client.GatewayEndpoint
import kotlinx.coroutines.flow.StateFlow

/**
 * Options supplied by the application layer. The service caches them so a
 * system-initiated restart uses the same target instead of silently falling
 * back to a different application.
 *
 * [gatewayToken] is a bearer secret. In the P2 lab it is stored in the
 * app-private preference file; moving it to the Android Keystore is tracked as
 * a hardening item in docs/SECURITY.md.
 */
data class VpnStartOptions(
    val targetPackage: String,
    val labDirectSocks: Boolean = false,
    val labAllowlist: Set<String> = emptySet(),
    val gatewayHost: String? = null,
    val gatewayPort: Int = DEFAULT_GATEWAY_PORT,
    val gatewayFingerprint: String? = null,
    val gatewayToken: String? = null,
) {
    val remoteGateway: Boolean
        get() = !gatewayHost.isNullOrBlank() &&
            !gatewayFingerprint.isNullOrBlank() &&
            !gatewayToken.isNullOrBlank()

    fun gatewayEndpoint(): GatewayEndpoint? {
        if (!remoteGateway) return null
        return GatewayEndpoint(
            host = gatewayHost.orEmpty().trim(),
            port = gatewayPort,
            fingerprint = gatewayFingerprint.orEmpty(),
            token = gatewayToken.orEmpty(),
        )
    }

    companion object {
        const val DEFAULT_GATEWAY_PORT = 8443
    }
}

object VpnPreferences {
    private const val FILE = "openmesh_vpn"
    private const val KEY_TARGET = "target_package"
    private const val KEY_LAB_DIRECT = "lab_direct"
    private const val KEY_LAB_ALLOWLIST = "lab_allowlist"
    private const val KEY_GATEWAY_HOST = "gateway_host"
    private const val KEY_GATEWAY_PORT = "gateway_port"
    private const val KEY_GATEWAY_FINGERPRINT = "gateway_fingerprint"
    private const val KEY_GATEWAY_TOKEN = "gateway_token"

    fun save(context: Context, options: VpnStartOptions) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_TARGET, options.targetPackage)
            .putBoolean(KEY_LAB_DIRECT, options.labDirectSocks)
            .putStringSet(KEY_LAB_ALLOWLIST, options.labAllowlist)
            .putString(KEY_GATEWAY_HOST, options.gatewayHost)
            .putInt(KEY_GATEWAY_PORT, options.gatewayPort)
            .putString(KEY_GATEWAY_FINGERPRINT, options.gatewayFingerprint)
            .putString(KEY_GATEWAY_TOKEN, options.gatewayToken)
            .apply()
    }

    fun load(context: Context): VpnStartOptions? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val target = prefs.getString(KEY_TARGET, null) ?: return null
        return VpnStartOptions(
            targetPackage = target,
            labDirectSocks = prefs.getBoolean(KEY_LAB_DIRECT, false),
            labAllowlist = prefs.getStringSet(KEY_LAB_ALLOWLIST, emptySet()) ?: emptySet(),
            gatewayHost = prefs.getString(KEY_GATEWAY_HOST, null),
            gatewayPort = prefs.getInt(
                KEY_GATEWAY_PORT,
                VpnStartOptions.DEFAULT_GATEWAY_PORT,
            ),
            gatewayFingerprint = prefs.getString(KEY_GATEWAY_FINGERPRINT, null),
            gatewayToken = prefs.getString(KEY_GATEWAY_TOKEN, null),
        )
    }
}

object VpnControl {
    fun observeStatus(): StateFlow<VpnStatus> = VpnStateStore.status

    fun start(context: Context, options: VpnStartOptions) {
        VpnPreferences.save(context, options)
        OpenMeshVpnService.start(context)
    }

    fun stop(context: Context) {
        OpenMeshVpnService.stop(context)
    }
}
