package com.bitchat.android.protocol

/** Big-endian cursor over a byte array. Underflow throws, which callers treat as a malformed packet. */
class ByteReader(private val data: ByteArray) {
    var position: Int = 0
        private set

    fun get(): Byte {
        if (position >= data.size) throw IndexOutOfBoundsException("read past end")
        return data[position++]
    }

    fun get(dst: ByteArray) {
        if (data.size - position < dst.size) throw IndexOutOfBoundsException("read past end")
        data.copyInto(dst, 0, position, position + dst.size)
        position += dst.size
    }

    fun remaining(): Int = data.size - position

    fun hasRemaining(): Boolean = position < data.size

    fun getShort(): Short = ((get().toInt() and 0xFF shl 8) or (get().toInt() and 0xFF)).toShort()

    fun getInt(): Int {
        var v = 0
        repeat(4) { v = (v shl 8) or (get().toInt() and 0xFF) }
        return v
    }

    fun getLong(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or (get().toLong() and 0xFF) }
        return v
    }
}

/** Growable big-endian byte sink. */
class ByteWriter(initialCapacity: Int, private val maxSize: Int = Int.MAX_VALUE) {
    private var buf = ByteArray(initialCapacity.coerceAtMost(maxSize).coerceAtLeast(16))
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        // Mirrors ByteBuffer.allocate(n): writing past a fixed capacity is an error, not a resize.
        if (size + extra > maxSize) throw IndexOutOfBoundsException("write past capacity")
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun put(b: Byte) {
        ensure(1)
        buf[size++] = b
    }

    fun put(src: ByteArray) {
        ensure(src.size)
        src.copyInto(buf, size)
        size += src.size
    }

    fun putShort(v: Short) {
        put((v.toInt() shr 8).toByte())
        put(v.toByte())
    }

    fun putInt(v: Int) {
        for (shift in 24 downTo 0 step 8) put((v shr shift).toByte())
    }

    fun putLong(v: Long) {
        for (shift in 56 downTo 0 step 8) put((v shr shift).toByte())
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
