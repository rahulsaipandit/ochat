package com.bitchat.android.nostr

import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.protocol.nowMillis
import com.bitchat.android.protocol.sha256

/**
 * Nostr identity (secp256k1 keypair) for NIP-17 private messaging
 * Compatible with iOS implementation
 */
data class NostrIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val npub: String,
    val createdAt: Long
) {

    companion object {
        private const val TAG = "NostrIdentity"

        /**
         * Generate a new Nostr identity
         */
        fun generate(): NostrIdentity {
            val (privateKeyHex, publicKeyHex) = NostrCrypto.generateKeyPair()
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())

            PlatformLog.d(TAG, "Generated new Nostr identity: npub=$npub")

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = nowMillis()
            )
        }

        /**
         * Create from existing private key
         */
        fun fromPrivateKey(privateKeyHex: String): NostrIdentity {
            require(NostrCrypto.isValidPrivateKey(privateKeyHex)) {
                "Invalid private key"
            }

            val publicKeyHex = NostrCrypto.derivePublicKey(privateKeyHex)
            val npub = Bech32.encode("npub", publicKeyHex.hexToByteArray())

            return NostrIdentity(
                privateKeyHex = privateKeyHex,
                publicKeyHex = publicKeyHex,
                npub = npub,
                createdAt = nowMillis()
            )
        }

        /**
         * Create from a deterministic seed (for demo purposes)
         */
        fun fromSeed(seed: String): NostrIdentity {
            // Hash the seed to create a private key
            return fromPrivateKey(sha256(seed.encodeToByteArray()).toHexString())
        }
    }

    /**
     * Sign a Nostr event
     */
    fun signEvent(event: NostrEvent): NostrEvent {
        return event.sign(privateKeyHex)
    }

    /**
     * Get short display format
     */
    fun getShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.take(8)}...${npub.takeLast(8)}"
        } else {
            npub
        }
    }
}
