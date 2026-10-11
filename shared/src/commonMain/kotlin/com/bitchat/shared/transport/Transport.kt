package com.bitchat.shared.transport

import kotlinx.coroutines.flow.Flow

/**
 * Platform-neutral byte pipe the shared mesh core talks to. Implementations (Android BLE GATT,
 * Wi-Fi Aware, iOS CoreBluetooth) only move opaque bytes between links; they hold no routing,
 * crypto or protocol logic.
 */
interface Transport {
    val id: String

    /** Frames received from any link, tagged with the link they arrived on. */
    val incoming: Flow<InboundFrame>

    /** Broadcasts to every link and reports whether at least one write was accepted. */
    suspend fun broadcast(frame: ByteArray): Boolean

    /** Sends to one exact link generation. Transports that cannot prove link identity must return false. */
    suspend fun sendToLink(linkId: LinkId, frame: ByteArray): Boolean
}

/** Opaque, transport-scoped identity of one connection generation. Never reused for a new link. */
data class LinkId(val value: String)

class InboundFrame(val linkId: LinkId, val bytes: ByteArray)
