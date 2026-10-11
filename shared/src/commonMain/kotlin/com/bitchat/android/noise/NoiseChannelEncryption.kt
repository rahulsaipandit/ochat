@file:OptIn(DelicateCryptographyApi::class)

package com.bitchat.android.noise

import com.bitchat.android.protocol.ConcurrentMap
import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.protocol.withLock
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Channel encryption for password-protected channels - 100% compatible with iOS implementation
 *
 * Uses PBKDF2 key derivation with channel name as salt and AES-256-GCM for encryption.
 * This is separate from Noise sessions and used for group channels with shared passwords.
 *
 * The former plaintext "channel key packet" helpers were unused and are intentionally not ported
 * (docs/security-review-jul-27.md, L4).
 */
class NoiseChannelEncryption {

    companion object {
        private const val TAG = "NoiseChannelEncryption"

        // PBKDF2 parameters (same as iOS)
        private const val PBKDF2_ITERATIONS = 100000
        private const val KEY_LENGTH_BYTES = 32 // 256-bit AES key
        private const val GCM_IV_BYTES = 12
        private const val MIN_ENCRYPTED_BYTES = 16 // 12 bytes IV + minimum ciphertext
    }

    private val provider get() = noiseCryptographyProvider()

    // Channel keys storage (channelName -> AES key)
    private val channelKeys = ConcurrentMap<String, ByteArray>()

    // Held while a key is used or wiped, so a key is never zeroed under a running encrypt/decrypt.
    private val keyLock = PlatformLock()

    // Channel passwords (for rekey operations)
    private val channelPasswords = ConcurrentMap<String, String>()

    // MARK: - Channel Password Management

    /**
     * Set password for a channel and derive encryption key
     */
    fun setChannelPassword(password: String, channel: String) {
        try {
            if (password.isEmpty()) {
                PlatformLog.w(TAG, "Empty password provided for channel $channel")
                return
            }

            // Derive key from password using PBKDF2 (same as iOS)
            val key = deriveChannelKey(password, channel)

            // Store key and password
            keyLock.withLock { channelKeys.put(channel, key)?.fill(0) }
            channelPasswords[channel] = password

            PlatformLog.d(TAG, "Set password for channel $channel")
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to set password for channel $channel: ${e.message}")
        }
    }

    /**
     * Remove password for a channel
     */
    fun removeChannelPassword(channel: String) {
        keyLock.withLock { channelKeys.remove(channel)?.fill(0) }
        channelPasswords.remove(channel)
        PlatformLog.d(TAG, "Removed password for channel $channel")
    }

    /**
     * Check if we have a key for a channel
     */
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }

    /**
     * Get channel password (if available)
     */
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }

    // MARK: - Encryption/Decryption

    /**
     * Encrypt a message for a channel
     * Returns IV (12 bytes) + ciphertext + 16-byte tag, the same layout as iOS
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")

        return try {
            val iv = CryptographyRandom.nextBytes(GCM_IV_BYTES)
            val sealed = keyLock.withLock { cipher(key).encryptWithIvBlocking(iv, message.encodeToByteArray()) }
            iv + sealed
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to encrypt channel message: ${e.message}")
            throw e
        }
    }

    /**
     * Decrypt a message for a channel
     * Expects data format: IV + encrypted_data + auth_tag
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String {
        val key = channelKeys[channel]
            ?: throw IllegalStateException("No key available for channel $channel")

        if (encryptedData.size < MIN_ENCRYPTED_BYTES) {
            throw IllegalArgumentException("Encrypted data too short")
        }

        return try {
            val iv = encryptedData.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = encryptedData.copyOfRange(GCM_IV_BYTES, encryptedData.size)
            keyLock.withLock { cipher(key).decryptWithIvBlocking(iv, ciphertext).decodeToString() }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to decrypt channel message: ${e.message}")
            throw e
        }
    }

    // MARK: - Key Derivation

    /**
     * Derive AES key from password using PBKDF2-HMAC-SHA256 (same parameters as iOS):
     * UTF-8 password, channel name as UTF-8 salt, 100,000 iterations, 256-bit output.
     */
    private fun deriveChannelKey(password: String, channel: String): ByteArray {
        try {
            return provider.get(PBKDF2)
                .secretDerivation(SHA256, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES.bytes, channel.encodeToByteArray())
                .deriveSecretToByteArrayBlocking(password.encodeToByteArray())
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to derive channel key: ${e.message}")
            throw e
        }
    }

    private fun cipher(key: ByteArray) = provider.get(AES.GCM).keyDecoder()
        .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key).cipher()

    // MARK: - Key Verification

    /**
     * Calculate key commitment (SHA-256 hash) for verification
     * This allows peers to verify they have the same key without revealing it
     */
    fun calculateKeyCommitment(channel: String): String? {
        val key = channelKeys[channel] ?: return null

        return try {
            keyLock.withLock { DefaultNoiseCrypto.sha256(key).toHexString() }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to calculate key commitment: ${e.message}")
            null
        }
    }

    /**
     * Verify key commitment matches our derived key
     */
    fun verifyKeyCommitment(channel: String, commitment: String): Boolean {
        val ourCommitment = calculateKeyCommitment(channel)
        return ourCommitment?.lowercase() == commitment.lowercase()
    }

    // MARK: - Debug and Management

    /**
     * Get debug information
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== Channel Encryption Debug ===")
        appendLine("Active channels: ${channelKeys.size}")

        channelKeys.keys.forEach { channel ->
            val hasPassword = channelPasswords.containsKey(channel)
            val commitment = calculateKeyCommitment(channel)?.take(16)
            appendLine("  $channel: hasPassword=$hasPassword, commitment=${commitment}...")
        }
    }

    /**
     * Get list of channels with keys
     */
    fun getActiveChannels(): Set<String> {
        return channelKeys.keys.toSet()
    }

    /**
     * Clear all channel data
     */
    fun clear() {
        keyLock.withLock {
            channelKeys.values.forEach { it.fill(0) }
            channelKeys.clear()
        }
        channelPasswords.clear()
        PlatformLog.d(TAG, "Cleared all channel encryption data")
    }
}
