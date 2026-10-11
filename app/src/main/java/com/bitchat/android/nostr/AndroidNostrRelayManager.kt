package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Process-wide [NostrRelayManager] wired to the Android implementations: OkHttp sockets (Tor-aware),
 * the bundled relay directory for geohash selection, the live-location consent gate, and the power
 * profile's validation cadence. Handlers run on the main thread.
 */
private object AndroidRelayManagerHolder {
    @Volatile
    var appContext: Context? = null

    val instance: NostrRelayManager by lazy {
        NostrRelayManager(
            connector = OkHttpRelayConnector { OkHttpProvider.webSocketClient() },
            relaySelector = RelaySelector { geohash, count -> RelayDirectory.closestRelaysForGeohash(geohash, count) },
            liveLocationGate = LiveLocationPrivacyGate,
            validationIntervalMs = ::validationIntervals,
            handlerDispatcher = Dispatchers.Main,
        )
    }

    /** Follows the power profile once a context is known; a fixed interval until then. */
    private fun validationIntervals(): Flow<Long> {
        val context = appContext
            ?: return flowOf(AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS)
        return PowerManager.getInstance(context).profile.map { it.nostr.subscriptionValidationMs }
    }
}

val NostrRelayManager.Companion.shared: NostrRelayManager
    get() = AndroidRelayManagerHolder.instance

/** The shared manager; also records the application context used to follow the power profile. */
fun NostrRelayManager.Companion.getInstance(context: Context): NostrRelayManager {
    AndroidRelayManagerHolder.appContext = context.applicationContext
    return AndroidRelayManagerHolder.instance
}
