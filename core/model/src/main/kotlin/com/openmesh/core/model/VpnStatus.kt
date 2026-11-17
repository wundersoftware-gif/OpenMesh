package com.openmesh.core.model

/**
 * Lifecycle of the local VPN tunnel.
 *
 * The UI must never collapse these states into a single "connected" badge.
 * In particular [CAPTURING] and [TUNNEL_READY] mean the link exists; they do
 * not by themselves prove that a gateway or the Internet is reachable.
 */
enum class VpnPhase {
    DISCONNECTED,
    WAITING_PERMISSION,
    CONNECTING,
    CAPTURING,
    TUNNEL_READY,
    NO_ROUTE,
    ERROR,
    REVOKED,
}

/** State of the TUN link itself. */
enum class LinkState {
    DOWN,
    UP,
}

/** Whether the mesh router has a usable route to a gateway. */
enum class RouteState {
    NONE,
    SELECTED,
}

/** Real end-to-end connectivity check, never inferred from a link state. */
enum class InternetState {
    UNKNOWN,
    VERIFIED,
    FAILED,
}

/**
 * Full, immutable status snapshot shared between the VPN service and the UI.
 * Counters are cumulative for the current session.
 */
data class VpnStatus(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val targetPackage: String = "",
    val link: LinkState = LinkState.DOWN,
    val route: RouteState = RouteState.NONE,
    val internet: InternetState = InternetState.UNKNOWN,
    val gatewayId: String? = null,
    val hops: Int? = null,
    val peerCount: Int = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val txPackets: Long = 0,
    val startedAtMillis: Long? = null,
    val lastError: String? = null,
    val labDirect: Boolean = false,
    val engineRunning: Boolean = false,
) {
    val isActive: Boolean
        get() = phase == VpnPhase.CAPTURING ||
            phase == VpnPhase.TUNNEL_READY ||
            phase == VpnPhase.NO_ROUTE
}
