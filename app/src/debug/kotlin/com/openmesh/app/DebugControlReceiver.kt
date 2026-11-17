package com.openmesh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openmesh.transport.Fingerprint
import com.openmesh.vpn.android.OpenMeshVpnService
import com.openmesh.vpn.android.VpnControl
import com.openmesh.vpn.android.VpnPreferences
import com.openmesh.vpn.android.VpnStartOptions

/**
 * Lab-only control surface for repeatable evidence runs.
 *
 * This receiver exists only in the debug source set. It lets adb start and
 * stop the gateway and the VPN without relying on brittle UI taps, and it
 * dumps the current state to logcat. It is intentionally absent from release
 * builds and must never be promoted there: an exported control receiver that
 * accepts a gateway token would be a security hole in a shipped app.
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_GATEWAY -> {
                val port = intent.getIntExtra(EXTRA_PORT, GatewayPreferences.DEFAULT_PORT)
                GatewayPreferences.savePort(context, port)
                GatewayService.start(context)
                Log.i(TAG, "start gateway requested on port $port")
            }
            ACTION_STOP_GATEWAY -> {
                GatewayService.stop(context)
                Log.i(TAG, "stop gateway requested")
            }
            ACTION_START_VPN -> {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_GATEWAY
                val options = when (mode) {
                    MODE_GATEWAY -> VpnStartOptions(
                        targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                        gatewayHost = intent.getStringExtra(EXTRA_HOST),
                        gatewayPort = intent.getIntExtra(
                            EXTRA_PORT,
                            VpnStartOptions.DEFAULT_GATEWAY_PORT,
                        ),
                        gatewayFingerprint = intent.getStringExtra(EXTRA_FINGERPRINT)
                            ?.let { Fingerprint.normalize(it) },
                        gatewayToken = intent.getStringExtra(EXTRA_TOKEN),
                    )
                    MODE_LAB -> VpnStartOptions(
                        targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                        labDirectSocks = true,
                        labAllowlist = setOf("10.0.2.2:8080"),
                    )
                    else -> VpnStartOptions(
                        targetPackage = BuildConfig.OPENMESH_TARGET_PACKAGE,
                    )
                }
                VpnPreferences.save(context, options)
                OpenMeshVpnService.start(context)
                Log.i(TAG, "start VPN requested mode=$mode options=$options")
            }
            ACTION_STOP_VPN -> {
                OpenMeshVpnService.stop(context)
                Log.i(TAG, "stop VPN requested")
            }
            ACTION_STATUS -> {
                Log.i(
                    TAG,
                    "vpn=${VpnControl.observeStatus().value} gateway=${GatewayStateStore.status.value}",
                )
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "OpenMeshDebug"
        const val ACTION_START_GATEWAY = "com.openmesh.debug.START_GATEWAY"
        const val ACTION_STOP_GATEWAY = "com.openmesh.debug.STOP_GATEWAY"
        const val ACTION_START_VPN = "com.openmesh.debug.START_VPN"
        const val ACTION_STOP_VPN = "com.openmesh.debug.STOP_VPN"
        const val ACTION_STATUS = "com.openmesh.debug.STATUS"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_FINGERPRINT = "fingerprint"
        const val EXTRA_TOKEN = "token"
        const val MODE_GATEWAY = "gateway"
        const val MODE_LAB = "lab"
        const val MODE_CAPTURE = "capture"
    }
}
