package com.openmesh.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide gateway status. The gateway foreground service is the only
 * writer. Like VpnStateStore it is not persisted: a "running" badge after a
 * process death would be a lie.
 */
data class GatewayStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val port: Int = GatewayPreferences.DEFAULT_PORT,
    val fingerprint: String? = null,
    val error: String? = null,
    val accepted: Long = 0,
    val denied: Long = 0,
    val active: Long = 0,
    val bytesToRemote: Long = 0,
    val bytesFromRemote: Long = 0,
    val startedAtMillis: Long? = null,
)

object GatewayStateStore {
    private val state = MutableStateFlow(GatewayStatus())

    val status: StateFlow<GatewayStatus> = state.asStateFlow()

    fun update(transform: (GatewayStatus) -> GatewayStatus) {
        state.update(transform)
    }

    fun set(value: GatewayStatus) {
        state.value = value
    }
}
