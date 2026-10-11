package com.bitchat.android.nostr

import kotlin.test.assertEquals
import kotlin.test.Test

class NostrLiveSubscriptionPrivacyTest {
    @Test
    fun teardownClosesLiveSubscriptionsOnSharedRelays() {
        val targets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = setOf("live-a", "live-b"),
            subscriptionsByRelay = mapOf(
                "shared-relay" to setOf("dm", "live-a"),
                "live-relay" to setOf("live-a", "live-b"),
                "dm-relay" to setOf("dm"),
            ),
        )

        assertEquals(
            mapOf(
                "shared-relay" to setOf("live-a"),
                "live-relay" to setOf("live-a", "live-b"),
            ),
            targets,
        )
    }

    @Test
    fun teardownIgnoresRelaysWithoutLiveSubscriptions() {
        assertEquals(
            emptyMap<String, Set<String>>(),
            NostrLiveSubscriptionPrivacy.closeTargets(
                liveSubscriptionIds = emptySet(),
                subscriptionsByRelay = mapOf("default-relay" to setOf("dm")),
            ),
        )
    }
}
