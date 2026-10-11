package com.bitchat.android.noise

import com.bitchat.android.identity.IdentityKeyStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseEncryptionServiceTest {

    private class MemoryKeyStore : IdentityKeyStore {
        var static: Pair<ByteArray, ByteArray>? = null
        var signing: Pair<ByteArray, ByteArray>? = null
        var cleared = 0

        override fun loadStaticKey() = static
        override fun saveStaticKey(privateKey: ByteArray, publicKey: ByteArray) { static = privateKey to publicKey }
        override fun loadSigningKey() = signing
        override fun saveSigningKey(privateKey: ByteArray, publicKey: ByteArray) { signing = privateKey to publicKey }
        override fun clearIdentityData() { static = null; signing = null; cleared++ }
    }

    private fun NoiseEncryptionService.peerID() = getIdentityFingerprint().take(16)

    private fun handshake(a: NoiseEncryptionService, b: NoiseEncryptionService) {
        val m1 = assertNotNull(a.initiateHandshake(b.peerID()))
        val m2 = assertNotNull(b.processHandshakeMessage(m1, a.peerID()))
        val m3 = assertNotNull(a.processHandshakeMessage(m2, b.peerID()))
        assertNull(b.processHandshakeMessage(m3, a.peerID()))
    }

    @Test
    fun twoDevicesHandshakeAndExchangeMessages() {
        val a = NoiseEncryptionService(MemoryKeyStore())
        val b = NoiseEncryptionService(MemoryKeyStore())
        handshake(a, b)

        assertTrue(a.hasEstablishedSession(b.peerID()))
        assertTrue(b.hasEstablishedSession(a.peerID()))
        assertContentEquals(b.getStaticPublicKeyData(), a.getPeerPublicKeyData(b.peerID()))
        assertEquals(b.getIdentityFingerprint(), a.getPeerFingerprint(b.peerID()))

        val ciphertext = assertNotNull(a.encrypt("hi bob".encodeToByteArray(), b.peerID()))
        assertEquals("hi bob", assertNotNull(b.decrypt(ciphertext, a.peerID())).decodeToString())
        // Replay of the same transport message is rejected.
        assertNull(b.decrypt(ciphertext, a.peerID()))
    }

    @Test
    fun identityPersistsAcrossInstancesAndIsRotatedByPanicWipe() {
        val store = MemoryKeyStore()
        val first = NoiseEncryptionService(store)
        val fingerprint = first.getIdentityFingerprint()
        val signingKey = first.getSigningPublicKeyData()

        val second = NoiseEncryptionService(store)
        assertEquals(fingerprint, second.getIdentityFingerprint())
        assertContentEquals(signingKey, second.getSigningPublicKeyData())

        second.clearPersistentIdentity()
        assertEquals(1, store.cleared)
        assertNotEquals(fingerprint, second.getIdentityFingerprint())
        assertFalse(second.getSigningPublicKeyData().contentEquals(signingKey))
        // The rotated identity was saved again.
        assertNotNull(store.static)
    }

    @Test
    fun signaturesVerifyAgainstTheSigningKey() {
        val a = NoiseEncryptionService(MemoryKeyStore())
        val data = "announce".encodeToByteArray()
        val signature = assertNotNull(a.signData(data))
        assertTrue(a.verifySignature(signature, data, a.getSigningPublicKeyData()))
        assertFalse(a.verifySignature(signature, "other".encodeToByteArray(), a.getSigningPublicKeyData()))
    }

    @Test
    fun sessionAuthenticationHookRunsOnlyAfterTheHandshakeCompletes() {
        val seen = mutableListOf<Pair<String, List<Byte>>>()
        val a = NoiseEncryptionService(MemoryKeyStore()) { peerID, key -> seen += peerID to key.toList() }
        val b = NoiseEncryptionService(MemoryKeyStore())
        val m1 = assertNotNull(a.initiateHandshake(b.peerID()))
        assertTrue(seen.isEmpty())
        val m2 = assertNotNull(b.processHandshakeMessage(m1, a.peerID()))
        a.processHandshakeMessage(m2, b.peerID())
        assertEquals(listOf(b.peerID() to b.getStaticPublicKeyData().toList()), seen)
    }

    @Test
    fun handshakeCompletingUnderAWrongClaimedPeerIdIsRejected() {
        val a = NoiseEncryptionService(MemoryKeyStore())
        val b = NoiseEncryptionService(MemoryKeyStore())
        val wrongID = "0000000000000000"
        // A believes it is talking to wrongID, but the responder's authenticated key derives to b.
        val m1 = assertNotNull(a.initiateHandshake(wrongID))
        val m2 = assertNotNull(b.processHandshakeMessage(m1, a.peerID()))
        assertNull(a.processHandshakeMessage(m2, wrongID))
        assertFalse(a.hasEstablishedSession(wrongID))
    }

    @Test
    fun channelEncryptionIsAvailableThroughTheService() {
        val a = NoiseEncryptionService(MemoryKeyStore())
        val b = NoiseEncryptionService(MemoryKeyStore())
        a.setChannelPassword("pw", "#c")
        b.setChannelPassword("pw", "#c")
        val sealed = assertNotNull(a.encryptChannelMessage("hello", "#c"))
        assertEquals("hello", b.decryptChannelMessage(sealed, "#c"))
    }
}
