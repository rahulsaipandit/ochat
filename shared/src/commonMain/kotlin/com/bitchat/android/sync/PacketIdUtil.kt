package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.sha256

/**
 * Deterministic packet ID helper for sync purposes.
 * Uses SHA-256 over a canonical subset of packet fields:
 * [type | senderID | timestamp | payload] to generate a stable ID.
 * Returns a 16-byte (128-bit) truncated hash for compactness.
 */
object PacketIdUtil {
    fun computeIdBytes(packet: BitchatPacket): ByteArray {
        // Canonical preimage: type | senderID | timestamp (8 bytes big-endian) | payload
        val ts = packet.timestamp.toLong()
        val timestamp = ByteArray(8) { i -> ((ts ushr ((7 - i) * 8)) and 0xFF).toByte() }
        val preimage = byteArrayOf(packet.type.toByte()) + packet.senderID + timestamp + packet.payload
        return sha256(preimage).copyOf(16) // 128-bit ID
    }

    fun computeIdHex(packet: BitchatPacket): String {
        return computeIdBytes(packet).toHexString()
    }
}

