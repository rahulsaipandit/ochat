package com.bitchat.android.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalStdlibApi::class)
class Ed25519Test {
    private fun hex(s: String) = s.hexToByteArray()

    private class Vector(val secret: String, val pub: String, val msg: String, val sig: String)

    // RFC 8032 section 7.1, tests 1 and 2.
    private val vectors = listOf(
        Vector(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        ),
        Vector(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
        )
    )

    @Test
    fun rfc8032VectorsDerivePublicKeySignAndVerify() {
        for (v in vectors) {
            val secret = hex(v.secret)
            assertContentEquals(hex(v.pub), Ed25519.publicKeyFromPrivate(secret))
            val signature = Ed25519.sign(secret, hex(v.msg))
            assertEquals(v.sig, signature.toHexString())
            assertTrue(Ed25519.verify(signature, hex(v.msg), hex(v.pub)))
        }
    }

    @Test
    fun generatedKeysSignAndVerify() {
        val pair = Ed25519.generateKeyPair()
        assertEquals(Ed25519.KEY_SIZE, pair.privateKey.size)
        assertContentEquals(pair.publicKey, Ed25519.publicKeyFromPrivate(pair.privateKey))
        val data = "announcement".encodeToByteArray()
        assertTrue(Ed25519.verify(Ed25519.sign(pair.privateKey, data), data, pair.publicKey))
    }

    @Test
    fun tamperedWrongKeyAndMalformedInputsAreRejectedWithoutThrowing() {
        val v = vectors[1]
        val sig = hex(v.sig)
        val msg = hex(v.msg)
        val pub = hex(v.pub)
        assertTrue(Ed25519.verify(sig, msg, pub))

        assertFalse(Ed25519.verify(sig, byteArrayOf(0x73), pub), "different message")
        assertFalse(Ed25519.verify(sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, msg, pub), "tampered signature")
        assertFalse(Ed25519.verify(sig, msg, hex(vectors[0].pub)), "wrong key")
        assertFalse(Ed25519.verify(sig.copyOf(63), msg, pub), "short signature")
        assertFalse(Ed25519.verify(sig, msg, pub.copyOf(31)), "short key")
        assertFalse(Ed25519.verify(ByteArray(0), msg, pub), "empty signature")
        assertFalse(Ed25519.verify(sig, msg, ByteArray(32)), "all-zero key")
    }
}
