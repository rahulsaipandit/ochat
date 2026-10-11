package com.bitchat.android.crypto

import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.noise.NoiseHandshakeProcessingResult
import com.bitchat.android.noise.NoiseSession

/** What the mesh security layer needs from the device's encryption service. */
interface MeshEncryption {
    fun processHandshakeMessageWithResult(data: ByteArray, peerID: String): NoiseHandshakeProcessingResult

    fun sign(data: ByteArray): ByteArray

    fun encrypt(data: ByteArray, peerID: String): ByteArray

    fun encryptForSession(data: ByteArray, peerID: String, expectedSession: AuthenticatedNoiseSession): ByteArray

    fun decryptWithSession(data: ByteArray, peerID: String): NoiseDecryptionResult

    fun getCombinedPublicKeyData(): ByteArray

    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean

    fun hasEstablishedSession(peerID: String): Boolean

    fun clearPersistentIdentity()

    fun getIdentityFingerprint(): String

    fun getSessionState(peerID: String): NoiseSession.NoiseSessionState

    fun getSigningPublicKey(): ByteArray?

    fun getStaticPublicKey(): ByteArray?

    fun initiateHandshake(peerID: String, replaceEstablished: Boolean = false): ByteArray?

    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray?

    fun removePeer(peerID: String)

    fun signData(data: ByteArray): ByteArray?

    fun getAuthenticatedSession(peerID: String): AuthenticatedNoiseSession?

    fun withAuthenticatedSession(peerID: String, expectedSession: AuthenticatedNoiseSession, action: () -> Boolean): Boolean
}
