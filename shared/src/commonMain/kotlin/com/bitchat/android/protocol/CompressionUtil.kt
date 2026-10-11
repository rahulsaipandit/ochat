package com.bitchat.android.protocol

/**
 * Compression utilities - 100% iOS-compatible zlib implementation
 * Uses the same zlib algorithm as iOS CompressionUtil.swift
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = ProtocolConstants.COMPRESSION_THRESHOLD_BYTES  // bytes - same as iOS

    private val decompressionPool = DecompressionResourcePool.forRuntime()
    
    /**
     * Helper to check if compression is worth it - exact same logic as iOS
     */
    fun shouldCompress(data: ByteArray): Boolean {
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD) return false
        
        // Simple entropy check - count unique bytes (exact same as iOS)
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }
        
        // If we have very high byte diversity, data is likely already compressed
        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }
    
    /**
     * Compress data using deflate algorithm - exact same as iOS
     * iOS COMPRESSION_ZLIB actually produces raw deflate data (no zlib headers)
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD) return null
        
        val compressedData = rawDeflate(data) ?: return null

        // Only return if compression was beneficial (same logic as iOS)
        return if (compressedData.isNotEmpty() && compressedData.size < data.size) compressedData else null
    }

    /**
     * Decompress deflate compressed data - exact same as iOS
     * iOS COMPRESSION_ZLIB produces raw deflate data (no headers)
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        if (!isValidRequest(compressedData, originalSize)) return null
        return withDecompressionResources(originalSize.toLong()) {
            decompressWithResourcesReserved(compressedData, originalSize)
        }
    }

    fun <T> withDecompressionResources(bytes: Long, block: () -> T): T? =
        decompressionPool.withReservation(bytes, block)

    /**
     * Inflate after the caller has reserved all packet-specific allocations.
     * This avoids nested acquisition when BinaryProtocol reserves both its input copy and output.
     */
    fun decompressWithResourcesReserved(
        compressedData: ByteArray,
        originalSize: Int
    ): ByteArray? {
        if (!isValidRequest(compressedData, originalSize)) return null
        return decompressExact(compressedData, originalSize)
    }

    private fun isValidRequest(compressedData: ByteArray, originalSize: Int): Boolean {
        val maxExpandedSize = ProtocolConstants.MAX_PAYLOAD_LENGTH
        if (compressedData.isEmpty()) {
            PlatformLog.w("CompressionUtil", "Refusing an empty compressed payload")
            return false
        }
        if (originalSize <= 0 || originalSize > maxExpandedSize) {
            PlatformLog.w(
                "CompressionUtil",
                "Refusing expanded payload size $originalSize outside 1..$maxExpandedSize"
            )
            return false
        }
        return true
    }

    private fun decompressExact(compressedData: ByteArray, originalSize: Int): ByteArray? {
        return if (looksLikeZlib(compressedData)) {
            // A raw stream can coincidentally begin with a valid-looking zlib header. The
            // header therefore only determines which format to try first; any failed or
            // non-exact zlib result must still fall back to raw under the same bounds.
            inflateExact(compressedData, originalSize, nowrap = false)
                ?: inflateExact(compressedData, originalSize, nowrap = true)
        } else {
            inflateExact(compressedData, originalSize, nowrap = true)
        }
    }

    /** RFC 1950 header check used to avoid speculative double inflation. */
    private fun looksLikeZlib(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        return (cmf and 0x0F) == 8 &&
            (cmf ushr 4) <= 7 &&
            ((cmf shl 8) or flg) % 31 == 0
    }

    /**
     * Test function to verify deflate compression works correctly
     * This can be called during app initialization to ensure compatibility
     */
    fun testCompression(): Boolean {
        try {
            // Create test data that should compress well (repeating pattern like iOS would use)
            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.encodeToByteArray()
            
            PlatformLog.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")
            
            // Test shouldCompress
            val shouldCompress = shouldCompress(originalData)
            PlatformLog.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")
            
            if (!shouldCompress) {
                PlatformLog.e("CompressionUtil", "shouldCompress failed for test data")
                return false
            }
            
            // Test compression
            val compressed = compress(originalData)
            if (compressed == null) {
                PlatformLog.e("CompressionUtil", "Compression failed")
                return false
            }
            
            PlatformLog.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")
            
            // Test decompression
            val decompressed = decompress(compressed, originalData.size)
            if (decompressed == null) {
                PlatformLog.e("CompressionUtil", "Decompression failed")
                return false
            }
            
            // Verify data integrity
            val isIdentical = originalData.contentEquals(decompressed)
            PlatformLog.d("CompressionUtil", "Data integrity check: $isIdentical")
            
            if (!isIdentical) {
                PlatformLog.e("CompressionUtil", "Decompressed data doesn't match original")
                return false
            }
            
            PlatformLog.i("CompressionUtil", "✅ deflate compression test PASSED - ready for iOS compatibility")
            return true
            
        } catch (e: Exception) {
            PlatformLog.e("CompressionUtil", "deflate compression test failed: ${e.message}")
            return false
        }
    }
}
