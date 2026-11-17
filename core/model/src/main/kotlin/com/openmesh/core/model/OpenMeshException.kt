package com.openmesh.core.model

/**
 * Typed failures. Every failure surfaced to the UI should be one of these,
 * so that the state is visible and recoverable instead of silently ignored.
 */
sealed class OpenMeshException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class TargetAppNotInstalled(val packageName: String) :
        OpenMeshException("Target package is not installed: $packageName")

    class VpnPermissionMissing :
        OpenMeshException("VPN permission has not been granted")

    class VpnEstablishFailed(reason: String? = null) :
        OpenMeshException("VpnService.Builder.establish() returned null${reason?.let { ": $it" } ?: ""}")

    class AnotherVpnActive :
        OpenMeshException("Another VPN is active or the system rejected the tunnel")

    class Tun2SocksStartFailed(val detail: String) :
        OpenMeshException("tun2socks failed to start: $detail")

    class LocalProxyStartFailed(val detail: String) :
        OpenMeshException("local SOCKS5 frontend failed to start: $detail")

    class GatewayUnavailable :
        OpenMeshException("no reachable gateway")

    class PolicyDenied(val reason: String) :
        OpenMeshException("destination rejected by policy: $reason")

    class ProtocolViolation(val reason: String) :
        OpenMeshException("protocol violation: $reason")

    class TransportFailure(val reason: String, cause: Throwable? = null) :
        OpenMeshException("transport failure: $reason", cause)
}
