package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory

/** Android implementation: app-private file storage and the process-wide live voice manager. */
class AndroidMessageHandlerPlatform(context: Context) : MessageHandlerPlatform {
    private val appContext = context.applicationContext

    override fun saveIncomingFile(file: BitchatFilePacket): String =
        FileUtils.saveIncomingFile(appContext, file)

    override fun absorbFinalizedVoiceNote(message: BitchatMessage): Boolean =
        LiveVoiceManager.getInstance(appContext).absorbFinalizedVoiceNote(message)

    override fun canonicalConversationId(peerOrConversationID: String): String =
        ContactDirectory.canonicalConversationId(peerOrConversationID)

    override fun onPeerFavoritedUs(noisePublicKey: ByteArray, isFavorite: Boolean) {
        FavoritesPersistenceService.shared.updatePeerFavoritedUs(noisePublicKey, isFavorite)
    }

    override fun updateNostrPublicKey(noisePublicKey: ByteArray, npub: String) {
        FavoritesPersistenceService.shared.updateNostrPublicKey(noisePublicKey, npub)
    }

    override fun updateNostrPublicKeyForPeerID(peerID: String, npub: String) {
        FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(peerID, npub)
    }

    override fun isFavorite(noisePublicKey: ByteArray): Boolean =
        FavoritesPersistenceService.shared.getFavoriteStatus(noisePublicKey)?.isFavorite == true

    override fun handleFrame(
        peerID: String,
        nickname: String,
        scope: LiveVoiceScope,
        payload: ByteArray,
        timestampMs: Long
    ): Boolean = LiveVoiceManager.getInstance(appContext).handleFrame(peerID, nickname, scope, payload, timestampMs)
}
