@file:OptIn(ExperimentalStdlibApi::class, dev.whyoleg.cryptography.DelicateCryptographyApi::class)

package com.bitchat.android.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.*
import dev.whyoleg.cryptography.providers.jdk.JDK
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase 1 spike: published vectors for every primitive Noise_XX_25519_ChaChaPoly_SHA256 and the channel crypto use. */
/** The provider the app uses on Android: BouncyCastle, explicitly, with no global JCA changes. */
class BouncyCastleVectorTest : VectorSuite(CryptographyProvider.JDK(org.bouncycastle.jce.provider.BouncyCastleProvider()))

/** The unmodified JDK 21 provider, for comparison. */
class JdkDefaultVectorTest : VectorSuite(CryptographyProvider.Default)

abstract class VectorSuite(private val p: CryptographyProvider) {
    private fun hex(s: String) = s.replace(" ", "").hexToByteArray()
    private fun ByteArray.hex() = toHexString()

    @Test
    fun sha256() = runTest {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            p.get(SHA256).hasher().hash("abc".encodeToByteArray()).hex()
        )
    }

    @Test
    fun hmacSha256_rfc4231_case2() = runTest {
        val key = p.get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, "Jefe".encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature("what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    @Test
    fun hkdfSha256_rfc5869_case1() = runTest {
        val ikm = ByteArray(22) { 0x0b }
        val out = p.get(HKDF).secretDerivation(SHA256, 42.bytes, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"))
            .deriveSecretToByteArray(ikm)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", out.hex())
    }

    @Test
    fun pbkdf2Sha256_rfc7914() = runTest {
        val out = p.get(PBKDF2).secretDerivation(SHA256, 1, 64.bytes, "salt".encodeToByteArray())
            .deriveSecretToByteArray("passwd".encodeToByteArray())
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            out.hex()
        )
    }

    @Test
    fun x25519_rfc7748_scalarMult() = runTest {
        val xdh = p.get(XDH)
        val priv = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            XDH.PrivateKey.Format.RAW, hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        )
        val pub = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            XDH.PublicKey.Format.RAW, hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        )
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            priv.sharedSecretGenerator().generateSharedSecretToByteArray(pub).hex()
        )
    }

    @Test
    fun x25519_rfc7748_diffieHellman() = runTest {
        val xdh = p.get(XDH)
        val alice = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            XDH.PrivateKey.Format.RAW, hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        )
        val bob = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(
            XDH.PrivateKey.Format.RAW, hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        )
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            alice.getPublicKey().encodeToByteArray(XDH.PublicKey.Format.RAW).hex()
        )
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, alice.sharedSecretGenerator().generateSharedSecretToByteArray(bob.getPublicKey()).hex())
        assertEquals(shared, bob.sharedSecretGenerator().generateSharedSecretToByteArray(alice.getPublicKey()).hex())
    }

    @Test
    fun ed25519_rfc8032_test1() = runTest {
        val eddsa = p.get(EdDSA)
        val priv = eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519).decodeFromByteArray(
            EdDSA.PrivateKey.Format.RAW, hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        )
        val pub = priv.getPublicKey()
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            pub.encodeToByteArray(EdDSA.PublicKey.Format.RAW).hex()
        )
        val sig = priv.signatureGenerator().generateSignature(ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            sig.hex()
        )
        assertEquals(true, pub.signatureVerifier().tryVerifySignature(ByteArray(0), sig))
    }

    @Test
    fun chacha20Poly1305_rfc8439_2_8_2() = runTest {
        val key = p.get(ChaCha20Poly1305).keyDecoder().decodeFromByteArray(
            ChaCha20Poly1305.Key.Format.RAW,
            hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        )
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        val expected = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
        )
        val cipher = key.cipher()
        val sealed = cipher.encryptWithIv(nonce, pt.encodeToByteArray(), aad)
        assertEquals(expected.hex(), sealed.hex())
        assertEquals(pt, cipher.decryptWithIv(nonce, sealed, aad).decodeToString())
    }

    @Test
    fun aes256Gcm_mcgrewViega_case15() = runTest {
        val key = p.get(AES.GCM).keyDecoder().decodeFromByteArray(
            AES.Key.Format.RAW, hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        )
        val iv = hex("cafebabefacedbaddecaf888")
        val pt = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255"
        )
        val expected = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
            "b094dac5d93471bdec1a502270e3cc6c"
        val sealed = key.cipher().encryptWithIv(iv, pt)
        assertEquals(expected, sealed.hex())
        assertEquals(pt.hex(), key.cipher().decryptWithIv(iv, sealed).hex())
    }
}
