package com.bitchat.android.nostr

/** Proof-of-work preference at the moment an event is created. */
data class NostrPowSettings(val enabled: Boolean, val difficulty: Int)

/**
 * Port for the device-side proof-of-work preference. [NostrProtocol] reads it when it builds a
 * geohash message and reports mining so the UI can show progress. Until a platform registers one,
 * proof of work is off.
 */
interface NostrPowSettingsProvider {
    fun currentSettings(): NostrPowSettings
    fun miningStarted()
    fun miningStopped()
}

internal object DisabledNostrPowSettings : NostrPowSettingsProvider {
    override fun currentSettings() = NostrPowSettings(enabled = false, difficulty = 0)
    override fun miningStarted() = Unit
    override fun miningStopped() = Unit
}
