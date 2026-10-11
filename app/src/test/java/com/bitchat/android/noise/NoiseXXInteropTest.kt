package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.CipherState
import com.bitchat.android.noise.southernstorm.protocol.HandshakeState
import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The shared Kotlin Noise XX handshake against the Noise-Java implementation the app ships today.
 * Fixed keys prove byte-for-byte identical transcripts; random keys prove live interop both ways.
 */
class NoiseXXInteropTest {

    private val name = NoiseXXHandshake.PROTOCOL_NAME

    // Synthetic, non-secret test keys only.
    private fun priv(seed: Int) = ByteArray(32) { (seed + it * 7).toByte() }

    private fun keyPairFor(privateKey: ByteArray): NoiseKeyPair {
        val dh = Noise.createDH("25519")
        dh.setPrivateKey(privateKey, 0)
        val pub = ByteArray(32)
        dh.getPublicKey(pub, 0)
        return NoiseKeyPair(privateKey.copyOf(), pub)
    }

    private class JavaSide(val hs: HandshakeState) {
        var send: CipherState? = null
        var receive: CipherState? = null

        fun write(): ByteArray {
            val buf = ByteArray(512)
            return buf.copyOf(hs.writeMessage(buf, 0, null, 0, 0))
        }

        fun read(msg: ByteArray) {
            hs.readMessage(msg, 0, msg.size, ByteArray(512), 0)
        }

        fun finishIfSplit() {
            if (hs.action == HandshakeState.SPLIT) {
                val pair = hs.split()
                send = pair.sender
                receive = pair.receiver
            }
        }

        fun seal(counter: Long, plaintext: ByteArray): ByteArray {
            val out = ByteArray(plaintext.size + 16)
            send!!.setNonce(counter)
            return out.copyOf(send!!.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size))
        }

