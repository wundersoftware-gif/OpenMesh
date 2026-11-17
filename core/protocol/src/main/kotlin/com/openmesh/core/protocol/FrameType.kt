package com.openmesh.core.protocol

/**
 * Message types of the OpenMesh binary protocol.
 *
 * The numeric values are part of the wire format and must not be renumbered.
 * New types may be appended after 0x0D.
 */
enum class FrameType(val code: Int) {
    HELLO(0x01),
    AUTH(0x02),
    PEER_ANNOUNCE(0x03),
    ROUTE_UPDATE(0x04),
    OPEN_STREAM(0x05),
    OPEN_RESULT(0x06),
    STREAM_DATA(0x07),
    WINDOW_UPDATE(0x08),
    CLOSE_STREAM(0x09),
    PING(0x0A),
    PONG(0x0B),
    ERROR(0x0C),
    ;

    companion object {
        fun fromCode(code: Int): FrameType =
            entries.firstOrNull { it.code == code }
                ?: throw ProtocolException("unknown frame type 0x${code.toString(16)}")
    }
}

object ProtocolConstants {
    /** ASCII "OMSH". */
    val MAGIC = byteArrayOf(0x4F, 0x4D, 0x53, 0x48)
    const val VERSION: Int = 1

    /** magic + version + type + flags + stream + destination + length. */
    const val HEADER_SIZE: Int = 36
    const val MAX_PAYLOAD_SIZE: Int = 64 * 1024

    /** Stream 0 is reserved for connection-level control frames. */
    const val CONTROL_STREAM_ID: Long = 0L

    /** Flags. Unknown flags are rejected rather than ignored. */
    const val FLAG_NONE: Int = 0
    const val FLAG_ACK_REQUIRED: Int = 1
    const val FLAG_FIN: Int = 1 shl 1
    const val FLAG_RST: Int = 1 shl 2
    const val KNOWN_FLAGS: Int = FLAG_ACK_REQUIRED or FLAG_FIN or FLAG_RST

    val ZERO_NODE_ID: String = "0".repeat(32)
}
