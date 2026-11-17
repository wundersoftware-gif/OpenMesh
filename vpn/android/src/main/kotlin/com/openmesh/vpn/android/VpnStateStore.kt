package com.openmesh.vpn.android

import com.openmesh.core.model.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide status store. The VPN service is the only writer; UI and
 * diagnostics are readers. State is intentionally not persisted: a stale
 * "connected" badge after a process death would be a lie.
 */
object VpnStateStore {
    private val state = MutableStateFlow(VpnStatus())

    val status: StateFlow<VpnStatus> = state.asStateFlow()

    fun update(transform: (VpnStatus) -> VpnStatus) {
        state.update(transform)
    }

    fun set(value: VpnStatus) {
        state.value = value
    }

    fun reset() {
        state.value = VpnStatus()
    }
}
