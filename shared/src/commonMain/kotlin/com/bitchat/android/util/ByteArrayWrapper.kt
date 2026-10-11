package com.bitchat.android.util


/**
 * A wrapper class for ByteArray to allow it to be used as a key in HashMaps.
 * The default ByteArray does not override equals() and hashCode() based on content.
 *
 * @param bytes The byte array to wrap.
 */
data class ByteArrayWrapper(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ByteArrayWrapper
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    fun toHexString(): String {
        return bytes.toHexString()
    }
}
