package com.bitchat.android.nostr

import com.bitchat.android.protocol.nowMillis
import com.bitchat.android.util.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden vectors captured from the previous BouncyCastle/Tink NostrCrypto (synthetic fixed keys).
 * They pin the wire format the iOS client also speaks; the common implementation must reproduce them
 * byte for byte.
 */
class NostrCryptoVectorTest {

    private val privateKeys = listOf(
        "1111111111111111111111111111111111111111111111111111111111111111",
        "2222222222222222222222222222222222222222222222222222222222222222",
        "3333333333333333333333333333333333333333333333333333333333333333",
        "4444444444444444444444444444444444444444444444444444444444444444",
        "5555555555555555555555555555555555555555555555555555555555555555"
    )

    private val publicKeys = listOf(
        "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa",
        "466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27",
        "3c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1",
        "2c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
        "9ac20335eb38768d2052be1dbbc3c8f6178407458e51e6b4ad22f1d91758895b"
    )

    private class Cipher(val sender: Int, val recipient: Int, val plaintextHex: String, val ciphertext: String)

    private val ciphertexts = listOf(
        Cipher(0, 1, "6d73672d302d3120c3a9e4b8ad", "v2:ozajuFlhdx2lXG6S5xJZBpbUdtAX43tzlvxLAP24nlv7ZOLjObJMxJIYeiJ7anBwodiaaBA"),
        Cipher(0, 2, "6d73672d302d3220c3a9e4b8ad", "v2:zCPu30qD7ROGxsN6GuVIWbccOSF9bDIjRGas3CW4qZ2PRS_N_mUbvj-H4SqafMMl8jQr-tc"),
        Cipher(0, 3, "6d73672d302d3320c3a9e4b8ad", "v2:dQaEqU5VOkXZEblo-vdhJJEjoi_UR7-J-ITPE14rV7N3rkDuwI3VMgY340QNURcZjIxl30Q"),
        Cipher(0, 4, "6d73672d302d3420c3a9e4b8ad", "v2:QlORRgebHTsFWGbuHjnOjYvk_d5xRNvAN_4sUgABsBuIKTjgyGp1FHEOxO0lrCzF9AHuO_A"),
        Cipher(1, 0, "6d73672d312d3020c3a9e4b8ad", "v2:pB0TgBjZ5HdbI3S5gnEpnmXzuNLdYH5SJwqMB4KcDJrSaYB_7NeyUb5Y5jzk5JJbVclCzkU"),
        Cipher(1, 2, "6d73672d312d3220c3a9e4b8ad", "v2:H4mDbeXjPiHTOiR00yZ7CwPvuyiYMmgStqxG7yRz5oWZJJUV7MBnPH2VpRoB_wL6eBvYfgI"),
        Cipher(1, 3, "6d73672d312d3320c3a9e4b8ad", "v2:6iC6bgQ1BD4s0cNmEQ9gu3UHmi9V-jLrSAyFwKPq24pFhw9OPqDE8auj7MJO-Ro2L0bEoNA"),
        Cipher(1, 4, "6d73672d312d3420c3a9e4b8ad", "v2:gpeuSQsHF_2Ro_WZht4XAshgTBjNMFcdbfTaLRYrowoYAnjHdxq6D0FpKmW7C07m9tPbW54"),
        Cipher(2, 0, "6d73672d322d3020c3a9e4b8ad", "v2:9QreL7lOTEV9MKatlJCuHXGo_7PkpVRT_hgtcBpHgTpjXuOkqXply9S9fvlz1DUAwXFRcQQ"),
        Cipher(2, 1, "6d73672d322d3120c3a9e4b8ad", "v2:CzxGpHPIA0bzZECdilNyimzyMmBkLa2yKie5cxOdq2XLoj7TlV2ris-AlS7SbIj62VwGTlA"),
        Cipher(2, 3, "6d73672d322d3320c3a9e4b8ad", "v2:qiZyZFUeCeLvV0OY5c0cEs0uYps3vMeMJX60gGjUOz_co6-StOmStPK7K5wa6kK3ANUxQJ4"),
        Cipher(2, 4, "6d73672d322d3420c3a9e4b8ad", "v2:m2x7jUwOBErxuuabL9KGXJ6WSjpJ5NgBkiHCY7R7fay8CHSYQXmJUzIyGvxe6zRdTvK-ee4"),
        Cipher(3, 0, "6d73672d332d3020c3a9e4b8ad", "v2:FwG7thzTzyS_AqWeogaLS1AefbFWZhfNXLsgu9s6CDYQRq93rZS9Q4a28KTVpqIb2eEXmtQ"),
        Cipher(3, 1, "6d73672d332d3120c3a9e4b8ad", "v2:af_mDuMmy4I_-GX96zV3PS0gzH7CB6qyBHn3NwooR4Pl1BLSgL0juEbg1Sg2tNJNWYBOtQI"),
        Cipher(3, 2, "6d73672d332d3220c3a9e4b8ad", "v2:hNffZLChKDZTNJFfhoQKUEV3cRuMPy9pe9olsoxvlsEfSIyC9dee4sLu9oUoj136y3sMhb0"),
        Cipher(3, 4, "6d73672d332d3420c3a9e4b8ad", "v2:AUS6hiMHCtIurY7Zhnoy8VFLFstRpICnzqvnB0BTpVyKts7zaadS5aM4DmWEi1j7Wvj0GYA"),
        Cipher(4, 0, "6d73672d342d3020c3a9e4b8ad", "v2:FeHtjliZtFXF-MrXUeYaYR5AbwEh_F75Ou87_NCayjbN2nUFBL7jcVYMqUk7pyhMVnKdp4I"),
        Cipher(4, 1, "6d73672d342d3120c3a9e4b8ad", "v2:CI4qUhqtP3jxgPg58c_G-XxynNlk_wHBETh3VxjVFbNNBlpqxoQeuT--RH7CKmZHU69dqS0"),
        Cipher(4, 2, "6d73672d342d3220c3a9e4b8ad", "v2:y3ERpgpqq4r1Pd9EGBW6FnVCRay3lTWrAfcOKCI7_e2RBt_zlwtZ_Bo41xw-tzrcMoJNUZM"),
        Cipher(4, 3, "6d73672d342d3320c3a9e4b8ad", "v2:-al8hfVo8X49AdZlHBelwUY2FUstsebV70Ej9gaLZaKWt_S4SvIIkMIOD79RARDgpV76iNk")
    )

