package com.bitchat.android.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BinaryProtocolCommonTest {
    private val sender = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())

    private fun packet(payload: ByteArray, version: UByte = 1u) = BitchatPacket(
        version = version,
        type = MessageType.MESSAGE.value,
        senderID = sender,
        timestamp = 1709600000000uL,
        payload = payload,
        ttl = 7u
    )

    @Test
    fun v1HeaderLayoutIsBigEndianAndStable() {
        val raw = BinaryProtocol.encode(packet(byteArrayOf(0x41, 0x42)), padding = false)!!
        val expected = byteArrayOf(
            0x01, 0x02, 0x07,
            0x00, 0x00, 0x01, 0x8E.toByte(), 0x0C, 0x19, 0xC8.toByte(), 0x00, // timestamp 1709600000000
            0x00, // flags
            0x00, 0x02, // payload length
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(),
            0x41, 0x42
        )
        assertContentEquals(expected, raw)
    }

    @Test
    fun paddedPacketRoundTrips() {
        val decoded = BinaryProtocol.decode(BinaryProtocol.encode(packet("hello".encodeToByteArray()))!!)
        assertNotNull(decoded)
        assertContentEquals("hello".encodeToByteArray(), decoded.payload)
        assertEquals(7u.toUByte(), decoded.ttl)
    }

    @Test
    fun compressiblePayloadRoundTripsThroughPlatformDeflate() {
        val payload = ByteArray(4_000) { (it % 7).toByte() }
        val raw = BinaryProtocol.encode(packet(payload, version = 2u), padding = false)!!
        assertEquals(true, raw.size < payload.size)
        assertContentEquals(payload, BinaryProtocol.decode(raw)!!.payload)
    }

    @Test
    fun overDeclaredExpansionIsRejected() {
        val payload = ByteArray(4_000) { (it % 7).toByte() }
        val raw = BinaryProtocol.encode(packet(payload, version = 2u), padding = false)!!
        // v2 header is 16 bytes + 8 sender; the next 4 bytes are the declared original size.
        raw[24 + 3] = (raw[24 + 3] + 1).toByte()
        assertNull(BinaryProtocol.decode(raw))
    }

    @Test
    fun paddingIsStrictPkcs7() {
        val padded = MessagePadding.pad(byteArrayOf(1, 2, 3), 256)
        assertEquals(256, padded.size)
        assertContentEquals(byteArrayOf(1, 2, 3), MessagePadding.unpad(padded))
        val corrupt = padded.copyOf().also { it[200] = 0 }
        assertContentEquals(corrupt, MessagePadding.unpad(corrupt))
    }
}
