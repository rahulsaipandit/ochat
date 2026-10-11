package com.bitchat.android.mesh

import android.content.Context
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.VerificationService

/** Android implementation of [MeshCorePlatform], backed by app storage and preferences. */
class AndroidMeshCorePlatform(context: Context) :
    MeshCorePlatform,
    MessageHandlerPlatform by AndroidMessageHandlerPlatform(context) {

    private val appContext = context.applicationContext

    override val authenticatedPeerStateStore: AuthenticatedPeerStateStore = SecureAuthenticatedPeerStateStore(appContext)

    override fun markReadReceiptSent(messageID: String) {
        SeenMessageStore.getInstance(appContext).markReadReceiptSent(messageID)
    }

    override fun setTransportPeers(transportId: String, ids: List<String>) =
        AppStateStore.setTransportPeers(transportId, ids)

    override fun setTransportDirectPeers(transportId: String, ids: Collection<String>) =
        AppStateStore.setTransportDirectPeers(transportId, ids)

    override fun directPeers(): Set<String> = AppStateStore.getDirectPeers()

    override fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) =
        AppStateStore.updatePrivateMessageStatus(messageID, status)

    override fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray =
        VerificationService.buildVerifyChallenge(noiseKeyHex, nonceA)

    override fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray? =
        VerificationService.buildVerifyResponse(noiseKeyHex, nonceA)
}
