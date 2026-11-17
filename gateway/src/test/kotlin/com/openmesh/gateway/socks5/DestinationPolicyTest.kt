package com.openmesh.gateway.socks5

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPolicyTest {

    private fun addresses(vararg values: String): List<InetAddress> =
        values.map { InetAddress.getByName(it) }

    @Test
    fun allowsPublicAddresses() {
        val policy = DestinationPolicy()
        val approved = policy.approve("example.com", 443, addresses("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"))
        assertEquals(2, approved.size)
    }

    @Test
    fun blocksRfc1918() {
        val policy = DestinationPolicy()
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 80, addresses("10.1.2.3"))
        }
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 80, addresses("172.16.0.1"))
        }
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 80, addresses("192.168.1.1"))
        }
    }

    @Test
    fun blocksLoopbackLinkLocalCgnatAndMulticast() {
        val policy = DestinationPolicy()
        listOf("127.0.0.1", "127.9.9.9", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255")
            .forEach { value ->
                assertThrows("expected $value to be blocked", PolicyDeniedException::class.java) {
                    policy.approve("host", 80, addresses(value))
                }
            }
    }

    @Test
    fun blocksUniqueLocalAndLinkLocalV6() {
        val policy = DestinationPolicy()
        listOf("fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::1", "::")
            .forEach { value ->
                assertThrows("expected $value to be blocked", PolicyDeniedException::class.java) {
                    policy.approve("host", 80, addresses(value))
                }
            }
    }

    @Test
    fun blocksIpv4MappedPrivate() {
        val policy = DestinationPolicy()
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 80, addresses("::ffff:10.0.0.1"))
        }
    }

    @Test
    fun blocksIfAnyResolvedAddressIsBlocked() {
        val policy = DestinationPolicy()
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("mixed", 443, addresses("93.184.216.34", "10.0.0.7"))
        }
    }

    @Test
    fun labAllowlistMatchesExactHostAndPortOnly() {
        val policy = DestinationPolicy(labAllowlist = setOf("10.0.2.2:8080"))
        assertTrue(policy.isLabAllowed("10.0.2.2", 8080))
        assertFalse(policy.isLabAllowed("10.0.2.2", 8081))
        assertFalse(policy.isLabAllowed("10.0.2.3", 8080))
        assertEquals(1, policy.approve("10.0.2.2", 8080, addresses("10.0.2.2")).size)
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("10.0.2.2", 8081, addresses("10.0.2.2"))
        }
    }

    @Test
    fun rejectsInvalidPortsAndEmptyResolution() {
        val policy = DestinationPolicy()
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 0, addresses("93.184.216.34"))
        }
        assertThrows(PolicyDeniedException::class.java) {
            policy.approve("host", 443, emptyList())
        }
    }
}
