package com.bitchat.android.mesh

import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType

/** Device services the message handler needs: incoming-file storage and live voice assembly. */
interface MessageHandlerPlatform {
    /** Stores a received file and returns the path or URI to show in the message. */
    fun saveIncomingFile(file: BitchatFilePacket): String

    /** True when [message] completed a live voice burst that is already being played or shown. */
    fun absorbFinalizedVoiceNote(message: BitchatMessage): Boolean

    /** Conversation id used to mirror notices into the private chat with [peerOrConversationID]. */
    fun canonicalConversationId(peerOrConversationID: String): String

    /** The peer told us they favorited (or unfavorited) us; the platform persists it. */
    fun onPeerFavoritedUs(noisePublicKey: ByteArray, isFavorite: Boolean)

    fun updateNostrPublicKey(noisePublicKey: ByteArray, npub: String)

    fun updateNostrPublicKeyForPeerID(peerID: String, npub: String)

    /** Whether we have favorited the peer with [noisePublicKey]. */
    fun isFavorite(noisePublicKey: ByteArray): Boolean

    fun messageTypeForMime(mime: String): BitchatMessageType =
        when {
            mime.lowercase().startsWith("image/") -> BitchatMessageType.Image
            mime.lowercase().startsWith("audio/") -> BitchatMessageType.Audio
            else -> BitchatMessageType.File
        }

    fun handleFrame(
        peerID: String,
        nickname: String,
        scope: LiveVoiceScope,
        payload: ByteArray,
        timestampMs: Long
    ): Boolean
}
