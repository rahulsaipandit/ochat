package com.bitchat.android.nostr

import com.bitchat.android.noise.DefaultNoiseCrypto
import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.protocol.nowMillis
import com.bitchat.android.util.toHexString
import dev.whyoleg.cryptography.random.CryptographyRandom
import fr.acinq.secp256k1.Secp256k1
import kotlin.io.encoding.Base64

/**
 * Cryptographic utilities for Nostr: secp256k1 keys, BIP-340 Schnorr signatures and the
 * bitchat NIP-44 v2 variant (XChaCha20-Poly1305 keyed by HKDF over the compressed ECDH point,
 * wire-compatible with the iOS client).
 */
object NostrCrypto {

    const val NIP17_DEFAULT_MAX_PAST_SECONDS = 79_200

    private const val TAG = "NostrCrypto"
    private const val NIP44_PREFIX = "v2:"

    /** Generate a secp256k1 key pair. Returns (privateKeyHex, x-only publicKeyHex). */
    fun generateKeyPair(): Pair<String, String> {
        var privateKey: ByteArray
        do {
            privateKey = CryptographyRandom.nextBytes(32)
        } while (!Secp256k1.secKeyVerify(privateKey))
        return Pair(privateKey.toHexString(), xOnly(Secp256k1.pubkeyCreate(privateKey)).toHexString())
    }

    /** Derive the x-only public key (32 bytes hex) from a private key. */
    fun derivePublicKey(privateKeyHex: String): String {
        val privateKey = privateKeyHex.hexToByteArray()
        require(Secp256k1.secKeyVerify(privateKey)) { "Invalid private key" }
        return xOnly(Secp256k1.pubkeyCreate(privateKey)).toHexString()
    }

