package com.bitchat.android.noise

import org.junit.Assert.assertEquals
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Test

/**
 * The shared channel encryption against the JCA code it replaced (the reference below is the
 * previous implementation, verbatim in behavior): messages must decrypt across both directions.
 */
class NoiseChannelEncryptionJcaCompatTest {
    private fun jcaKey(password: String, channel: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), channel.toByteArray(Charsets.UTF_8), 100000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }

    private fun jcaEncrypt(key: SecretKeySpec, message: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun jcaDecrypt(key: SecretKeySpec, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }

    @Test
    fun jcaEncryptedMessageDecryptsWithSharedImplementation() {
        val enc = NoiseChannelEncryption().apply { setChannelPassword("pässwörd ✓", "#kanäl") }
        val sealed = jcaEncrypt(jcaKey("pässwörd ✓", "#kanäl"), "grüß dich 👋")
        assertEquals("grüß dich 👋", enc.decryptChannelMessage(sealed, "#kanäl"))
    }

    @Test
    fun sharedEncryptedMessageDecryptsWithJca() {
        val enc = NoiseChannelEncryption().apply { setChannelPassword("pässwörd ✓", "#kanäl") }
        val sealed = enc.encryptChannelMessage("grüß dich 👋", "#kanäl")
        assertEquals("grüß dich 👋", jcaDecrypt(jcaKey("pässwörd ✓", "#kanäl"), sealed))
    }

    @Test
    fun derivedKeysAreIdentical() {
        val enc = NoiseChannelEncryption().apply { setChannelPassword("pw", "#c") }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(jcaKey("pw", "#c").encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, enc.calculateKeyCommitment("#c"))
    }
}
