package com.bitchat.android.nostr

import com.bitchat.android.nostr.NostrJson.asLenientInt
import com.bitchat.android.nostr.NostrJson.asLenientString
import com.bitchat.android.nostr.NostrJson.asObject
import com.bitchat.android.protocol.nowMillis
import com.bitchat.android.protocol.sha256
import com.bitchat.android.util.toHexString

/**
 * Nostr Event structure following NIP-01
 * Compatible with iOS implementation
 */
data class NostrEvent(
    var id: String = "",
    val pubkey: String,
    val createdAt: Int,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    var sig: String? = null
) {

    companion object {
        /**
         * Create from a decoded JSON dictionary
         */
        fun fromJson(json: Map<String, Any>): NostrEvent? {
            return try {
                @Suppress("UNCHECKED_CAST")
                NostrEvent(
                    id = json["id"] as? String ?: "",
                    pubkey = json["pubkey"] as? String ?: return null,
                    createdAt = (json["created_at"] as? Number)?.toInt() ?: return null,
                    kind = (json["kind"] as? Number)?.toInt() ?: return null,
                    tags = (json["tags"] as? List<List<String>>) ?: return null,
                    content = json["content"] as? String ?: return null,
                    sig = json["sig"] as? String?
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create from a JSON string. Returns null unless pubkey, created_at, kind, tags and content
         * are all present (the Gson-based parser used to return an object with null fields).
         */
        fun fromJsonString(jsonString: String): NostrEvent? {
            return try {
                val obj = NostrJson.parse(jsonString)?.asObject() ?: return null
                NostrEvent(
                    id = obj["id"]?.asLenientString() ?: "",
                    pubkey = obj["pubkey"]?.asLenientString() ?: return null,
                    createdAt = obj["created_at"]?.asLenientInt() ?: return null,
                    kind = obj["kind"]?.asLenientInt() ?: return null,
                    tags = NostrJson.parseTags(obj["tags"] ?: return null),
                    content = obj["content"]?.asLenientString() ?: return null,
                    sig = obj["sig"]?.asLenientString()
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Create a new text note event
         */
        fun createTextNote(
            content: String,
            publicKeyHex: String,
            privateKeyHex: String,
            tags: List<List<String>> = emptyList(),
            createdAt: Int = (nowMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.TEXT_NOTE,
                tags = tags,
                content = content
            )
            return event.sign(privateKeyHex)
        }

        /**
         * Create a new metadata event (kind 0)
         */
        fun createMetadata(
            metadata: String,
            publicKeyHex: String,
            privateKeyHex: String,
            createdAt: Int = (nowMillis() / 1000).toInt()
        ): NostrEvent {
            val event = NostrEvent(
                pubkey = publicKeyHex,
                createdAt = createdAt,
                kind = NostrKind.METADATA,
                tags = emptyList(),
                content = metadata
            )
            return event.sign(privateKeyHex)
        }
    }

    /**
     * Sign event with secp256k1 private key
     * Returns signed event with id and signature set
     */
    fun sign(privateKeyHex: String): NostrEvent {
        val (eventId, eventIdHash) = calculateEventId()

        // Create signature using secp256k1
        val signature = signHash(eventIdHash, privateKeyHex)

        return this.copy(
            id = eventId,
            sig = signature
        )
    }

    /**
     * Compute event ID (NIP-01) without signing
     */
    fun computeEventIdHex(): String {
        val (eventId, _) = calculateEventId()
        return eventId
    }

    /**
     * Calculate event ID according to NIP-01
     * Returns (hex_id, hash_bytes)
     */
    private fun calculateEventId(): Pair<String, ByteArray> {
        // [0, pubkey, created_at, kind, tags, content] in compact form
        val serialized = StringBuilder()
        serialized.append("[0,")
        NostrJson.quote(serialized, pubkey)
        serialized.append(',').append(createdAt).append(',').append(kind).append(',')
        NostrJson.writeTags(serialized, tags)
        serialized.append(',')
        NostrJson.quote(serialized, content)
        serialized.append(']')

        val hash = sha256(NostrJson.utf8(serialized.toString()))
        return Pair(hash.toHexString(), hash)
    }

    /**
     * Sign hash using BIP-340 Schnorr signatures
     */
    private fun signHash(hash: ByteArray, privateKeyHex: String): String {
        return try {
            NostrCrypto.schnorrSign(hash, privateKeyHex)
        } catch (e: Exception) {
            throw RuntimeException("Failed to sign event: ${e.message}", e)
        }
    }

    /**
     * Convert to JSON string. A null signature is omitted.
     */
    fun toJsonString(): String {
        val out = StringBuilder()
        writeJson(out)
        return out.toString()
    }

    internal fun writeJson(out: StringBuilder) {
        out.append("{\"id\":")
        NostrJson.quote(out, id)
        out.append(",\"pubkey\":")
        NostrJson.quote(out, pubkey)
        out.append(",\"created_at\":").append(createdAt)
        out.append(",\"kind\":").append(kind)
        out.append(",\"tags\":")
        NostrJson.writeTags(out, tags)
        out.append(",\"content\":")
        NostrJson.quote(out, content)
        sig?.let {
            out.append(",\"sig\":")
            NostrJson.quote(out, it)
        }
        out.append('}')
    }

    /**
     * Validate event signature using BIP-340 Schnorr verification
     */
    fun isValidSignature(): Boolean {
        return try {
            val signatureHex = sig ?: return false
            if (id.isEmpty() || pubkey.isEmpty()) return false

            // Recalculate the event ID hash for verification
            val (calculatedId, messageHash) = calculateEventId()

            // Check if the calculated ID matches the stored ID
            if (calculatedId != id) return false

            // Verify the Schnorr signature
            NostrCrypto.schnorrVerify(messageHash, signatureHex, pubkey)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate event structure and signature
     */
    fun isValid(): Boolean {
        return try {
            // Basic field validation
            if (pubkey.isEmpty() || content.isEmpty()) return false
            if (createdAt <= 0 || kind < 0) return false
            if (!NostrCrypto.isValidPublicKey(pubkey)) return false

            // Signature validation
            isValidSignature()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Nostr event kinds
 */
object NostrKind {
    const val METADATA = 0
    const val TEXT_NOTE = 1
    const val DIRECT_MESSAGE = 14     // NIP-17 direct message (unsigned)
    const val FILE_MESSAGE = 15       // NIP-17 file message (unsigned)
    const val SEAL = 13              // NIP-17 sealed event
    const val GIFT_WRAP = 1059       // NIP-17 gift wrap
    const val EPHEMERAL_EVENT = 20000 // For geohash channels
    const val GEOHASH_PRESENCE = 20001 // For geohash presence heartbeat
}
