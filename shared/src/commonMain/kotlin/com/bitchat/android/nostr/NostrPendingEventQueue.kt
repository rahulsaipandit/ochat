package com.bitchat.android.nostr

import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.withLock

/**
 * Thread-safe bounded queue of relay deliveries awaiting a usable WebSocket.
 *
 * Queue entries have a local ID rather than using the Nostr event ID: the same signed event may be
 * intentionally published more than once with different relay sets or privacy provenance.
 */
class NostrPendingEventQueue(
    private val capacity: Int
) {
    init {
        require(capacity > 0)
    }

    data class Delivery(
        val queueId: Long,
        val event: NostrEvent,
        val liveLocationToken: Long?
    )

    private data class Entry(
        val queueId: Long,
        val event: NostrEvent,
        val pendingRelayUrls: MutableSet<String>,
        val liveLocationToken: Long?
    )

    private val lock = PlatformLock()
    private val entries = ArrayDeque<Entry>()
    private var nextQueueId = 1L

    fun enqueue(
        event: NostrEvent,
        relayUrls: Collection<String>,
        liveLocationToken: Long?
    ): Long? {
        val pendingRelays = relayUrls.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (pendingRelays.isEmpty()) return null

        return lock.withLock {
            if (entries.size >= capacity) entries.removeFirst()
            val queueId = nextQueueId++
            entries.addLast(
                Entry(
                    queueId = queueId,
                    event = event,
                    pendingRelayUrls = pendingRelays,
                    liveLocationToken = liveLocationToken
                )
            )
            queueId
        }
    }

    fun pendingForRelay(relayUrl: String): List<Delivery> = lock.withLock {
        entries
            .asSequence()
            .filter { relayUrl in it.pendingRelayUrls }
            .map { Delivery(it.queueId, it.event, it.liveLocationToken) }
            .toList()
    }

    fun markDelivered(queueId: Long, relayUrl: String) {
        lock.withLock {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.queueId != queueId) continue
                entry.pendingRelayUrls.remove(relayUrl)
                if (entry.pendingRelayUrls.isEmpty()) iterator.remove()
                return
            }
        }
    }

    fun removeLiveLocationEvents() {
        lock.withLock {
            entries.removeAll { it.liveLocationToken != null }
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }

    fun size(): Int = lock.withLock { entries.size }
}
