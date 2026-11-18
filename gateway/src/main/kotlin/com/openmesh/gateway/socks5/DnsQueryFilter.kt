package com.openmesh.gateway.socks5

/**
 * IPv4-only egress assistance for the P2 tunnel.
 *
 * The P2 data path carries IPv4 destinations. The TUN still routes `::/0`
 * into the tunnel so that IPv6 can never leak through the underlying network,
 * but an app that receives AAAA records will prefer IPv6, get a reset from the
 * tunnel and report "no connectivity" even though IPv4 works. Answering AAAA
 * queries with an empty NOERROR response makes the platform resolver hand out
 * IPv4 only, which is what the tunnel can actually carry.
 *
 * This is not a DNS cache and it never rewrites an A answer: it only turns a
 * single-question AAAA query into a valid empty response. Everything else is
 * forwarded unchanged.
 */
object DnsQueryFilter {

    const val TYPE_AAAA: Int = 28

    private const val HEADER_SIZE = 12
    private const val CLASS_IN: Int = 1
    private const val FLAG_QR: Int = 0x8000
    private const val FLAG_RD: Int = 0x0100
    private const val OPCODE_MASK: Int = 0x7800

    /**
     * Returns a synthesised empty NOERROR response when [query] is a single
     * question AAAA query, or null when the query must be forwarded unchanged.
     */
    fun emptyIpv6Answer(query: ByteArray): ByteArray? {
        if (query.size < HEADER_SIZE) return null
        val queryFlags = readU16(query, 2)
        if (queryFlags and FLAG_QR != 0) return null
        if (readU16(query, 4) != 1) return null

        var offset = HEADER_SIZE
        while (true) {
            if (offset >= query.size) return null
            val labelLength = query[offset].toInt() and 0xFF
            if (labelLength == 0) {
                offset += 1
                break
            }
            // Compression pointers are not valid in a question section.
            if (labelLength and 0xC0 != 0) return null
            offset += 1 + labelLength
        }
        if (offset + 4 > query.size) return null

        val type = readU16(query, offset)
        val klass = readU16(query, offset + 2)
        if (type != TYPE_AAAA || klass != CLASS_IN) return null

        val response = query.copyOf(offset + 4)
        // QR=1, RA=1, RD and OPCODE preserved, RCODE=0 (NOERROR).
        writeU16(response, 2, (queryFlags and (OPCODE_MASK or FLAG_RD)) or 0x8080)
        writeU16(response, 6, 0) // ANCOUNT: no IPv6 addresses.
        writeU16(response, 8, 0) // NSCOUNT
        writeU16(response, 10, 0) // ARCOUNT
        return response
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