    private val kdfInput = "01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1"
    private val kdfOutput = "ec8f4346c8fdce9eea02aa63f136ee83db0de00e4ec022d9b8c576cb8ca2caae"

    private val sigMessage = "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d"

    private val signatures = listOf(
        "46fca5fc8241ce67f38c924f41ccc576da901200e132408654e0f75372bd4151f172e7ce313b51d8b9fccbe0bdf5a0ec3cac433f880007b2c9899b85530ff77f",
        "04815e5c626eb276cf74f455531b2da29f31e6ff1e67fac548efa7398d0220661179e85e95dd1f42a4d4dc7b829c176a6d7bac63dd97685a502103c04deae81d",
        "03080717b03920ad9f7bc62c7b1b9a0bf8e30ad35cab2a0f36d1f37585987365309a508caee930204c397bc54e7637d9d30594416b8ece8e206c5eb5305a1a8b",
        "fec2d4406191089cf7f13524e9e41866d767f89b305e781a32021222eb1f6658a97bd052d7d00b613fae6fb0310e5817c10e7175760849359b943e33ee38b760",
        "aacea0fc4f10f8b3d3aa953d4f59a5d36749f19de8a3cf8f5e81cd1ad59d0f75fc4c1e43e17936cc04e83a5423be858f1cee904e5a05e2d8162d26691aef7891"
    )

    private class VerifyCase(val label: String, val message: String, val signature: String, val publicKey: String, val expected: Boolean)

