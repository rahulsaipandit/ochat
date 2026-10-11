package com.bitchat.shared.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class TransportTest {
    private val declining = object : Transport {
        override val id = "fake"
        override val incoming: Flow<InboundFrame> = emptyFlow()
        override suspend fun broadcast(frame: ByteArray) = false
        override suspend fun sendToLink(linkId: LinkId, frame: ByteArray) = false
    }

    @Test
    fun transportWithoutLinksDeclinesSends() = runTest {
        assertFalse(declining.broadcast(byteArrayOf(1)))
        assertFalse(declining.sendToLink(LinkId("a"), byteArrayOf(1)))
    }
}