    /**
     * HKDF-SHA256 with an empty salt and info = "nip44-v2", 32 bytes out. An empty salt is a
     * block of zero bytes to HMAC, so the zero key below is the RFC 5869 extract step.
     */
    fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        val prk = DefaultNoiseCrypto.hmacSha256(ByteArray(32), sharedSecret)
        return DefaultNoiseCrypto.hmacSha256(prk, "nip44-v2".encodeToByteArray() + byteArrayOf(0x01))
    }

    /** Output format: "v2:" + base64url(nonce24 || ciphertext || tag), no padding. */
    fun encryptNIP44(plaintext: String, recipientPublicKeyHex: String, senderPrivateKeyHex: String): String {
        try {
            val secretMaterial = sharedPoint(
                senderPrivateKeyHex.hexToByteArray(),
                recipientPublicKeyHex.hexToByteArray(),
                oddY = false
            )
            val sealed = XChaCha20Poly1305.seal(deriveNIP44Key(secretMaterial), plaintext.encodeToByteArray())
            val encoded = base64UrlNoPad(sealed)
            PlatformLog.d(TAG, "NIP44 v2 encrypt: len=${encoded.length}")
            return NIP44_PREFIX + encoded
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 encryption failed: ${e.message}", e)
        }
    }

    /** Accepts only the exact "v2:" format. An x-only sender key has no parity, so both lifts are tried. */
    fun decryptNIP44(ciphertext: String, senderPublicKeyHex: String, recipientPrivateKeyHex: String): String {
        try {
            require(ciphertext.startsWith(NIP44_PREFIX)) { "Invalid NIP-44 version prefix" }
            val encryptedData = base64UrlDecode(ciphertext.substring(NIP44_PREFIX.length))
                ?: throw IllegalArgumentException("Invalid base64url payload")
            val privateKey = recipientPrivateKeyHex.hexToByteArray()
            val senderX = senderPublicKeyHex.hexToByteArray()

            var lastError: Exception? = null
            for (oddY in listOf(false, true)) {
                try {
                    val key = deriveNIP44Key(sharedPoint(privateKey, senderX, oddY))
                    return XChaCha20Poly1305.open(key, encryptedData).decodeToString()
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: RuntimeException("NIP-44 v2 decryption failed")
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 decryption failed: ${e.message}", e)
        }
    }

    /** Random timestamp within +-15 minutes of [baseTimestamp] (epoch seconds). */
    fun randomizeTimestamp(baseTimestamp: Long = nowMillis() / 1000): Int {
        val offset = secureInt(1800) - 900
        return (baseTimestamp + offset).toInt()
    }

    /** Random timestamp in the past; the default leaves 2 hours of slack inside iOS's 24-hour lookback. */
    fun randomizeTimestampUpToPast(maxPastSeconds: Int = NIP17_DEFAULT_MAX_PAST_SECONDS): Int {
        val now = (nowMillis() / 1000).toInt()
        val offset = if (maxPastSeconds > 0) secureInt(maxPastSeconds + 1) else 0
        return now - offset
    }

    fun isValidPrivateKey(privateKeyHex: String): Boolean = try {
        val bytes = privateKeyHex.hexToByteArray()
        bytes.size == 32 && Secp256k1.secKeyVerify(bytes)
    } catch (e: Exception) {
        false
    }

    /** True when the 32-byte x coordinate lies on the curve. */
    fun isValidPublicKey(publicKeyHex: String): Boolean = try {
        val bytes = publicKeyHex.hexToByteArray()
        bytes.size == 32 && lift(bytes, oddY = false) != null
    } catch (e: Exception) {
        false
    }

    /** BIP-340 Schnorr signature over a 32-byte hash. Returns r || s as 128 hex characters. */
    fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }
        val privateKey = privateKeyHex.hexToByteArray()
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        require(Secp256k1.secKeyVerify(privateKey)) { "Invalid private key" }
        return Secp256k1.signSchnorr(messageHash, privateKey, CryptographyRandom.nextBytes(32)).toHexString()
    }

    fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean = try {
        val signature = signatureHex.hexToByteArray()
        val publicKey = publicKeyHex.hexToByteArray()
        messageHash.size == 32 && signature.size == 64 && publicKey.size == 32 &&
            Secp256k1.verifySchnorr(signature, messageHash, publicKey)
    } catch (e: Exception) {
        false
    }

    /** Compressed (33-byte) point privateKey * lift(peerX), with the peer's y parity chosen by [oddY]. */
    private fun sharedPoint(privateKey: ByteArray, peerX: ByteArray, oddY: Boolean): ByteArray {
        val peer = lift(peerX, oddY) ?: throw IllegalArgumentException("Invalid public key")
        return Secp256k1.pubKeyCompress(Secp256k1.pubKeyTweakMul(peer, privateKey))
    }

    /** Compressed point for an x-only key with the requested y parity, or null if x is not on the curve. */
    private fun lift(xOnly: ByteArray, oddY: Boolean): ByteArray? {
        require(xOnly.size == 32) { "X-only public key must be 32 bytes" }
        val compressed = byteArrayOf(if (oddY) 0x03 else 0x02) + xOnly
        return try {
            Secp256k1.pubkeyParse(compressed)
            compressed
        } catch (e: Exception) {
            null
        }
    }

    private fun xOnly(publicKey: ByteArray): ByteArray = Secp256k1.pubKeyCompress(publicKey).copyOfRange(1, 33)

    private fun base64UrlNoPad(data: ByteArray): String =
        Base64.encode(data).replace('+', '-').replace('/', '_').trimEnd('=')

    private fun base64UrlDecode(s: String): ByteArray? {
        var standard = s.replace('-', '+').replace('_', '/')
        standard += "=".repeat((4 - standard.length % 4) % 4)
        return try {
            Base64.decode(standard)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Uniform value in [0, bound) from the platform CSPRNG, by rejection sampling. */
    private fun secureInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val limit = (1L shl 31) - ((1L shl 31) % bound)
        while (true) {
            val b = CryptographyRandom.nextBytes(4)
            val v = (((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
                ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)) and 0x7fffffffL
            if (v < limit) return (v % bound).toInt()
        }
    }
}