    private val verifyCases = listOf(
        VerifyCase("ok", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "933d784396d9f3357c7bff662be401895c14515d500ccc286adf4169dbb9abd03253f126d5648345e6d5ffa5ab91f80e3284bbcdb39d23f2553c602a2d486916", "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", true),
        VerifyCase("wrongmsg", "03070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "933d784396d9f3357c7bff662be401895c14515d500ccc286adf4169dbb9abd03253f126d5648345e6d5ffa5ab91f80e3284bbcdb39d23f2553c602a2d486916", "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", false),
        VerifyCase("wrongkey", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "933d784396d9f3357c7bff662be401895c14515d500ccc286adf4169dbb9abd03253f126d5648345e6d5ffa5ab91f80e3284bbcdb39d23f2553c602a2d486916", "466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27", false),
        VerifyCase("sge_n", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "933d784396d9f3357c7bff662be401895c14515d500ccc286adf4169dbb9abd0fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", false),
        VerifyCase("r_ge_p", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff3253f126d5648345e6d5ffa5ab91f80e3284bbcdb39d23f2553c602a2d486916", "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", false),
        VerifyCase("zeros", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa", false),
        VerifyCase("badpub", "02070c11161b20252a2f34393e43484d52575c61666b70757a7f84898e93989d", "933d784396d9f3357c7bff662be401895c14515d500ccc286adf4169dbb9abd03253f126d5648345e6d5ffa5ab91f80e3284bbcdb39d23f2553c602a2d486916", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", false)
    )

    private val privateKeyCases = listOf(
        "" to false,
        "0000000000000000000000000000000000000000000000000000000000000000" to false,
        "00000000000000000000000000000000000000000000000000000000000000" to false,
        "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141" to false,
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to false,
        "0000000000000000000000000000000000000000000000000000000000000001" to true,
        "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140" to true,
        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" to false
    )

    private val publicKeyCases = listOf(
        "0000000000000000000000000000000000000000000000000000000000000000" to false,
        "0000000000000000000000000000000000000000000000000000000000000005" to false,
        "0000000000000000000000000000000000000000000000000000000000000007" to false,
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to false,
        "fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f" to false,
        "00000000000000000000000000000000000000000000000000000000000000" to false,
        "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa" to true,
        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" to false
    )


    private fun String.bytes() = hexToByteArray()

    @Test
    fun derivesXOnlyPublicKeys() {
        privateKeys.forEachIndexed { i, key -> assertEquals(publicKeys[i], NostrCrypto.derivePublicKey(key)) }
    }

    @Test
    fun decryptsCiphertextsFromPreviousImplementation() {
        for (c in ciphertexts) {
            val plaintext = NostrCrypto.decryptNIP44(c.ciphertext, publicKeys[c.sender], privateKeys[c.recipient])
            assertEquals(c.plaintextHex, plaintext.encodeToByteArray().toHexString(), "${c.sender}->${c.recipient}")
        }
    }

    @Test
    fun encryptDecryptRoundTripsForEveryKeyPairOrdering() {
        for (s in privateKeys.indices) for (r in privateKeys.indices) if (s != r) {
            val plaintext = "round trip $s-$r é中"
            val ciphertext = NostrCrypto.encryptNIP44(plaintext, publicKeys[r], privateKeys[s])
            assertTrue(ciphertext.startsWith("v2:"))
            assertEquals(plaintext, NostrCrypto.decryptNIP44(ciphertext, publicKeys[s], privateKeys[r]))
        }
    }

    @Test
    fun encryptionIsRandomised() {
        val a = NostrCrypto.encryptNIP44("same", publicKeys[1], privateKeys[0])
        val b = NostrCrypto.encryptNIP44("same", publicKeys[1], privateKeys[0])
        assertTrue(a != b)
    }

    @Test
    fun rejectsTamperedWrongKeyAndMalformedCiphertext() {
        val c = ciphertexts.first { it.sender == 0 && it.recipient == 1 }
        val flipped = c.ciphertext.dropLast(1) + if (c.ciphertext.last() == 'A') 'B' else 'A'
        assertFailsWith<RuntimeException> { NostrCrypto.decryptNIP44(flipped, publicKeys[0], privateKeys[1]) }
        assertFailsWith<RuntimeException> { NostrCrypto.decryptNIP44(c.ciphertext, publicKeys[0], privateKeys[2]) }
        assertFailsWith<RuntimeException> { NostrCrypto.decryptNIP44("v1:" + c.ciphertext.substring(3), publicKeys[0], privateKeys[1]) }
        assertFailsWith<RuntimeException> { NostrCrypto.decryptNIP44("v2:", publicKeys[0], privateKeys[1]) }
        assertFailsWith<RuntimeException> { NostrCrypto.decryptNIP44("v2:!!!", publicKeys[0], privateKeys[1]) }
    }

    @Test
    fun derivesNip44KeyLikePreviousImplementation() {
        assertEquals(kdfOutput, NostrCrypto.deriveNIP44Key(kdfInput.bytes()).toHexString())
    }

    @Test
    fun verifiesSignaturesFromPreviousImplementation() {
        signatures.forEachIndexed { i, sig ->
            assertTrue(NostrCrypto.schnorrVerify(sigMessage.bytes(), sig, publicKeys[i]), "signature $i")
        }
    }

    @Test
    fun signsAndVerifies() {
        privateKeys.forEachIndexed { i, key ->
            val sig = NostrCrypto.schnorrSign(sigMessage.bytes(), key)
            assertEquals(128, sig.length)
            assertTrue(NostrCrypto.schnorrVerify(sigMessage.bytes(), sig, publicKeys[i]))
            assertFalse(NostrCrypto.schnorrVerify(sigMessage.bytes(), sig, publicKeys[(i + 1) % publicKeys.size]))
        }
    }

    @Test
    fun verifyAcceptsAndRejectsLikePreviousImplementation() {
        for (c in verifyCases) {
            assertEquals(c.expected, NostrCrypto.schnorrVerify(c.message.bytes(), c.signature, c.publicKey), c.label)
        }
        assertFalse(NostrCrypto.schnorrVerify(ByteArray(31), signatures[0], publicKeys[0]))
        assertFalse(NostrCrypto.schnorrVerify(sigMessage.bytes(), signatures[0].dropLast(2), publicKeys[0]))
        assertFalse(NostrCrypto.schnorrVerify(sigMessage.bytes(), "zz".repeat(64), publicKeys[0]))
    }

    /** BIP-340 test vector 0 (secret key 3, all-zero message). */
    @Test
    fun verifiesBip340Vector0() {
        val publicKey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        assertEquals(publicKey, NostrCrypto.derivePublicKey("00".repeat(31) + "03"))
        val signature = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
        assertTrue(NostrCrypto.schnorrVerify(ByteArray(32), signature, publicKey))
    }

    @Test
    fun validatesPrivateKeysLikePreviousImplementation() {
        for ((input, expected) in privateKeyCases) assertEquals(expected, NostrCrypto.isValidPrivateKey(input), "'$input'")
    }

    @Test
    fun validatesPublicKeysLikePreviousImplementation() {
        for ((input, expected) in publicKeyCases) assertEquals(expected, NostrCrypto.isValidPublicKey(input), "'$input'")
    }

    @Test
    fun generatedKeyPairsAreValidAndConsistent() {
        repeat(5) {
            val (priv, pub) = NostrCrypto.generateKeyPair()
            assertTrue(NostrCrypto.isValidPrivateKey(priv))
            assertTrue(NostrCrypto.isValidPublicKey(pub))
            assertEquals(pub, NostrCrypto.derivePublicKey(priv))
        }
    }

    @Test
    fun hChaCha20MatchesDraftVector() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000090000004a0000000031415927".bytes()
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            XChaCha20Poly1305.hChaCha20(key, nonce).toHexString()
        )
    }

    @Test
    fun timestampsStayInsideRequestedWindows() {
        repeat(50) {
            val past = NostrCrypto.randomizeTimestampUpToPast(100)
            val now = (nowMillis() / 1000).toInt()
            assertTrue(past in (now - 102)..(now + 1))
            val jitter = NostrCrypto.randomizeTimestamp(1_000_000L)
            assertTrue(jitter in 999_100..1_000_899)
        }
    }
}
