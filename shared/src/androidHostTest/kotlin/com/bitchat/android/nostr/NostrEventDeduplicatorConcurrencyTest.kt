package com.bitchat.android.nostr

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NostrEventDeduplicatorConcurrencyTest {
    /**
     * totalChecks used to be incremented outside the lruLock that guards every other
     * mutation in this class, so concurrent callers could race on the read-modify-write
     * and lose increments. Every other counter (duplicateCount, evictionCount) was
     * already incremented under the lock, which is why only this one drifted.
     */
    @Test
    fun totalChecksCountsEveryCallExactlyOnceUnderConcurrentAccess() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10_000)
        val threadCount = 8
        val checksPerThread = 2_000
        val executor = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { threadIndex ->
            executor.submit {
                start.await()
                repeat(checksPerThread) { callIndex ->
                    deduplicator.isDuplicate("thread-$threadIndex-event-$callIndex")
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(
            (threadCount * checksPerThread).toLong(),
            deduplicator.getStats().totalChecks
        )
    }
}
