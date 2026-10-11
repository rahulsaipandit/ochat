package com.bitchat.android.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual object PlatformLog {
    actual fun d(tag: String, message: String) { Log.d(tag, message) }
    actual fun i(tag: String, message: String) { Log.i(tag, message) }
    actual fun w(tag: String, message: String) { Log.w(tag, message) }
    actual fun e(tag: String, message: String) { Log.e(tag, message) }
}

internal actual fun rawDeflate(data: ByteArray): ByteArray? {
    return try {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // true = raw deflate, no headers
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}

internal actual fun inflateExact(compressed: ByteArray, originalSize: Int, nowrap: Boolean): ByteArray? {
    val inflater = Inflater(nowrap)
    return try {
        inflater.setInput(compressed)
        val output = ByteArray(originalSize)
        var written = 0
        while (written < originalSize) {
            val count = inflater.inflate(output, written, originalSize - written)
            if (count == 0) break
            written += count
        }
        if (written != originalSize) return null

        // One byte of room lets Inflater consume the end marker; any produced byte proves the
        // declared size was smaller than the actual expansion.
        val overflowProbe = ByteArray(1)
        if (inflater.inflate(overflowProbe) != 0) return null
        if (!inflater.finished() || inflater.remaining != 0) return null
        output
    } catch (e: DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

internal actual class PermitGate actual constructor(permits: Int) {
    private val semaphore = Semaphore(permits, true)

    actual fun tryAcquire(permits: Int, timeoutMs: Long): Boolean =
        try {
            semaphore.tryAcquire(permits, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    actual fun release(permits: Int) = semaphore.release(permits)

    actual val available: Int get() = semaphore.availablePermits()
}

internal actual fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()

actual class PlatformLock actual constructor() {
    private val delegate = java.util.concurrent.locks.ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()
}

actual typealias ConcurrentMap<K, V> = java.util.concurrent.ConcurrentHashMap<K, V>
