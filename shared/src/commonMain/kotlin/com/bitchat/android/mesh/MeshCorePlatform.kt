package com.bitchat.android.mesh

import com.bitchat.android.model.DeliveryStatus

/** Everything the mesh core needs from the device beyond message handling. */
interface MeshCorePlatform : MessageHandlerPlatform {
    /** Persistence for Noise-authenticated peer state (identity key, capabilities). */
    val authenticatedPeerStateStore: AuthenticatedPeerStateStore

    /** Remembers that a read receipt for [messageID] was sent, so it is not repeated. */
    fun markReadReceiptSent(messageID: String)

    /** Publishes the peers reachable through [transportId] to app state. */
    fun setTransportPeers(transportId: String, ids: List<String>)

    /** Publishes the directly connected peers of [transportId]. */
    fun setTransportDirectPeers(transportId: String, ids: Collection<String>)

    /** Direct peers across all transports (the union that is gossiped to neighbours). */
    fun directPeers(): Set<String>

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus)

    fun buildVerifyChallenge(noiseKeyHex: String, nonceA: ByteArray): ByteArray

    fun buildVerifyResponse(noiseKeyHex: String, nonceA: ByteArray): ByteArray?
}
