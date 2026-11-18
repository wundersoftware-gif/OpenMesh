package com.openmesh.gateway

import com.openmesh.gateway.socks5.DnsQueryFilter
import com.openmesh.gateway.socks5.OutboundConnector
import com.openmesh.gateway.socks5.SocksDnsResolver
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsQueryFilterTest {

    @Test
    fun `AAAA query is answered with an empty NOERROR response`() {
        val query = query(name = "g.whatsapp.net", type = DnsQueryFilter.TYPE_AAAA)

        val response = DnsQueryFilter.emptyIpv6Answer(query)

        assertNotNull(response)
        response!!
        assertEquals("transaction id is preserved", readU16(query, 0), readU16(response, 0))
        assertTrue("QR bit set", readU16(response, 2) and 0x8000 != 0)
        assertTrue("RD bit preserved", readU16(response, 2) and 0x0100 != 0)
        assertTrue("RA bit set", readU16(response, 2) and 0x0080 != 0)
        assertEquals("NOERROR", 0, readU16(response, 2) and 0x000F)
        assertEquals("one question", 1, readU16(response, 4))
        assertEquals("no answers", 0, readU16(response, 6))
        assertEquals("no authority records", 0, readU16(response, 8))
        assertEquals("no additional records", 0, readU16(response, 10))
        assertArrayEquals("question echoed verbatim", query.copyOfRange(12, query.size), response.copyOfRange(12, response.size))
    }

    @Test
    fun `A query is forwarded unchanged`() {
        assertNull(DnsQueryFilter.emptyIpv6Answer(query("g.whatsapp.net", type = 1)))
    }

    @Test
    fun `HTTPS record query is forwarded unchanged`() {
        assertNull(DnsQueryFilter.emptyIpv6Answer(query("example.com", type = 65)))
    }

    @Test
    fun `non IN class is forwarded unchanged`() {
        assertNull(DnsQueryFilter.emptyIpv6Answer(query("example.com", type = DnsQueryFilter.TYPE_AAAA, klass = 3)))
    }

    @Test
    fun `multiple questions are forwarded unchanged`() {
        val bytes = query("example.com", type = DnsQueryFilter.TYPE_AAAA)
        writeU16(bytes, 4, 2)
        assertNull(DnsQueryFilter.emptyIpv6Answer(bytes))
    }

    @Test
    fun `already a response is forwarded unchanged`() {
        val bytes = query("example.com", type = DnsQueryFilter.TYPE_AAAA)
        writeU16(bytes, 2, readU16(bytes, 2) or 0x8000)
        assertNull(DnsQueryFilter.emptyIpv6Answer(bytes))
    }

    @Test
    fun `malformed queries are forwarded unchanged`() {
        assertNull(DnsQueryFilter.emptyIpv6Answer(ByteArray(0)))
        assertNull(DnsQueryFilter.emptyIpv6Answer(ByteArray(11)))
        assertNull(DnsQueryFilter.emptyIpv6Answer(query("example.com", type = DnsQueryFilter.TYPE_AAAA).copyOf(14)))
        assertNull(DnsQueryFilter.emptyIpv6Answer(query("example.com", type = DnsQueryFilter.TYPE_AAAA).copyOf(20)))
    }

    @Test
    fun `compression pointer in the question is rejected`() {
        val bytes = query("example.com", type = DnsQueryFilter.TYPE_AAAA)
        bytes[12] = 0xC0.toByte()
        assertNull(DnsQueryFilter.emptyIpv6Answer(bytes))
    }

    @Test
    fun `resolver never opens a connection for an AAAA query when ipv4Only`() {
        val connector = RecordingConnector()
        val resolver = SocksDnsResolver(connector = connector, ipv4Only = true)

        val response = resolver.resolve(query("g.whatsapp.net", type = DnsQueryFilter.TYPE_AAAA))

        assertNotNull(response)
        assertEquals("no upstream connection", 0, connector.calls)
    }

    @Test
    fun `resolver still connects for an A query when ipv4Only`() {
        val connector = RecordingConnector()
        val resolver = SocksDnsResolver(
            connector = connector,
            upstreams = listOf(InetSocketAddress("127.0.0.1", 53)),
            ipv4Only = true,
            timeoutMillis = 50,
        )

        resolver.resolve(query("g.whatsapp.net", type = 1))

        assertEquals("A query is forwarded", 1, connector.calls)
    }

    private class RecordingConnector : OutboundConnector {
        var calls = 0
            private set

        override fun connect(host: String, port: Int): Socket {
            calls++
            throw java.net.ConnectException("test")
        }
    }

    private fun query(name: String, type: Int, klass: Int = 1): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(value: Int) {
            out.add((value ushr 8).toByte())
            out.add(value.toByte())
        }
        u16(0x2F3A) // transaction id
        u16(0x0100) // RD
        u16(1) // QDCOUNT
        u16(0) // ANCOUNT
        u16(0) // NSCOUNT
        u16(0) // ARCOUNT
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        u16(type)
        u16(klass)
        return out.toByteArray()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
