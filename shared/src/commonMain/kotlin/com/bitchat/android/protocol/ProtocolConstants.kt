package com.bitchat.android.protocol

/** Wire-level limits shared by every client. Changing these changes interoperability. */
object ProtocolConstants {
    const val COMPRESSION_THRESHOLD_BYTES: Int = 100
    const val MAX_PAYLOAD_LENGTH: Int = 10_485_760

    /** TTL stamped into the signing preimage so relays can decrement TTL without breaking signatures. */
    const val SYNC_TTL_HOPS: UByte = 0u
}