        fun open(counter: Long, ciphertext: ByteArray): ByteArray {
            val out = ByteArray(ciphertext.size)
            receive!!.setNonce(counter)
            return out.copyOf(receive!!.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size))
        }

        fun remoteStatic(): ByteArray = ByteArray(32).also { hs.remotePublicKey.getPublicKey(it, 0) }
    }

    private fun javaSide(role: Int, staticKey: NoiseKeyPair, fixedEphemeral: ByteArray?): JavaSide {
        val hs = HandshakeState(name, role)
        hs.localKeyPair.setPrivateKey(staticKey.privateKey, 0)
        if (fixedEphemeral != null) hs.fixedEphemeralKey.setPrivateKey(fixedEphemeral, 0)
        hs.start()
        return JavaSide(hs)
    }

    @Test
    fun fixedKeysProduceIdenticalTranscriptAndTransportCiphertext() {
        val iStatic = keyPairFor(priv(1))
        val rStatic = keyPairFor(priv(2))
        val iEph = priv(3)
        val rEph = priv(4)

        val ji = javaSide(HandshakeState.INITIATOR, iStatic, iEph)
        val jr = javaSide(HandshakeState.RESPONDER, rStatic, rEph)
        val ki = NoiseXXHandshake(true, iStatic) { keyPairFor(iEph) }
        val kr = NoiseXXHandshake(false, rStatic) { keyPairFor(rEph) }

        val m1j = ji.write(); val m1k = ki.writeMessage()
        assertArrayEquals("message 1", m1j, m1k)
        jr.read(m1j); kr.readMessage(m1k)

        val m2j = jr.write(); val m2k = kr.writeMessage()
        assertArrayEquals("message 2", m2j, m2k)
        ji.read(m2j); ki.readMessage(m2k)

        val m3j = ji.write(); val m3k = ki.writeMessage()
        assertArrayEquals("message 3", m3j, m3k)
        jr.read(m3j); kr.readMessage(m3k)

        ji.finishIfSplit(); jr.finishIfSplit()
        assertTrue(ki.isComplete && kr.isComplete)
        assertArrayEquals(rStatic.publicKey, ki.remoteStaticPublicKey)
        assertArrayEquals(iStatic.publicKey, kr.remoteStaticPublicKey)
        assertArrayEquals(ji.hs.handshakeHash, ki.handshakeHash)

        val kiCiphers = ki.split()
        val krCiphers = kr.split()
        val msg = "hello over the mesh".encodeToByteArray()
        for (counter in longArrayOf(0, 1, 5, 4_000_000_000L)) {
            assertArrayEquals("i->r $counter", ji.seal(counter, msg), kiCiphers.send.encrypt(counter, msg))
            assertArrayEquals("r->i $counter", jr.seal(counter, msg), krCiphers.send.encrypt(counter, msg))
        }
    }

    @Test
    fun kotlinInitiatorInteroperatesWithJavaResponder() {
        val iStatic = keyPairFor(priv(11))
        val rStatic = keyPairFor(priv(12))
        val k = NoiseXXHandshake(true, iStatic)
        val j = javaSide(HandshakeState.RESPONDER, rStatic, null)

        j.read(k.writeMessage())
        k.readMessage(j.write())
        j.read(k.writeMessage())
        j.finishIfSplit()

        assertTrue(k.isComplete)
        assertArrayEquals(rStatic.publicKey, k.remoteStaticPublicKey)
        assertArrayEquals(iStatic.publicKey, j.remoteStatic())
        val c = k.split()
        val msg = "ping".encodeToByteArray()
        assertArrayEquals(msg, j.open(7, c.send.encrypt(7, msg)))
        assertArrayEquals(msg, c.receive.decrypt(9, j.seal(9, msg)))
    }

    @Test
    fun javaInitiatorInteroperatesWithKotlinResponder() {
        val iStatic = keyPairFor(priv(21))
        val rStatic = keyPairFor(priv(22))
        val j = javaSide(HandshakeState.INITIATOR, iStatic, null)
        val k = NoiseXXHandshake(false, rStatic)

        k.readMessage(j.write())
        j.read(k.writeMessage())
        k.readMessage(j.write())
        j.finishIfSplit()

        assertTrue(k.isComplete)
        assertArrayEquals(iStatic.publicKey, k.remoteStaticPublicKey)
        assertArrayEquals(rStatic.publicKey, j.remoteStatic())
        val c = k.split()
        val msg = "pong".encodeToByteArray()
        assertArrayEquals(msg, j.open(3, c.send.encrypt(3, msg)))
        assertArrayEquals(msg, c.receive.decrypt(4, j.seal(4, msg)))
    }

    @Test
    fun tamperedHandshakeMessageIsRejectedAndPoisonsTheHandshake() {
        val i = NoiseXXHandshake(true, keyPairFor(priv(31)))
        val r = NoiseXXHandshake(false, keyPairFor(priv(32)))
        r.readMessage(i.writeMessage())
        val m2 = r.writeMessage()
        m2[40] = (m2[40].toInt() xor 1).toByte() // inside the encrypted static key
        try {
            i.readMessage(m2)
            fail("tampered message accepted")
        } catch (_: NoiseException) {
        }
        try {
            i.writeMessage()
            fail("handshake continued after failure")
        } catch (_: NoiseException) {
        }
    }

    @Test
    fun transportRejectsWrongCounterAndTamperedCiphertext() {
        val i = NoiseXXHandshake(true, keyPairFor(priv(41)))
        val r = NoiseXXHandshake(false, keyPairFor(priv(42)))
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        val ci = i.split()
        val cr = r.split()

        val sealed = ci.send.encrypt(0, "x".encodeToByteArray())
        assertEquals("x", cr.receive.decrypt(0, sealed).decodeToString())
        try { cr.receive.decrypt(1, sealed); fail("wrong counter accepted") } catch (_: NoiseException) {}
        val bad = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try { cr.receive.decrypt(0, bad); fail("tampered ciphertext accepted") } catch (_: NoiseException) {}
        try { cr.receive.decrypt(0, ByteArray(15)); fail("short ciphertext accepted") } catch (_: NoiseException) {}
    }
}
