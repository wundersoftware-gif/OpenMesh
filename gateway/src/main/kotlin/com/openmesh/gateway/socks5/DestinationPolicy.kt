package com.openmesh.gateway.socks5

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Destination policy for the gateway and for the lab frontend.
 *
 * Private, loopback, link-local, multicast and CGNAT destinations are denied
 * by default. A lab allowlist may permit exact host:port pairs (for example
 * `10.0.2.2:8080` on an Android emulator); this is a development feature and
 * must never be enabled in a release build.
 *
 * The policy is applied to *resolved* addresses, and the connector dials only
 * an address that this policy has approved. That prevents a DNS answer from
 * changing between validation and connect (DNS rebinding).
 */
class DestinationPolicy(
    private val allowPrivateDestinations: Boolean = false,
    private val labAllowlist: Set<String> = emptySet(),
) {
    fun isLabAllowed(host: String, port: Int): Boolean =
        labAllowlist.contains("$host:$port")

    /**
     * Returns the addresses that may be dialled, or throws [PolicyDeniedException].
     * The order of the resolved list is preserved.
     */
    fun approve(host: String, port: Int, resolved: List<InetAddress>): List<InetAddress> {
        if (port !in 1..65535) {
            throw PolicyDeniedException("invalid port $port")
        }
        if (resolved.isEmpty()) {
            throw PolicyDeniedException("host did not resolve: $host")
        }
        if (allowPrivateDestinations || isLabAllowed(host, port)) {
            return resolved
        }
        val blocked = resolved.firstOrNull { isBlocked(it) }
        if (blocked != null) {
            throw PolicyDeniedException(
                "destination $host:$port resolves to blocked address ${blocked.hostAddress}",
            )
        }
        return resolved
    }

    fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> isBlockedV4(address.address)
            is Inet6Address -> isBlockedV6(address.address)
            else -> true
        }
    }

    private fun isBlockedV4(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // 0.0.0.0/8 "this network"
        if (b0 == 0) return true
        // 10.0.0.0/8
        if (b0 == 10) return true
        // 100.64.0.0/10 CGNAT
        if (b0 == 100 && b1 in 64..127) return true
        // 127.0.0.0/8
        if (b0 == 127) return true
        // 169.254.0.0/16 link-local
        if (b0 == 169 && b1 == 254) return true
        // 172.16.0.0/12
        if (b0 == 172 && b1 in 16..31) return true
        // 192.168.0.0/16
        if (b0 == 192 && b1 == 168) return true
        // 224.0.0.0/4 multicast, 240.0.0.0/4 reserved, broadcast
        if (b0 >= 224) return true
        return false
    }

    private fun isBlockedV6(bytes: ByteArray): Boolean {
        // ::/128, ::1/128
        if (bytes.all { it.toInt() == 0 }) return true
        if (bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1) return true
        // IPv4-mapped ::ffff:a.b.c.d
        val firstTenZero = (0 until 10).all { bytes[it].toInt() == 0 }
        if (firstTenZero &&
            bytes[10].toInt() and 0xFF == 0xFF &&
            bytes[11].toInt() and 0xFF == 0xFF
        ) {
            return isBlockedV4(bytes.copyOfRange(12, 16))
        }
        val b0 = bytes[0].toInt() and 0xFF
        // fc00::/7 unique local
        if (b0 == 0xFC || b0 == 0xFD) return true
        // fe80::/10 link-local
        if (b0 == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80) return true
        // ff00::/8 multicast
        if (b0 == 0xFF) return true
        return false
    }
}

class PolicyDeniedException(message: String) : Exception(message)
