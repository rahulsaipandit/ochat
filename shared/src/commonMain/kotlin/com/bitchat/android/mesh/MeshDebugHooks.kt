package com.bitchat.android.mesh

/** Debug controls and logging the mesh core consults; implemented by the app's debug settings. */
interface MeshDebugHooks {
    fun isPacketRelayEnabled(): Boolean

    fun logIncomingPacket(senderPeerID: String, senderNickname: String?, messageType: String, viaDeviceId: String?)
}

/** Registration point so shared mesh code never depends on UI classes. Unset means defaults. */
object MeshDebug {
    var provider: (() -> MeshDebugHooks?)? = null

    fun hooks(): MeshDebugHooks? = try {
        provider?.invoke()
    } catch (_: Exception) {
        null
    }
}
