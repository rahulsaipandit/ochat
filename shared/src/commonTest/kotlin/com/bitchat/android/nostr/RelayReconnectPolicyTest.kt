package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The relay layer has no connectivity callback, its periodic validator only
 * repairs subscriptions on sockets that are already open, and `connect()` runs
 * once at startup. The backoff schedule is therefore the only thing that can
 * bring relays back after the phone loses its data connection, so it has to
 * saturate rather than terminate.
 */
class RelayReconnectPolicyTest {

    private val initial = AppConstants.Nostr.INITIAL_BACKOFF_INTERVAL_MS
    private val ceiling = AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS

    @Test
    fun theFirstRetryWaitsTheInitialInterval() {
        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(0)))
    }

    @Test
    fun theIntervalDoublesPerAttemptUntilItReachesTheCeiling() {
        var attempt = 0
        var previous = 0L
        var sawCeiling = false

        repeat(RelayReconnectPolicy.SATURATION_ATTEMPTS) {
            attempt = RelayReconnectPolicy.nextAttempt(attempt)
            val delay = RelayReconnectPolicy.backoffMs(attempt)

            assertTrue(delay <= ceiling, "delay must never exceed the ceiling")
            if (delay == ceiling) {
                sawCeiling = true
            } else {
                assertEquals(maxOf(initial, previous * 2), delay, "expected doubling below the ceiling")
            }
            previous = delay
        }

        assertTrue(sawCeiling, "the schedule must actually reach the ceiling")
    }

    @Test
    fun anOutageLongerThanTheScheduleKeepsRetryingAtTheCeiling() {
        var attempt = 0
        // Far past the old give-up point; a real outage can last hours.
        repeat(500) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }

        assertEquals(RelayReconnectPolicy.SATURATION_ATTEMPTS, attempt)
        assertEquals(ceiling, RelayReconnectPolicy.backoffMs(attempt))
    }

    @Test
    fun aLongOutageCannotRunTheAttemptCounterOrTheExponentAway() {
        var attempt = 0
        repeat(10_000) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }

        val delay = RelayReconnectPolicy.backoffMs(attempt)
        assertTrue(delay in 1..ceiling, "delay must stay finite and bounded")
    }

    @Test
    fun aSuccessfulConnectionResetsTheScheduleToTheInitialInterval() {
        var attempt = 0
        repeat(6) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }
        assertTrue(RelayReconnectPolicy.backoffMs(attempt) > initial)

        // updateRelayStatus zeroes reconnectAttempts on a successful open.
        attempt = 0

        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(attempt)))
    }

    @Test
    fun aNonsensicalStoredAttemptCountStillYieldsAUsableDelay() {
        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(-5)))
        assertTrue(RelayReconnectPolicy.backoffMs(0) in 1..ceiling)
        assertTrue(RelayReconnectPolicy.backoffMs(Int.MAX_VALUE) in 1..ceiling)
    }

    @Test
    fun theWholeScheduleStaysUnderAnHourOfTotalWaitBeforeTheCeiling() {
        var attempt = 0
        var total = 0L
        repeat(RelayReconnectPolicy.SATURATION_ATTEMPTS) {
            attempt = RelayReconnectPolicy.nextAttempt(attempt)
            total += RelayReconnectPolicy.backoffMs(attempt)
        }

        assertTrue(total < 60 * 60 * 1000L, "reaching the steady state must not take an hour")
    }
}
