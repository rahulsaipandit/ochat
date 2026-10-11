package com.bitchat.android.noise

import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.withLock

/**
 * Transport-phase cipher with caller-managed counters (bitchat sends the counter on the wire).
 * Once destroyed it fails closed: the zeroed key is never used to encrypt or authenticate.
 */
class NoiseTransportCipher internal constructor(
    private val key: ByteArray,
    private val crypto: NoiseCrypto
) {
    val macLength: Int = MAC_LENGTH

    private val lock = PlatformLock()
    private var destroyed = false

    fun encrypt(counter: Long, plaintext: ByteArray): ByteArray = lock.withLock {
        check(!destroyed) { "Cipher destroyed" }
        crypto.seal(key, counter, EMPTY, plaintext)
    }

    fun decrypt(counter: Long, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < MAC_LENGTH) throw NoiseException("Ciphertext shorter than MAC")
        return lock.withLock {
            if (destroyed) throw NoiseException("Cipher destroyed")
            crypto.open(key, counter, EMPTY, ciphertext)
        }
    }

    fun destroy() {
        lock.withLock {
            destroyed = true
            key.fill(0)
        }
    }

    private companion object {
        const val MAC_LENGTH = 16
        val EMPTY = ByteArray(0)
    }
}

class NoiseTransportCiphers(val send: NoiseTransportCipher, val receive: NoiseTransportCipher)

/**
 * Noise_XX_25519_ChaChaPoly_SHA256 handshake (no prologue, no PSK), wire-compatible with the
 * Noise-Java implementation and the iOS client.
 *
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 */
class NoiseXXHandshake(
    private val isInitiator: Boolean,
    private val localStatic: NoiseKeyPair,
    private val crypto: NoiseCrypto = DefaultNoiseCrypto,
    private val newEphemeral: () -> NoiseKeyPair = { crypto.generateKeyPair() }
) {
    private var ck = ByteArray(HASH_LEN)
    private var h = ByteArray(HASH_LEN)
    private var k: ByteArray? = null
    private var n = 0L

    private var e: NoiseKeyPair? = null
    private var re: ByteArray? = null
    private var rs: ByteArray? = null
    private var step = 0
    private var failed = false

    val isComplete: Boolean get() = step == 3
    val remoteStaticPublicKey: ByteArray? get() = rs?.copyOf()
    val handshakeHash: ByteArray get() = h.copyOf()

    init {
        val name = PROTOCOL_NAME.encodeToByteArray()
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else crypto.sha256(name)
        ck = h.copyOf()
        mixHash(EMPTY) // empty prologue
    }

    /** True when the next action is to write (rather than read) a handshake message. */
    val isWriteTurn: Boolean get() = !isComplete && (step % 2 == 0) == isInitiator

    fun writeMessage(payload: ByteArray = EMPTY): ByteArray = guarded {
        if (!isWriteTurn) throw NoiseException("Not our turn to write")
        val out = ArrayList<ByteArray>()
        when (step) {
            0 -> { // -> e
                val eph = newEphemeral().also { e = it }
                out += eph.publicKey
                mixHash(eph.publicKey)
            }
            1 -> { // <- e, ee, s, es
                val eph = newEphemeral().also { e = it }
                out += eph.publicKey
                mixHash(eph.publicKey)
                mixKey(crypto.dh(eph.privateKey, need(re)))
                out += encryptAndHash(localStatic.publicKey)
                mixKey(crypto.dh(localStatic.privateKey, need(re)))
            }
            2 -> { // -> s, se
                out += encryptAndHash(localStatic.publicKey)
                mixKey(crypto.dh(localStatic.privateKey, need(re)))
            }
        }
        out += encryptAndHash(payload)
        step++
        concat(out)
    }

    fun readMessage(message: ByteArray): ByteArray = guarded {
        if (isWriteTurn || isComplete) throw NoiseException("Not our turn to read")
        var offset = 0
        fun take(len: Int): ByteArray {
            if (message.size - offset < len) throw NoiseException("Handshake message truncated")
            return message.copyOfRange(offset, offset + len).also { offset += len }
        }
        when (step) {
            0 -> { // -> e
                re = take(DH_LEN).also { mixHash(it) }
            }
            1 -> { // <- e, ee, s, es
                re = take(DH_LEN).also { mixHash(it) }
                mixKey(crypto.dh(need(e).privateKey, need(re)))
                rs = decryptAndHash(take(DH_LEN + MAC_LEN))
                mixKey(crypto.dh(need(e).privateKey, need(rs)))
            }
            2 -> { // -> s, se
                rs = decryptAndHash(take(DH_LEN + MAC_LEN))
                mixKey(crypto.dh(need(e).privateKey, need(rs)))
            }
        }
        val payload = decryptAndHash(message.copyOfRange(offset, message.size))
        step++
        payload
    }

    /** Derives the transport ciphers and wipes handshake secrets. Valid once [isComplete]. */
    fun split(): NoiseTransportCiphers {
        if (!isComplete || failed) throw NoiseException("Handshake not complete")
        val (k1, k2) = hkdf2(ck, EMPTY)
        val (send, receive) = if (isInitiator) k1 to k2 else k2 to k1
        destroy()
        return NoiseTransportCiphers(NoiseTransportCipher(send, crypto), NoiseTransportCipher(receive, crypto))
    }

    fun destroy() {
        ck.fill(0)
        k?.fill(0)
        k = null
        e?.destroy()
        e = null
    }

    // --- Noise SymmetricState / CipherState ---

    private fun mixHash(data: ByteArray) {
        h = crypto.sha256(h + data)
    }

    private fun mixKey(input: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, input)
        ck = newCk
        k = tempK
        n = 0
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val key = k
        val ct = if (key == null) plaintext else crypto.seal(key, n++, h, plaintext)
        mixHash(ct)
        return ct
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val key = k
        val pt = if (key == null) ciphertext else crypto.open(key, n, h, ciphertext).also { n++ }
        mixHash(ciphertext)
        return pt
    }

    private fun hkdf2(chainingKey: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = crypto.hmacSha256(chainingKey, input)
        val out1 = crypto.hmacSha256(temp, byteArrayOf(1))
        val out2 = crypto.hmacSha256(temp, out1 + byteArrayOf(2))
        return out1 to out2
    }

    private fun need(value: ByteArray?): ByteArray = value ?: throw NoiseException("Missing handshake key")
    private fun need(value: NoiseKeyPair?): NoiseKeyPair = value ?: throw NoiseException("Missing handshake key")

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** Any failure poisons the handshake: Noise state is undefined after a failed read or write. */
    private inline fun <T> guarded(block: () -> T): T {
        if (failed) throw NoiseException("Handshake already failed")
        try {
            return block()
        } catch (t: Throwable) {
            failed = true
            destroy()
            throw if (t is NoiseException) t else NoiseException("Handshake failed", t)
        }
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        private const val DH_LEN = 32
        private const val HASH_LEN = 32
        private const val MAC_LEN = 16
        private val EMPTY = ByteArray(0)
    }
}
