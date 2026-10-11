@file:OptIn(DelicateCryptographyApi::class)

package com.bitchat.android.crypto

import com.bitchat.android.noise.noiseCryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.EdDSA

/**
 * Ed25519 identity signing key. [privateKey] is the 32-byte RFC 8032 seed, the same encoding the
 * app has always persisted, so existing stored keys keep working.
 */
class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/** Ed25519 (RFC 8032) signatures for announcements and signed packets. */
object Ed25519 {
    const val KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    private val eddsa get() = noiseCryptographyProvider().get(EdDSA)

    fun generateKeyPair(): Ed25519KeyPair {
        val pair = eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking()
        return Ed25519KeyPair(
            pair.privateKey.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.RAW),
            pair.publicKey.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)
        )
    }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "Ed25519 private key must be $KEY_SIZE bytes" }
        return decodePrivate(privateKey).getPublicKeyBlocking().encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)
    }

    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "Ed25519 private key must be $KEY_SIZE bytes" }
        return decodePrivate(privateKey).signatureGenerator().generateSignatureBlocking(data)
    }

    /** False for any malformed key or signature, never an exception. */
    fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val key = eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
                .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, publicKey)
            key.signatureVerifier().tryVerifySignatureBlocking(data, signature)
        } catch (e: Exception) {
            false
        }
    }

    private fun decodePrivate(privateKey: ByteArray) = eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
        .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, privateKey)
}
