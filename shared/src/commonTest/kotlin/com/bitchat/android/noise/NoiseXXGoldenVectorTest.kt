package com.bitchat.android.noise

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Golden transcript for Noise_XX_25519_ChaChaPoly_SHA256 captured from the Noise-Java implementation
 * (see NoiseXXInteropTest in :app). It runs on every target, so the iOS provider must reproduce the
 * same bytes as the Android one. Keys are synthetic test values.
 */
@OptIn(ExperimentalStdlibApi::class)
class NoiseXXGoldenVectorTest {
    private fun priv(seed: Int) = ByteArray(32) { (seed + it * 7).toByte() }
    private fun hex(s: String) = s.hexToByteArray()

    private val iStatic = NoiseKeyPair(priv(1), hex("c8feca81be196cdf2cadeabf13c4903d7632dce4955aa68b6e5d9adef54e2616"))
    private val rStatic = NoiseKeyPair(priv(2), hex("73e79971c9110029723632a80b707bf4f62c125763346e1e8718d6c0dcc3aa3a"))
    private val iEph = NoiseKeyPair(priv(3), hex("bb50ff9e82a574cfbf820e97f60fb9c143ec7415cf514f8cfd98eff59e059614"))
    private val rEph = NoiseKeyPair(priv(4), hex("f74e5bb7515d565274967af75d88497dc6fc7fcd607da9dd91a617db86ad0110"))

    @Test
    fun transcriptMatchesNoiseJava() {
        val i = NoiseXXHandshake(true, iStatic) { iEph }
        val r = NoiseXXHandshake(false, rStatic) { rEph }

        val m1 = i.writeMessage()
        assertContentEquals(hex("bb50ff9e82a574cfbf820e97f60fb9c143ec7415cf514f8cfd98eff59e059614"), m1)
        r.readMessage(m1)

        val m2 = r.writeMessage()
        assertContentEquals(hex("f74e5bb7515d565274967af75d88497dc6fc7fcd607da9dd91a617db86ad0110162e79d6d6d72cd693d78b056d3785d7ced9989b141e657fb868a69263e9f37b2c2e1a08c07f07e2caa2c3dcd8fcae84bd8bde85a917a52180aa60f681b2021e"), m2)
        i.readMessage(m2)

        val m3 = i.writeMessage()
        assertContentEquals(hex("c04826cdd24da4062b7931f15c18c5a7e5d3c54bf168f612eecdfc4fa5fb27a3ed3bf34c3cd90619c0b7b9658395304ecdaf0485513cf7be325b13a309f32759"), m3)
        r.readMessage(m3)

        assertTrue(i.isComplete && r.isComplete)
        assertContentEquals(hex("f6d0616f77192b8d01cfce57db0ee3343ff3a0320d473194126cf8702d23e8a6"), i.handshakeHash)
        assertContentEquals(hex("f6d0616f77192b8d01cfce57db0ee3343ff3a0320d473194126cf8702d23e8a6"), r.handshakeHash)
        assertContentEquals(rStatic.publicKey, i.remoteStaticPublicKey)
        assertContentEquals(iStatic.publicKey, r.remoteStaticPublicKey)

        val ci = i.split()
        val cr = r.split()
        val msg = "hello over the mesh".encodeToByteArray()
        assertContentEquals(hex("22146eb4935b84866db32c0cd3d2b34bbcb736a6fa3b9ae5f117fcee07c8fec23cbb38"), ci.send.encrypt(0, msg))
        assertContentEquals(hex("371f021c173ca5d933c264aaa452f15eb0b49e57aa5ae03866f5a06b6e4cee0e7a7b4f"), cr.send.encrypt(5, msg))
        assertContentEquals(msg, cr.receive.decrypt(0, hex("22146eb4935b84866db32c0cd3d2b34bbcb736a6fa3b9ae5f117fcee07c8fec23cbb38")))
        assertContentEquals(msg, ci.receive.decrypt(5, hex("371f021c173ca5d933c264aaa452f15eb0b49e57aa5ae03866f5a06b6e4cee0e7a7b4f")))
    }
}
