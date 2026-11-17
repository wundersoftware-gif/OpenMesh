package com.openmesh.core.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(message: String) : IOException(message)

/**
 * A single binary frame.
 *
 * Wire format (all integers big-endian):
 * ```
 * magic            4 bytes  "OMSH"
 * protocol version 1 byte
 * message type     1 byte
 * flags            2 bytes
 * stream id        8 bytes
 * destination id  16 bytes (raw)
 * payload length   4 bytes (0..MAX_PAYLOAD_SIZE)
 * payload          N bytes
 * ```
 *
 * [destinationId] is the 32-character lowercase hex form of the 16-byte
 * destination routing identifier. For control frames it may be all zeroes.
 */
data class MeshFrame(
    val type: FrameType,
    val streamId: Long = ProtocolConstants.CONTROL_STREAM_ID,
    val destinationId: String = ProtocolConstants.ZERO_NODE_ID,
    val flags: Int = ProtocolConstants.FLAG_NONE,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(streamId >= 0) { "streamId must be non-negative" }
        require(payload.size <= ProtocolConstants.MAX_PAYLOAD_SIZE) {
            "payload too large: ${payload.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshFrame) return false
        return type == other.type &&
            streamId == other.streamId &&
            destinationId == other.destinationId &&
            flags == other.flags &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId.hashCode()
        result = 31 * result + destinationId.hashCode()
        result = 31 * result + flags
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MeshFrame(type=$type, stream=$streamId, dst=$destinationId, flags=$flags, bytes=${payload.size})"
}

/**
 * Strict, bounded frame codec.
 *
 * It never allocates based on an unvalidated length: the declared payload is
 * checked against [ProtocolConstants.MAX_PAYLOAD_SIZE] before allocation.
 */
object FrameCodec {

    fun encode(frame: MeshFrame): ByteArray {
        val destination = parseNodeId(frame.destinationId)
        require(frame.flags and ProtocolConstants.KNOWN_FLAGS == frame.flags) {
            "unknown flags: 0x${frame.flags.toString(16)}"
        }
        require(frame.payload.size <= ProtocolConstants.MAX_PAYLOAD_SIZE)
        val buffer = ByteBuffer.allocate(ProtocolConstants.HEADER_SIZE + frame.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(ProtocolConstants.MAGIC)
        buffer.put(ProtocolConstants.VERSION.toByte())
        buffer.put(frame.type.code.toByte())
        buffer.putShort(frame.flags.toShort())
        buffer.putLong(frame.streamId)
        buffer.put(destination)
        buffer.putInt(frame.payload.size)
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): MeshFrame {
        if (bytes.size < ProtocolConstants.HEADER_SIZE) {
            throw ProtocolException("short frame: ${bytes.size} bytes")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (!magic.contentEquals(ProtocolConstants.MAGIC)) {
            throw ProtocolException("bad magic")
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != ProtocolConstants.VERSION) {
            throw ProtocolException("unsupported version $version")
        }
        val type = FrameType.fromCode(buffer.get().toInt() and 0xFF)
        val flags = buffer.getShort().toInt() and 0xFFFF
        if (flags and ProtocolConstants.KNOWN_FLAGS != flags) {
            throw ProtocolException("unknown flags 0x${flags.toString(16)}")
        }
        val streamId = buffer.getLong()
        if (streamId < 0) throw ProtocolException("negative stream id")
        val destination = ByteArray(16).also { buffer.get(it) }
        val payloadLength = buffer.getInt()
        if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("invalid payload length $payloadLength")
        }
        if (buffer.remaining() != payloadLength) {
            throw ProtocolException(
                "declared payload $payloadLength but ${buffer.remaining()} bytes remain",
            )
        }
        val payload = ByteArray(payloadLength).also { buffer.get(it) }
        return MeshFrame(
            type = type,
            streamId = streamId,
            destinationId = formatNodeId(destination),
            flags = flags,
            payload = payload,
        )
    }

    /**
     * Reads exactly one frame. Returns null on a clean EOF at a frame boundary.
     * A truncated frame is an error, never a silently dropped connection.
     */
    fun read(input: InputStream): MeshFrame? {
        val header = ByteArray(ProtocolConstants.HEADER_SIZE)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("truncated frame header ($read/${header.size})")
            }
            if (n == 0) continue
            read += n
        }
        return decodeHeaderAndPayload(header, input)
    }

    fun write(output: OutputStream, frame: MeshFrame) {
        output.write(encode(frame))
        output.flush()
    }

    private fun decodeHeaderAndPayload(header: ByteArray, input: InputStream): MeshFrame {
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (!magic.contentEquals(ProtocolConstants.MAGIC)) {
            throw ProtocolException("bad magic")
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != ProtocolConstants.VERSION) {
            throw ProtocolException("unsupported version $version")
        }
        val type = FrameType.fromCode(buffer.get().toInt() and 0xFF)
        val flags = buffer.getShort().toInt() and 0xFFFF
        if (flags and ProtocolConstants.KNOWN_FLAGS != flags) {
            throw ProtocolException("unknown flags")
        }
        val streamId = buffer.getLong()
        if (streamId < 0) throw ProtocolException("negative stream id")
        val destination = ByteArray(16).also { buffer.get(it) }
        val payloadLength = buffer.getInt()
        if (payloadLength < 0 || payloadLength > ProtocolConstants.MAX_PAYLOAD_SIZE) {
            throw ProtocolException("invalid payload length $payloadLength")
        }
        val payload = ByteArray(payloadLength)
        var read = 0
        while (read < payloadLength) {
            val n = input.read(payload, read, payloadLength - read)
            if (n < 0) throw EOFException("truncated payload ($read/$payloadLength)")
            if (n == 0) continue
            read += n
        }
        return MeshFrame(
            type = type,
            streamId = streamId,
            destinationId = formatNodeId(destination),
            flags = flags,
            payload = payload,
        )
    }

    private fun parseNodeId(nodeId: String): ByteArray {
        if (nodeId.length != 32) throw ProtocolException("node id must be 32 hex chars")
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = hexValue(nodeId[i * 2])
            val lo = hexValue(nodeId[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw ProtocolException("invalid hex digit '$c'")
    }

    private fun formatNodeId(bytes: ByteArray): String {
        val chars = CharArray(32)
        val digits = "0123456789abcdef"
        for (i in 0 until 16) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(chars)
    }
}
