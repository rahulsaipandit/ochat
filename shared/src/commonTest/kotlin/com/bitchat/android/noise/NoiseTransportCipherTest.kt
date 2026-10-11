package com.bitchat.android.noise

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class NoiseTransportCipherTest {
    private fun established(): Pair<NoiseTransportCiphers, NoiseTransportCiphers> {
        val i = NoiseXXHandshake(true, DefaultNoiseCrypto.generateKeyPair())
        val r = NoiseXXHandshake(false, DefaultNoiseCrypto.generateKeyPair())
        r.readMessage(i.writeMessage())
        i.readMessage(r.writeMessage())
        r.readMessage(i.writeMessage())
        return i.split() to r.split()
    }

    @Test
    fun destroyedCipherFailsClosedInsteadOfUsingAZeroedKey() {
        val (initiator, responder) = established()
        val sealed = initiator.send.encrypt(0, "x".encodeToByteArray())
        responder.receive.destroy()
        initiator.send.destroy()

        assertFailsWith<IllegalStateException> { initiator.send.encrypt(1, "y".encodeToByteArray()) }
        assertFailsWith<NoiseException> { responder.receive.decrypt(0, sealed) }
    }

    @Test
    fun providerIsBuiltOnceAndReused() {
        assertSame(noiseCryptographyProvider(), noiseCryptographyProvider())
    }
}
