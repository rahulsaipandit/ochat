package com.bitchat.android.nostr

import com.bitchat.android.noise.DefaultNoiseCrypto
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha): HChaCha20 derives a subkey from the first 16 nonce
 * bytes, then ChaCha20-Poly1305 runs with 4 zero bytes followed by the last 8 nonce bytes.
 * Sealed layout is `nonce(24) || ciphertext || tag(16)`, the same layout Tink produces.
 */
internal object XChaCha20Poly1305 {
    const val NONCE_BYTES = 24
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private val NO_AD = ByteArray(0)

    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = CryptographyRandom.nextBytes(NONCE_BYTES)
        return nonce + DefaultNoiseCrypto.sealWithNonce(subKey(key, nonce), tailNonce(nonce), NO_AD, plaintext)
    }

    fun open(key: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= NONCE_BYTES + TAG_BYTES) { "Ciphertext too short" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        return DefaultNoiseCrypto.openWithNonce(
            subKey(key, nonce),
            tailNonce(nonce),
            NO_AD,
            sealed.copyOfRange(NONCE_BYTES, sealed.size)
        )
    }

    private fun subKey(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Key must be 32 bytes" }
        return hChaCha20(key, nonce.copyOfRange(0, 16))
    }

    private fun tailNonce(nonce: ByteArray): ByteArray = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }

    /** HChaCha20: 20 ChaCha rounds over (constants, key, nonce16); output words 0-3 and 12-15, no feed-forward. */
    internal fun hChaCha20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = leInt(key, i * 4)
        for (i in 0 until 4) s[12 + i] = leInt(nonce16, i * 4)
        repeat(10) {
            quarter(s, 0, 4, 8, 12)
            quarter(s, 1, 5, 9, 13)
            quarter(s, 2, 6, 10, 14)
            quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15)
            quarter(s, 1, 6, 11, 12)
            quarter(s, 2, 7, 8, 13)
            quarter(s, 3, 4, 9, 14)
        }
        val words = intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15])
        val out = ByteArray(32)
        for (i in words.indices) for (b in 0 until 4) out[i * 4 + b] = (words[i] ushr (8 * b)).toByte()
        return out
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
