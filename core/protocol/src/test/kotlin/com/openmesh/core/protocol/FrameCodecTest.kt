package com.openmesh.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCodecTest {

    private val destination = "0123456789abcdef0123456789abcdef"

    @Test
    fun roundTripPreservesAllFields() {
        val frame = MeshFrame(
            type = FrameType.STREAM_DATA,
            streamId = 42L,
            destinationId = destination,
            flags = ProtocolConstants.FLAG_FIN,
            payload = "hola".toByteArray(),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun maxPayloadRoundTrips() {
        val payload = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE) { (it and 0xFF).toByte() }
        val frame = MeshFrame(FrameType.STREAM_DATA, payload = payload, destinationId = destination)
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun rejectsOversizedPayloadAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshFrame(
                FrameType.STREAM_DATA,
                payload = ByteArray(ProtocolConstants.MAX_PAYLOAD_SIZE + 1),
                destinationId = destination,
            )
        }
    }

    @Test
    fun rejectsShortBuffer() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(ByteArray(ProtocolConstants.HEADER_SIZE - 1))
        }
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        bytes[0] = 'X'.code.toByte()
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsUnknownVersion() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        bytes[4] = 9
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsUnknownFrameType() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        bytes[5] = 0x7F
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsUnknownFlags() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        bytes[7] = 0x40
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsDeclaredLengthLargerThanBuffer() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(ProtocolConstants.HEADER_SIZE - 4, ProtocolConstants.MAX_PAYLOAD_SIZE)
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsOverMaxDeclaredLength() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            .putInt(ProtocolConstants.HEADER_SIZE - 4, ProtocolConstants.MAX_PAYLOAD_SIZE + 1)
        assertThrows(ProtocolException::class.java) { FrameCodec.decode(bytes) }
    }

    @Test
    fun rejectsTrailingBytes() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        assertThrows(ProtocolException::class.java) {
            FrameCodec.decode(bytes + byteArrayOf(0))
        }
    }

    @Test
    fun rejectsInvalidNodeId() {
        assertThrows(ProtocolException::class.java) {
            FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = "not-hex"))
        }
    }

    @Test
    fun readReturnsNullOnCleanEof() {
        assertNull(FrameCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun readRejectsTruncatedHeader() {
        val bytes = FrameCodec.encode(MeshFrame(FrameType.PING, destinationId = destination))
        assertThrows(EOFException::class.java) {
            FrameCodec.read(ByteArrayInputStream(bytes.copyOfRange(0, 10)))
        }
    }

    @Test
    fun readRejectsTruncatedPayload() {
        val frame = MeshFrame(
            type = FrameType.STREAM_DATA,
            destinationId = destination,
            payload = ByteArray(128) { 7 },
        )
        val bytes = FrameCodec.encode(frame)
        assertThrows(EOFException::class.java) {
            FrameCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)))
        }
    }

    @Test
    fun streamRoundTrip() {
        val out = ByteArrayOutputStream()
        val first = MeshFrame(FrameType.HELLO, destinationId = destination, payload = byteArrayOf(1))
        val second = MeshFrame(FrameType.PING, destinationId = destination)
        FrameCodec.write(out, first)
        FrameCodec.write(out, second)
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(first, FrameCodec.read(input))
        assertEquals(second, FrameCodec.read(input))
        assertNull(FrameCodec.read(input))
    }

    @Test
    fun zeroNodeIdIsAcceptedForControlFrames() {
        val frame = MeshFrame(FrameType.PING)
        assertEquals(ProtocolConstants.ZERO_NODE_ID, FrameCodec.decode(FrameCodec.encode(frame)).destinationId)
    }
}
