package com.bitchat.android.noise

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoiseChannelEncryptionTest {

    // Commitments are SHA-256 of the derived key; expected values come from
    // hashlib.pbkdf2_hmac('sha256', password, channel, 100000, 32), independent of this code.
    @Test
    fun derivedKeyMatchesPbkdf2Reference() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("correct horse battery", "#test-channel")
        assertEquals(
            "3d5d71723b1c40a402ef3c4c9dd07e6d056ca86985d4b0fa822f15aaeb04b207",
            enc.calculateKeyCommitment("#test-channel")
        )
    }

    @Test
    fun nonAsciiPasswordAndChannelAreDerivedFromUtf8() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("pässwörd ✓", "#kanäl")
        assertEquals(
            "144fbe58f2259dcd5f02dd7609bb26b57c06dc1d41a76f74c17390b485e80ec8",
            enc.calculateKeyCommitment("#kanäl")
        )
    }

    @Test
    fun roundTripUsesIvPlusCiphertextPlusTagLayout() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("pw", "#c")
        val message = "hello channel"
        val sealed = enc.encryptChannelMessage(message, "#c")
        assertEquals(12 + message.encodeToByteArray().size + 16, sealed.size)
        assertEquals(message, enc.decryptChannelMessage(sealed, "#c"))
        // A fresh random IV per message.
        assertNotEquals(sealed.toList(), enc.encryptChannelMessage(message, "#c").toList())
    }

    @Test
    fun wrongPasswordTamperingAndShortDataAreRejected() {
        val a = NoiseChannelEncryption().apply { setChannelPassword("one", "#c") }
        val b = NoiseChannelEncryption().apply { setChannelPassword("two", "#c") }
        val sealed = a.encryptChannelMessage("secret", "#c")

        assertFails { b.decryptChannelMessage(sealed, "#c") }
        val tampered = sealed.copyOf().also { it[sealed.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertFails { a.decryptChannelMessage(tampered, "#c") }
        assertFailsWith<IllegalArgumentException> { a.decryptChannelMessage(ByteArray(15), "#c") }
        assertFailsWith<IllegalStateException> { a.decryptChannelMessage(sealed, "#unknown") }
    }

    @Test
    fun removeAndClearForgetKeys() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("pw", "#c")
        assertTrue(enc.hasChannelKey("#c"))
        enc.removeChannelPassword("#c")
        assertFalse(enc.hasChannelKey("#c"))
        assertNull(enc.calculateKeyCommitment("#c"))

        enc.setChannelPassword("pw", "#d")
        enc.clear()
        assertTrue(enc.getActiveChannels().isEmpty())
    }

    @Test
    fun emptyPasswordIsIgnored() {
        val enc = NoiseChannelEncryption()
        enc.setChannelPassword("", "#c")
        assertFalse(enc.hasChannelKey("#c"))
    }

    private fun assertFails(block: () -> Unit) {
        assertFailsWith<Exception> { block() }
    }
}
