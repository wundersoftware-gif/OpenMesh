package com.openmesh.vpn.android

import android.content.Context
import com.openmesh.core.model.VpnStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Options supplied by the application layer. The service caches them so a
 * system-initiated restart uses the same target instead of silently falling
 * back to a different application.
 */
data class VpnStartOptions(
    val targetPackage: String,
    val labDirectSocks: Boolean = false,
    val labAllowlist: Set<String> = emptySet(),
)

object VpnPreferences {
    private const val FILE = "openmesh_vpn"
    private const val KEY_TARGET = "target_package"
    private const val KEY_LAB_DIRECT = "lab_direct"
    private const val KEY_LAB_ALLOWLIST = "lab_allowlist"

    fun save(context: Context, options: VpnStartOptions) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_TARGET, options.targetPackage)
            .putBoolean(KEY_LAB_DIRECT, options.labDirectSocks)
            .putStringSet(KEY_LAB_ALLOWLIST, options.labAllowlist)
            .apply()
    }

    fun load(context: Context): VpnStartOptions? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val target = prefs.getString(KEY_TARGET, null) ?: return null
        return VpnStartOptions(
            targetPackage = target,
            labDirectSocks = prefs.getBoolean(KEY_LAB_DIRECT, false),
            labAllowlist = prefs.getStringSet(KEY_LAB_ALLOWLIST, emptySet()) ?: emptySet(),
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
