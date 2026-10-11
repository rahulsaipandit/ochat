package com.bitchat.android.nostr

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.Test

class NostrPendingEventQueueTest {
    @Test
    fun emptyRelaySetIsNotQueued() {
        val queue = NostrPendingEventQueue(capacity = 2)

        assertNull(queue.enqueue(event("empty"), emptyList(), liveLocationToken = null))
        assertEquals(0, queue.size())
    }

    @Test
    fun capacityEvictsTheOldestPublish() {
        val queue = NostrPendingEventQueue(capacity = 2)
        queue.enqueue(event("one"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("two"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("three"), listOf("relay"), liveLocationToken = null)

        assertEquals(
            listOf("two", "three"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    @Test
    fun duplicateEventPublishesRetainIndependentDeliveryState() {
        val queue = NostrPendingEventQueue(capacity = 4)
        val signedEvent = event("same")
        val firstId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a", "relay-b"), liveLocationToken = null)
        )
        val secondId = requireNotNull(
            queue.enqueue(signedEvent, listOf("relay-a"), liveLocationToken = null)
        )
        assertNotEquals(firstId, secondId)

        queue.markDelivered(firstId, "relay-a")

        assertEquals(
            listOf(secondId),
            queue.pendingForRelay("relay-a").map { it.queueId }
        )
        assertEquals(
            listOf(firstId),
            queue.pendingForRelay("relay-b").map { it.queueId }
        )

        queue.markDelivered(firstId, "relay-b")
        assertEquals(1, queue.size())
    }

    @Test
    fun privacyPurgeRetainsNonLivePublishes() {
        val queue = NostrPendingEventQueue(capacity = 4)
        queue.enqueue(event("manual"), listOf("relay"), liveLocationToken = null)
        queue.enqueue(event("live"), listOf("relay"), liveLocationToken = 42L)

        queue.removeLiveLocationEvents()

        assertEquals(
            listOf("manual"),
            queue.pendingForRelay("relay").map { it.event.content }
        )
    }

    private fun event(content: String): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
    }
}
