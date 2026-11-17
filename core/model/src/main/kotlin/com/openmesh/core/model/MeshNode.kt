package com.openmesh.core.model

/**
 * Roles a node can advertise. A node may advertise several roles.
 *
 * CLIENT  - originates VPN traffic.
 * RELAY   - forwards opaque, encrypted streams for other nodes.
 * GATEWAY - is willing to open outbound sockets for authenticated clients.
 */
enum class NodeCapability {
    CLIENT,
    RELAY,
    GATEWAY,
}

/**
 * Public description of a mesh node.
 *
 * [id] is the routing identifier: 32 lowercase hexadecimal characters
 * (16 bytes), derived from the SHA-256 fingerprint of the node's public key.
 * It is deliberately shorter than the full key fingerprint because the MVP
 * frame header carries 16 bytes.
 */
data class MeshNode(
    val id: String,
    val publicKey: ByteArray = ByteArray(0),
    val capabilities: Set<NodeCapability> = setOf(NodeCapability.CLIENT),
    val batteryPercent: Int? = null,
    val isGateway: Boolean = capabilities.contains(NodeCapability.GATEWAY),
    val lastSeenMillis: Long = 0L,
) {
    init {
        require(NodeIdFormat.isValid(id)) { "invalid node id: $id" }
        require(batteryPercent == null || batteryPercent in 0..100) {
            "batteryPercent must be in 0..100"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshNode) return false
        return id == other.id &&
            publicKey.contentEquals(other.publicKey) &&
            capabilities == other.capabilities &&
            batteryPercent == other.batteryPercent &&
            isGateway == other.isGateway &&
            lastSeenMillis == other.lastSeenMillis
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + (batteryPercent ?: 0)
        result = 31 * result + isGateway.hashCode()
        result = 31 * result + lastSeenMillis.hashCode()
        return result
    }
}

object NodeIdFormat {
    private val HEX_32 = Regex("^[0-9a-f]{32}$")

    fun isValid(id: String): Boolean = HEX_32.matches(id)
}

/** A route selected by the local routing table. */
data class MeshRoute(
    val destinationId: String,
    val nextHopId: String,
    val costMillis: Int,
    val hops: Int,
    val expiresAtMillis: Long,
    val sequence: Long,
    val gateway: Boolean,
)

/** Gateway information exposed to the VPN client. */
data class GatewayInfo(
    val nodeId: String,
    val routeCostMillis: Int,
    val hops: Int,
    val lastSeenMillis: Long,
)
