@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.android.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

// NOTE: written against platform.zlib but not yet compiled or run; needs a macOS build and the
// golden-vector tests on an iOS target before it can be trusted (see docs/designOChat.md).

actual object PlatformLog {
    actual fun d(tag: String, message: String) { NSLog("D/%s: %s", tag, message) }
    actual fun i(tag: String, message: String) { NSLog("I/%s: %s", tag, message) }
    actual fun w(tag: String, message: String) { NSLog("W/%s: %s", tag, message) }
    actual fun e(tag: String, message: String) { NSLog("E/%s: %s", tag, message) }
}

private const val RAW_WINDOW_BITS = -15
private const val ZLIB_WINDOW_BITS = 15
private const val DEFAULT_MEM_LEVEL = 8
private const val Z_DEFAULT_STRATEGY_VALUE = 0

internal actual fun rawDeflate(data: ByteArray): ByteArray? = memScoped {
    val stream = alloc<z_stream>()
    val init = deflateInit2_(
        stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, RAW_WINDOW_BITS,
        DEFAULT_MEM_LEVEL, Z_DEFAULT_STRATEGY_VALUE, ZLIB_VERSION, sizeOf<z_stream>().toInt()
    )
    if (init != Z_OK) return@memScoped null
    try {
        val bound = deflateBound(stream.ptr, data.size.convert()).toInt()
        val out = ByteArray(bound.coerceAtLeast(16))
        data.usePinned { input ->
            out.usePinned { output ->
                stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
                stream.avail_in = data.size.convert()
                stream.next_out = output.addressOf(0).reinterpret<UByteVar>()
                stream.avail_out = out.size.convert()
                if (deflate(stream.ptr, Z_FINISH) != Z_STREAM_END) return@memScoped null
            }
        }
        out.copyOf(stream.total_out.toInt())
    } finally {
        deflateEnd(stream.ptr)
    }
}

internal actual fun inflateExact(compressed: ByteArray, originalSize: Int, nowrap: Boolean): ByteArray? = memScoped {
    val stream = alloc<z_stream>()
    val windowBits = if (nowrap) RAW_WINDOW_BITS else ZLIB_WINDOW_BITS
    if (inflateInit2_(stream.ptr, windowBits, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
        return@memScoped null
    }
    try {
        // One spare byte: producing it proves the declared size was too small.
        val out = ByteArray(originalSize + 1)
        val result = compressed.usePinned { input ->
            out.usePinned { output ->
                stream.next_in = input.addressOf(0).reinterpret<UByteVar>()
                stream.avail_in = compressed.size.convert()
                stream.next_out = output.addressOf(0).reinterpret<UByteVar>()
                stream.avail_out = out.size.convert()
                inflate(stream.ptr, Z_NO_FLUSH)
            }
        }
        val exact = result == Z_STREAM_END &&
            stream.total_out.toInt() == originalSize &&
            stream.avail_in.toInt() == 0
        if (exact) out.copyOf(originalSize) else null
    } finally {
        inflateEnd(stream.ptr)
    }
}

internal actual class PermitGate actual constructor(permits: Int) {
    private val total = permits
    private val condition = NSCondition()
    private var free = permits

    actual fun tryAcquire(permits: Int, timeoutMs: Long): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
        condition.lock()
        try {
            while (free < permits) {
                if (!condition.waitUntilDate(deadline)) return false
            }
            free -= permits
            return true
        } finally {
            condition.unlock()
        }
    }

    actual fun release(permits: Int) {
        condition.lock()
        free = (free + permits).coerceAtMost(total)
        condition.broadcast()
        condition.unlock()
    }

    actual val available: Int
        get() {
            condition.lock()
            try { return free } finally { condition.unlock() }
        }
}

internal actual fun maxHeapBytes(): Long = NSProcessInfo.processInfo.physicalMemory.toLong() / 2

actual class PlatformLock actual constructor() {
    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}

actual class ConcurrentMap<K : Any, V : Any> actual constructor() : AbstractMutableMap<K, V>() {
    private val lock = PlatformLock()
    private val delegate = LinkedHashMap<K, V>()

    override val size: Int get() = lock.withLock { delegate.size }

    override fun get(key: K): V? = lock.withLock { delegate[key] }

    override fun containsKey(key: K): Boolean = lock.withLock { delegate.containsKey(key) }

    override fun put(key: K, value: V): V? = lock.withLock { delegate.put(key, value) }

    override fun remove(key: K): V? = lock.withLock { delegate.remove(key) }

    actual fun remove(key: K, value: V): Boolean = lock.withLock {
        if (delegate[key] == value) { delegate.remove(key); true } else false
    }

    override fun clear() = lock.withLock { delegate.clear() }

    /** A snapshot: iterate freely, but mutate through the map itself. */
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = lock.withLock { LinkedHashMap(delegate).entries }
}
