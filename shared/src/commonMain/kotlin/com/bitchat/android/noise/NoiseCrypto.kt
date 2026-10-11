@file:OptIn(DelicateCryptographyApi::class)

package com.bitchat.android.noise

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * Provider used for Noise primitives. Android uses BouncyCastle explicitly (the platform lacks
 * X25519/Ed25519 below API 33 and ChaCha20-Poly1305 below API 28) without touching the global JCA
 * provider order; Apple targets use the platform provider.
 */
internal expect fun noiseCryptographyProvider(): CryptographyProvider

class NoiseKeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    fun destroy() = privateKey.fill(0)
}

class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Primitives for Noise_XX_25519_ChaChaPoly_SHA256. Everything the handshake needs from a crypto
 * library sits behind this seam so tests can inject deterministic keys.
 */
interface NoiseCrypto {
    fun generateKeyPair(): NoiseKeyPair
    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** ChaCha20-Poly1305 with the Noise nonce: 4 zero bytes followed by the 64-bit counter, little-endian. */
    fun seal(key: ByteArray, counter: Long, ad: ByteArray, plaintext: ByteArray): ByteArray
    fun open(key: ByteArray, counter: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray
}

/** [NoiseCrypto] backed by cryptography-kotlin (JCA/BouncyCastle on Android, platform crypto on Apple). */
object DefaultNoiseCrypto : NoiseCrypto {
    private val provider: CryptographyProvider by lazy { noiseCryptographyProvider() }

    override fun generateKeyPair(): NoiseKeyPair {
        val pair = provider.get(XDH).keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
        return NoiseKeyPair(
            pair.privateKey.encodeToByteArrayBlocking(XDH.PrivateKey.Format.RAW),
            pair.publicKey.encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW)
        )
    }

    override fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val xdh = provider.get(XDH)
        val priv = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)
        val pub = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, publicKey)
        val shared = priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(pub)
        // Noise requires rejecting the all-zero output of low-order points.
        if (shared.all { it == 0.toByte() }) throw NoiseException("Invalid DH public key")
        return shared
    }

    override fun sha256(data: ByteArray): ByteArray = provider.get(SHA256).hasher().hashBlocking(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val k = provider.get(HMAC).keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        return k.signatureGenerator().generateSignatureBlocking(data)
    }

    override fun seal(key: ByteArray, counter: Long, ad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(key).encryptWithIvBlocking(nonce(counter), plaintext, ad)

    override fun open(key: ByteArray, counter: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        try {
            cipher(key).decryptWithIvBlocking(nonce(counter), ciphertext, ad)
        } catch (e: Exception) {
            throw NoiseException("Authentication failed", e)
        }

    private fun cipher(key: ByteArray) = provider.get(ChaCha20Poly1305).keyDecoder()
        .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key).cipher()

    private fun nonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }
}
