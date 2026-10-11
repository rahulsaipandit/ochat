package com.bitchat.android.nostr

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class NostrEventDeduplicatorTest {
    @Test
    fun isDuplicateFlagsARepeatedEventId() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10)

        assertEquals(false, deduplicator.isDuplicate("event-1"))
        assertEquals(true, deduplicator.isDuplicate("event-1"))
    }

    @Test
    fun capacityEvictsTheLeastRecentlyUsedEventId() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 2)

        deduplicator.isDuplicate("a")
        deduplicator.isDuplicate("b")
        deduplicator.isDuplicate("c") // evicts "a"

        assertTrue(deduplicator.contains("b"))
        assertTrue(deduplicator.contains("c"))
        assertEquals(false, deduplicator.contains("a"))
    }
}
