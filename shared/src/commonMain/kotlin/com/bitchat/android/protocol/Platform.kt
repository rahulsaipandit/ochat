package com.bitchat.android.protocol

import kotlin.time.Clock

/** Minimal logging seam: android.util.Log on Android, NSLog on iOS. */
expect object PlatformLog {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String)
}

/** Raw deflate (no zlib header), matching Apple COMPRESSION_ZLIB. Null on failure. */
internal expect fun rawDeflate(data: ByteArray): ByteArray?

/**
 * Inflates one complete stream into exactly [originalSize] bytes. Returns null for an invalid
 * stream, a size mismatch (under- or over-declared), an unfinished stream or trailing bytes.
 */
internal expect fun inflateExact(compressed: ByteArray, originalSize: Int, nowrap: Boolean): ByteArray?

/** Blocking permit gate backing [DecompressionResourcePool]. */
internal expect class PermitGate(permits: Int) {
    fun tryAcquire(permits: Int, timeoutMs: Long): Boolean
    fun release(permits: Int)
    val available: Int
}

internal expect fun maxHeapBytes(): Long

/** Reentrant mutual exclusion (a JVM monitor on Android, NSRecursiveLock on iOS). */
expect class PlatformLock() {
    fun lock()

    fun unlock()
}

/** Inline so a `return` inside [block] returns from the caller, like a synchronized block. */
inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Thread-safe map with conditional remove (ConcurrentHashMap on Android, a locked map on iOS). */
expect class ConcurrentMap<K : Any, V : Any>() : MutableMap<K, V> {
    fun remove(key: K, value: V): Boolean
}

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
