package com.bitchat.android.nostr

import com.bitchat.android.protocol.PlatformLock
import com.bitchat.android.protocol.PlatformLog
import com.bitchat.android.protocol.nowMillis
import com.bitchat.android.protocol.IoDispatcher
import com.bitchat.android.protocol.synchronizedSetOf
import com.bitchat.android.protocol.withLock
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages WebSocket connections to Nostr relays.
 * Compatible with iOS implementation.
 *
 * Everything platform-specific is injected: sockets ([RelayConnector]), geohash relay selection
 * ([RelaySelector]), the live-location consent gate ([LiveLocationGate]) and the cadence of
 * subscription validation. The manager owns the policy: which relays to hold open, reconnect
 * backoff, subscription restore, queued-publish delivery, de-duplication and the privacy teardown
 * that follows revoked location consent.
 *
 * Concurrency: callbacks from the connector are queued per connection and processed serially on
 * [scope], so a socket reporting "open" before the caller has finished registering it cannot be
 * mistaken for a stale one. All mutable bookkeeping is guarded by [lock]. Code holding [lock] never
 * calls out (to sockets, handlers or the gate), so the lock order is always gate, then [lock].
 */
class NostrRelayManager(
    private val connector: RelayConnector,
    private val relaySelector: RelaySelector,
    private val liveLocationGate: LiveLocationGate,
    private val validationIntervalMs: () -> Flow<Long>,
    private val handlerDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope = CoroutineScope(IoDispatcher + SupervisorJob()),
    private val eventDeduplicator: NostrEventDeduplicator = NostrEventDeduplicator.getInstance(),
    private val clock: () -> Long = ::nowMillis,
) {

    companion object {
        private const val TAG = "NostrRelayManager"
        private const val MAX_QUEUED_EVENTS = 500
        private const val NORMAL_CLOSE = 1000
        const val OWNER_LEGACY = "legacy"
        const val OWNER_BACKGROUND = "background"

        // Default relay list (same as iOS)
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )

        // Reconnect backoff lives in RelayReconnectPolicy.

        // Track gift-wraps we initiated for logging
        private val pendingGiftWrapIDs = synchronizedSetOf<String>()

        fun registerPendingGiftWrap(id: String) {
            pendingGiftWrapIDs.add(id)
        }

        fun defaultRelays(): List<String> = DEFAULT_RELAYS
    }

    /**
     * Relay status information. Immutable: every change publishes a new snapshot, so collectors of
     * [relays] see status changes (a mutable shared instance would compare equal to itself).
     */
    data class Relay(
        val url: String,
        val isConnected: Boolean = false,
        val lastError: Throwable? = null,
        val lastConnectedAt: Long? = null,
        val messagesSent: Int = 0,
        val messagesReceived: Int = 0,
        val reconnectAttempts: Int = 0,
        val lastDisconnectedAt: Long? = null,
        val nextReconnectTime: Long? = null
    )

    /**
     * Information about an active subscription that needs to be maintained across reconnections
     */
    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null, // null means all relays
        val createdAt: Long = nowMillis(),
        val originGeohash: String? = null,
        val owner: String = OWNER_LEGACY,
        val liveLocationToken: Long? = null
    )

    data class SubscriptionConsistencyReport(
        val isConsistent: Boolean,
        val inconsistencies: List<String>,
        val totalActiveSubscriptions: Int,
        val connectedRelayCount: Int
    )

    // Published state
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Bookkeeping, all guarded by [lock].
    private val lock = PlatformLock()
    private val relayStates = LinkedHashMap<String, Relay>()
    private val connections = HashMap<String, Connection>()
    private val relaySubscriptions = HashMap<String, Set<String>>() // relay URL -> subscription IDs
    private val activeSubscriptions = LinkedHashMap<String, SubscriptionInfo>()
    private val reconnectJobs = HashMap<String, Job>()
    private val geohashToRelays = HashMap<String, Set<String>>() // geohash -> relay URLs
    private val liveGeohashTokens = HashMap<String, Long>()
    private val liveLocationRelayTokens = HashMap<String, Long>()
    private val nonLiveRelayUrls = HashSet<String>()
    private val liveLocationConnectionJobs = HashSet<Job>()
    private var subscriptionValidationJob: Job? = null

    @Volatile
    private var desiredConnected = false

    // Bounded per-relay delivery queue for reconnect reliability.
    private val messageQueue = NostrPendingEventQueue(MAX_QUEUED_EVENTS)

    init {
        // Start with the default relays tracked; they are never live-location scoped.
        DEFAULT_RELAYS.forEach { relayStates[it] = Relay(it) }
        nonLiveRelayUrls.addAll(DEFAULT_RELAYS)
        publishRelays()
        liveLocationGate.addRevocationListener(::revokeLiveLocationAccess)
    }

    // --- Public API for geohash-specific operation ---

    /**
     * Compute and connect to relays for a given geohash (nearest + optional defaults), cache the mapping.
     */
    fun ensureGeohashRelaysConnected(
        geohash: String,
        nRelays: Int = 5,
        includeDefaults: Boolean = false,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        try {
            val nearest = relaySelector.closestRelaysForGeohash(geohash, nRelays)
            val selected = if (includeDefaults) {
                (nearest + DEFAULT_RELAYS).toSet()
            } else nearest.toSet()
            if (selected.isEmpty()) {
                PlatformLog.w(TAG, "No relays selected for a geohash")
                return
            }
            runNetworkAction(liveLocationToken) {
                lock.withLock {
                    geohashToRelays[geohash] = selected
                    if (liveLocationToken == null) {
                        liveGeohashTokens.remove(geohash)
                        nonLiveRelayUrls.addAll(selected)
                    } else {
                        liveGeohashTokens[geohash] = liveLocationToken
                        selected.forEach { relayUrl ->
                            if (relayUrl !in nonLiveRelayUrls) {
                                liveLocationRelayTokens[relayUrl] = liveLocationToken
                            }
                        }
                    }
                }
                ensureConnectionsFor(selected, liveLocationToken)
            }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to ensure geohash relays")
        }
    }

    /**
     * Get relays mapped to a geohash (empty list if none configured).
     */
    fun getRelaysForGeohash(geohash: String): List<String> {
        return lock.withLock { geohashToRelays[geohash]?.toList() } ?: emptyList()
    }

    /**
     * Subscribe with explicit geohash routing; ensures connections exist, then targets only those relays.
     */
    fun subscribeForGeohash(
        geohash: String,
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        includeDefaults: Boolean = false,
        nRelays: Int = 5,
        owner: String = OWNER_LEGACY,
        liveLocationToken: Long? = null
    ): String {
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        val relayUrls = getRelaysForGeohash(geohash)
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls,
            owner = owner,
            liveLocationToken = liveLocationToken,
            originGeohash = geohash
        )
    }

    /**
     * Send an event specifically to a geohash's relays (+ optional defaults).
     */
    fun sendEventToGeohash(
        event: NostrEvent,
        geohash: String,
        includeDefaults: Boolean = false,
        nRelays: Int = 5,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            PlatformLog.w(TAG, "No target relays for geohash event; falling back to defaults")
            sendEvent(event, DEFAULT_RELAYS, liveLocationToken)
            return
        }
        sendEvent(event, relayUrls, liveLocationToken)
    }

    // --- Internal helpers ---

    private fun isNetworkActionAllowed(liveLocationToken: Long?): Boolean =
        liveLocationToken == null || liveLocationGate.accepts(liveLocationToken)

    private fun runNetworkAction(
        liveLocationToken: Long?,
        action: () -> Unit
    ): Boolean = if (liveLocationToken == null) {
        action()
        true
    } else {
        liveLocationGate.runIfAllowed(liveLocationToken, action)
    }

    /**
     * Privacy teardown is allowed to bypass an already-revoked token solely to stop
     * server-side delivery. Live subscription IDs are opaque, so CLOSE carries no
     * geohash. If a CLOSE cannot be queued, fail closed by dropping that socket.
     */
    private fun closeSubscriptionsOnConnectedRelays(subscriptionIds: Set<String>) {
        if (subscriptionIds.isEmpty()) return

        val closeTargets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = subscriptionIds,
            subscriptionsByRelay = lock.withLock { relaySubscriptions.toMap() },
        )
        closeTargets.forEach { (relayUrl, relaySubscriptionIds) ->
            val connection = lock.withLock { connections[relayUrl] } ?: return@forEach
            for (subscriptionId in relaySubscriptionIds) {
                val message = NostrRequest.toJson(NostrRequest.Close(subscriptionId))
                val closeQueued = runCatching { connection.send(message) }.getOrDefault(false)
                if (!closeQueued) {
                    if (removeConnectionIfCurrent(relayUrl, connection)) {
                        lock.withLock { relaySubscriptions.remove(relayUrl) }
                        connection.terminate(NORMAL_CLOSE, "Failed to close revoked subscription", cancel = true)
                    }
                    updateRelayStatus(
                        relayUrl,
                        isConnected = false,
                        error = IllegalStateException("Failed to close revoked subscription")
                    )
                    val reconnect = lock.withLock { relayUrl in nonLiveRelayUrls }
                    if (desiredConnected && reconnect) {
                        scope.launch { connectToRelay(relayUrl, liveLocationToken = null) }
                    }
                    break
                }
            }
        }
    }

    private fun revokeLiveLocationAccess() {
        val jobs = lock.withLock {
            liveLocationConnectionJobs.toList().also { liveLocationConnectionJobs.clear() }
        }
        jobs.forEach(Job::cancel)

        val liveSubscriptionIds = lock.withLock {
            activeSubscriptions.values
                .filter { it.liveLocationToken != null }
                .mapTo(mutableSetOf()) { it.id }
        }
        closeSubscriptionsOnConnectedRelays(liveSubscriptionIds)
        lock.withLock {
            liveSubscriptionIds.forEach { activeSubscriptions.remove(it) }
            relaySubscriptions.updateAll { _, ids -> ids - liveSubscriptionIds }
        }

        messageQueue.removeLiveLocationEvents()

        val dropped = mutableListOf<Connection>()
        val cancelledReconnects = mutableListOf<Job>()
        lock.withLock {
            liveGeohashTokens.keys.forEach(geohashToRelays::remove)
            liveGeohashTokens.clear()

            val liveOnlyRelayUrls = liveLocationRelayTokens.keys.filterNotTo(mutableSetOf()) { it in nonLiveRelayUrls }
            liveOnlyRelayUrls.forEach { relayUrl ->
                connections.remove(relayUrl)?.let(dropped::add)
                relaySubscriptions.remove(relayUrl)
                reconnectJobs.remove(relayUrl)?.let(cancelledReconnects::add)
                relayStates.remove(relayUrl)
            }
            liveLocationRelayTokens.clear()
        }
        cancelledReconnects.forEach(Job::cancel)
        dropped.forEach { it.terminate(NORMAL_CLOSE, "Location access revoked", cancel = true) }
        publishRelays()
    }

    private fun ensureConnectionsFor(
        relayUrls: Set<String>,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        // Ensure relays are tracked for UI/status
        lock.withLock {
            relayUrls.forEach { url -> relayStates.getOrPut(url) { Relay(url) } }
        }
        publishRelays()

        if (!desiredConnected) return
        val job = scope.launch {
            if (!desiredConnected || !isNetworkActionAllowed(liveLocationToken)) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    if (desiredConnected &&
                        !lock.withLock { connections.containsKey(relayUrl) } &&
                        isNetworkActionAllowed(liveLocationToken)
                    ) {
                        connectToRelay(relayUrl, liveLocationToken)
                    }
                }
            }
        }
        if (liveLocationToken != null) {
            lock.withLock { liveLocationConnectionJobs.add(job) }
            job.invokeOnCompletion { lock.withLock { liveLocationConnectionJobs.remove(job) } }
        }
    }

    /**
     * Connect to all configured relays
     */
    fun connect() {
        desiredConnected = true
        val tracked = lock.withLock { relayStates.keys.toList() }
        PlatformLog.i(TAG, "Connecting to ${tracked.size} Nostr relays")
        scope.launch {
            tracked.forEach { url ->
                launch {
                    val liveToken = lock.withLock {
                        liveLocationRelayTokens[url]?.takeIf { url !in nonLiveRelayUrls }
                    }
                    if (liveToken == null || liveLocationGate.accepts(liveToken)) {
                        connectToRelay(url, liveToken)
                    }
                }
            }
        }

        // Start periodic subscription validation
        startSubscriptionValidation()
    }

    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        PlatformLog.i(TAG, "Disconnecting from all Nostr relays")
        desiredConnected = false

        // Stop subscription validation
        stopSubscriptionValidation()
        val jobs = lock.withLock {
            val all = reconnectJobs.values.toList() + liveLocationConnectionJobs
            reconnectJobs.clear()
            liveLocationConnectionJobs.clear()
            all
        }
        jobs.forEach(Job::cancel)

        val sockets = lock.withLock { connections.values.toList().also { connections.clear() } }
        sockets.forEach { it.terminate(NORMAL_CLOSE, "Manual disconnect") }

        // Preserve logical subscriptions for controlled resets, but forget per-socket state.
        lock.withLock {
            relaySubscriptions.clear()
            relayStates.updateAll { _, relay -> relay.copy(isConnected = false, nextReconnectTime = null) }
        }
        publishRelays()
    }

    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>? = null,
        liveLocationToken: Long? = null
    ) {
        val targetRelays = (relayUrls ?: lock.withLock { relayStates.keys.toList() })
            .filter { it.isNotBlank() }
            .distinct()
        if (targetRelays.isEmpty()) return

        runNetworkAction(liveLocationToken) {
            val queueId = messageQueue.enqueue(
                event = event,
                relayUrls = targetRelays,
                liveLocationToken = liveLocationToken
            ) ?: return@runNetworkAction
            scope.launch {
                if (!isNetworkActionAllowed(liveLocationToken)) return@launch
                targetRelays.forEach { relayUrl ->
                    val connection = lock.withLock { connections[relayUrl] }
                    if (connection != null) {
                        if (sendToRelay(event, connection, relayUrl, liveLocationToken)) {
                            messageQueue.markDelivered(queueId, relayUrl)
                        }
                    }
                }
            }
        }
    }

    /**
     * Subscribe to events matching a filter
     * The subscription will be automatically re-established on reconnection
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null,
        owner: String = OWNER_LEGACY,
        liveLocationToken: Long? = null
    ): String = subscribe(filter, id, handler, targetRelayUrls, owner, liveLocationToken, originGeohash = null)

    private fun subscribe(
        filter: NostrFilter,
        id: String,
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>?,
        owner: String,
        liveLocationToken: Long?,
        originGeohash: String?
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            createdAt = clock(),
            originGeohash = originGeohash,
            owner = owner,
            liveLocationToken = liveLocationToken
        )

        runNetworkAction(liveLocationToken) {
            lock.withLock { activeSubscriptions[id] = subscriptionInfo }
            sendSubscriptionToRelays(subscriptionInfo)
        }

        return id
    }

    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return
        val message = NostrRequest.toJson(
            NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        )

        scope.launch {
            if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return@launch
            val targets = lock.withLock {
                (subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList())
                    .mapNotNull { url -> connections[url]?.let { url to it } }
            }

            targets.forEach { (relayUrl, connection) ->
                try {
                    var success = false
                    runNetworkAction(subscriptionInfo.liveLocationToken) {
                        success = connection.send(message)
                        if (success) markSubscribed(relayUrl, subscriptionInfo.id)
                    }
                    if (!success) {
                        PlatformLog.w(TAG, "Failed to send subscription: WebSocket send failed")
                    }
                } catch (e: Exception) {
                    PlatformLog.e(TAG, "Failed to send subscription")
                }
            }

            if (lock.withLock { connections.isEmpty() }) {
                PlatformLog.w(TAG, "No relay connections available for subscription, will retry on reconnection")
            }
        }
    }

    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) {
        // Remove from persistent tracking
        val subscriptionInfo = lock.withLock { activeSubscriptions.remove(id) } ?: return

        if (subscriptionInfo.liveLocationToken != null &&
            !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
        ) {
            closeSubscriptionsOnConnectedRelays(setOf(id))
            lock.withLock { relaySubscriptions.updateAll { _, ids -> ids - id } }
            return
        }

        val message = NostrRequest.toJson(NostrRequest.Close(id))

        scope.launch {
            if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) {
                closeSubscriptionsOnConnectedRelays(setOf(id))
                lock.withLock { relaySubscriptions.updateAll { _, ids -> ids - id } }
                return@launch
            }
            val targets = lock.withLock {
                connections.entries
                    .filter { (url, _) -> relaySubscriptions[url]?.contains(id) == true }
                    .map { it.key to it.value }
            }
            targets.forEach { (relayUrl, connection) ->
                try {
                    runNetworkAction(subscriptionInfo.liveLocationToken) {
                        connection.send(message)
                    }
                    lock.withLock {
                        relaySubscriptions[relayUrl]?.let { relaySubscriptions[relayUrl] = it - id }
                    }
                } catch (e: Exception) {
                    PlatformLog.e(TAG, "Failed to unsubscribe from relay")
                }
            }
        }
    }

    fun unsubscribeOwner(owner: String) {
        lock.withLock { activeSubscriptions.values.filter { it.owner == owner }.map { it.id } }
            .forEach(::unsubscribe)
    }

    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        if (lock.withLock { !relayStates.containsKey(relayUrl) }) return
        desiredConnected = true
        val liveToken = lock.withLock { liveLocationRelayTokens[relayUrl]?.takeIf { relayUrl !in nonLiveRelayUrls } }
        if (!isNetworkActionAllowed(liveToken)) return

        // Reset reconnection attempts, drop any scheduled reconnect and the current socket.
        val (reconnect, current) = lock.withLock {
            relayStates[relayUrl]?.let { relayStates[relayUrl] = it.copy(reconnectAttempts = 0, nextReconnectTime = null) }
            reconnectJobs.remove(relayUrl) to connections.remove(relayUrl)
        }
        reconnect?.cancel()
        current?.terminate(NORMAL_CLOSE, "Manual retry")
        publishRelays()

        // Attempt immediate reconnection
        scope.launch {
            connectToRelay(relayUrl, liveToken)
        }
    }

    /**
     * Reset all relay connections
     * This will automatically restore all subscriptions when reconnected
     */
    fun resetAllConnections() {
        val shouldReconnect = desiredConnected
        disconnect()

        // Reset all relay states
        lock.withLock {
            relayStates.updateAll { _, relay ->
                relay.copy(reconnectAttempts = 0, nextReconnectTime = null, lastError = null)
            }
        }
        publishRelays()

        // Reconnect only when connectivity was desired before the controlled reset.
        if (shouldReconnect) connect()
    }

    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() {
        scope.launch {
            lock.withLock { connections.entries.map { it.key to it.value } }.forEach { (relayUrl, connection) ->
                restoreSubscriptionsForRelay(relayUrl, connection)
            }
        }
    }

    /**
     * Clear all subscription tracking, message handlers, routing caches, and queued messages.
     * Intended for panic/reset flows prior to reconnecting and re-subscribing from scratch.
     */
    fun clearAllSubscriptions() {
        try {
            lock.withLock {
                activeSubscriptions.clear()
                relaySubscriptions.clear()
                // Clear routing caches (per-geohash relay selections)
                geohashToRelays.clear()
            }

            // Clear any queued messages waiting to be sent
            messageQueue.clear()

            PlatformLog.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    /**
     * Clear all subscription tracking, deduplication cache, message queue, and connections for panic mode.
     */
    fun clearAllOnPanic() {
        try {
            val wasConnected = desiredConnected
            clearAllSubscriptions()
            clearDeduplicationCache()
            disconnect()
            if (wasConnected) {
                desiredConnected = true
            }
            PlatformLog.w(TAG, "Cleared NostrRelayManager subscriptions, cache, and connections for panic mode")
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to clear NostrRelayManager on panic: ${e.message}")
        }
    }

    /**
     * Get detailed status for all relays
     */
    fun getRelayStatuses(): List<Relay> = lock.withLock { relayStates.values.toList() }

    /**
     * Get event deduplication statistics
     */
    fun getDeduplicationStats(): DeduplicationStats {
        return eventDeduplicator.getStats()
    }

    /**
     * Clear the event deduplication cache (useful for testing or debugging)
     */
    fun clearDeduplicationCache() {
        eventDeduplicator.clear()
    }

    /**
     * Get the count of active subscriptions
     */
    fun getActiveSubscriptionCount(): Int = lock.withLock { activeSubscriptions.size }

    /**
     * Get information about all active subscriptions (for debugging)
     */
    fun getActiveSubscriptions(): Map<String, SubscriptionInfo> = lock.withLock { activeSubscriptions.toMap() }

    /**
     * Validate subscription consistency across all relays
     * Returns a report of any inconsistencies found
     */
    fun validateSubscriptionConsistency(): SubscriptionConsistencyReport {
        val (subscriptions, actualSubsByRelay, connectedRelays) = lock.withLock {
            Triple(activeSubscriptions.toMap(), relaySubscriptions.toMap(), connections.keys.toList())
        }
        val inconsistencies = mutableListOf<String>()

        connectedRelays.forEach { relayUrl ->
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedForRelay = expectedSubscriptionIds(subscriptions, relayUrl)

            val missing = expectedForRelay - actualSubs
            val extra = actualSubs - expectedForRelay

            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }

        return SubscriptionConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            totalActiveSubscriptions = subscriptions.size,
            connectedRelayCount = connectedRelays.size
        )
    }

    private fun expectedSubscriptionIds(
        subscriptions: Map<String, SubscriptionInfo>,
        relayUrl: String
    ): Set<String> = subscriptions.values
        .filter { it.targetRelayUrls == null || it.targetRelayUrls.contains(relayUrl) }
        .mapTo(mutableSetOf()) { it.id }

    /**
     * Start periodic subscription validation to ensure robustness
     */
    private fun startSubscriptionValidation() {
        stopSubscriptionValidation() // Stop any existing validation

        val job = scope.launch {
            validationIntervalMs()
                .distinctUntilChanged()
                .collectLatest(::runSubscriptionValidationLoop)
        }
        lock.withLock { subscriptionValidationJob = job }
    }

    private suspend fun runSubscriptionValidationLoop(intervalMs: Long) {
        while (currentCoroutineContext().isActive && desiredConnected) {
            delay(intervalMs)
            if (!desiredConnected) break
            validateAndRepairSubscriptions()
        }
    }

    private fun validateAndRepairSubscriptions() {
        try {
            val report = validateSubscriptionConsistency()
            if (report.isConsistent || report.connectedRelayCount == 0) return

            PlatformLog.w(TAG, "Nostr subscription inconsistencies detected")
            val (subscriptions, subscribedByRelay, current) = lock.withLock {
                Triple(activeSubscriptions.toMap(), relaySubscriptions.toMap(), connections.entries.map { it.key to it.value })
            }
            current.forEach { (relayUrl, connection) ->
                val currentSubs = subscribedByRelay[relayUrl] ?: emptySet()
                val expectedSubs = expectedSubscriptionIds(subscriptions, relayUrl)

                if ((expectedSubs - currentSubs).isNotEmpty()) {
                    PlatformLog.i(TAG, "Auto-repairing missing subscriptions")
                    restoreSubscriptionsForRelay(relayUrl, connection)
                }
            }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Error during subscription validation: ${e.message}")
        }
    }

    /**
     * Stop periodic subscription validation
     */
    private fun stopSubscriptionValidation() {
        lock.withLock { subscriptionValidationJob.also { subscriptionValidationJob = null } }?.cancel()
    }

    // MARK: - Connection lifecycle

    private suspend fun connectToRelay(
        urlString: String,
        liveLocationToken: Long? = null
    ) {
        if (!desiredConnected) return
        val connectionToken = liveLocationToken
            ?.takeIf { !lock.withLock { urlString in nonLiveRelayUrls } }
        if (!isNetworkActionAllowed(connectionToken)) return

        // Register before opening so no callback can ever observe an unregistered connection.
        val connection = Connection(urlString, connectionToken)
        val registered = lock.withLock {
            if (connections.containsKey(urlString)) false else {
                connections[urlString] = connection
                true
            }
        }
        if (!registered) return
        connection.startProcessing()

        try {
            val started = runNetworkAction(connectionToken) {
                connection.attach(connector.open(urlString, connection))
            }
            if (!started) {
                removeConnectionIfCurrent(urlString, connection)
                connection.terminate(NORMAL_CLOSE, "Connection no longer permitted")
                return
            }
            if (!desiredConnected && removeConnectionIfCurrent(urlString, connection)) {
                connection.terminate(NORMAL_CLOSE, "Connection no longer desired")
            }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to create WebSocket connection")
            removeConnectionIfCurrent(urlString, connection)
            connection.terminate(NORMAL_CLOSE, "Connection failed", cancel = true)
            handleConnectionCreationFailure(urlString, e, connectionToken)
        }
    }

    private fun isCurrent(connection: Connection): Boolean =
        lock.withLock { connections[connection.url] === connection }

    private fun removeConnectionIfCurrent(url: String, connection: Connection): Boolean = lock.withLock {
        if (connections[url] === connection) {
            connections.remove(url)
            true
        } else false
    }

    private fun sendToRelay(
        event: NostrEvent,
        connection: Connection,
        relayUrl: String,
        liveLocationToken: Long? = null
    ): Boolean {
        if (!isNetworkActionAllowed(liveLocationToken)) return false
        return try {
            val message = NostrRequest.toJson(NostrRequest.Event(event))

            var success = false
            runNetworkAction(liveLocationToken) {
                success = connection.send(message)
            }
            if (success) {
                updateRelay(relayUrl) { it.copy(messagesSent = it.messagesSent + 1) }
                true
            } else {
                PlatformLog.e(TAG, "Failed to send event: WebSocket send failed")
                false
            }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to send event")
            false
        }
    }

    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val response = NostrResponse.parse(message)
            if (response == null) {
                PlatformLog.w(TAG, "Received non-array message from relay")
                return
            }

            when (response) {
                is NostrResponse.Event -> {
                    updateRelay(relayUrl) { it.copy(messagesReceived = it.messagesReceived + 1) }

                    // CLIENT-SIDE FILTER ENFORCEMENT: Ensure this event matches the subscription's filter
                    val subscriptionInfo = lock.withLock { activeSubscriptions[response.subscriptionId] } ?: return
                    if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return
                    val matches = try { subscriptionInfo.filter.matches(response.event) } catch (e: Exception) { true }
                    if (!matches) {
                        // Do NOT call deduplicator here to allow the correct subscription to process it later
                        return
                    }

                    // DEDUPLICATION: Check if we've already processed this event
                    eventDeduplicator.processEvent(response.event) { event ->
                        scope.launch(handlerDispatcher) {
                            if (isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) {
                                subscriptionInfo.handler(event)
                            }
                        }
                    }
                }

                is NostrResponse.EndOfStoredEvents -> Unit
                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (!response.accepted) {
                        val reason = "Event rejected by relay: ${response.message ?: "no reason"}"
                        if (wasGiftWrap) PlatformLog.w(TAG, reason) else PlatformLog.e(TAG, reason)
                    }
                }
                is NostrResponse.Notice -> Unit
                is NostrResponse.Unknown -> Unit
            }
        } catch (e: Exception) {
            PlatformLog.e(TAG, "Failed to parse relay message")
        }
    }

    private fun handleDisconnection(
        relayUrl: String,
        connection: Connection,
        error: Throwable,
        liveLocationToken: Long? = null
    ) {
        // Ignore callbacks from intentionally closed or replaced sockets. They must not remove a
        // newer socket or schedule a reconnect after a controlled disconnect/privacy revocation.
        if (!removeConnectionIfCurrent(relayUrl, connection)) return
        lock.withLock { relaySubscriptions.remove(relayUrl) }
        handleCurrentDisconnection(relayUrl, error, liveLocationToken)
    }

    private fun handleConnectionCreationFailure(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?
    ) {
        if (!desiredConnected) return
        handleCurrentDisconnection(relayUrl, error, liveLocationToken)
    }

    private fun handleCurrentDisconnection(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { !lock.withLock { relayUrl in nonLiveRelayUrls } }

        updateRelayStatus(relayUrl, false, error)
        if (!desiredConnected || !isNetworkActionAllowed(connectionToken)) return

        // Every failure backs off and retries, including a name-resolution
        // failure: "unable to resolve host" is what this device reports when it
        // simply has no network, so treating it as permanent turns a walk
        // through a tunnel into a dead relay layer for the rest of the process.
        val backoffInterval = lock.withLock {
            val relay = relayStates[relayUrl] ?: return
            val attempts = RelayReconnectPolicy.nextAttempt(relay.reconnectAttempts)
            val backoff = RelayReconnectPolicy.backoffMs(attempts)
            relayStates[relayUrl] = relay.copy(reconnectAttempts = attempts, nextReconnectTime = clock() + backoff)
            backoff
        }
        publishRelays()

        PlatformLog.d(TAG, "Scheduling Nostr relay reconnection")

        val reconnectJob = scope.launch {
            delay(backoffInterval)
            if (desiredConnected && isNetworkActionAllowed(connectionToken)) {
                connectToRelay(relayUrl, connectionToken)
            }
        }
        lock.withLock { reconnectJobs.put(relayUrl, reconnectJob) }?.cancel()
        reconnectJob.invokeOnCompletion {
            lock.withLock { if (reconnectJobs[relayUrl] === reconnectJob) reconnectJobs.remove(relayUrl) }
        }
    }

    private fun updateRelay(url: String, transform: (Relay) -> Relay) {
        val changed = lock.withLock {
            val relay = relayStates[url] ?: return@withLock false
            relayStates[url] = transform(relay)
            true
        }
        if (changed) publishRelays()
    }

    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        val now = clock()
        updateRelay(url) { relay ->
            if (isConnected) {
                relay.copy(
                    isConnected = true,
                    lastError = error,
                    lastConnectedAt = now,
                    reconnectAttempts = 0,
                    nextReconnectTime = null
                )
            } else {
                relay.copy(isConnected = false, lastError = error, lastDisconnectedAt = now)
            }
        }
    }

    /** Publishes an immutable snapshot of relay state to collectors. */
    private fun publishRelays() {
        val snapshot = lock.withLock { relayStates.values.toList() }
        _relays.value = snapshot
        _isConnected.value = snapshot.any { it.isConnected }
    }

    private fun markSubscribed(relayUrl: String, subscriptionId: String) {
        lock.withLock { relaySubscriptions[relayUrl] = (relaySubscriptions[relayUrl] ?: emptySet()) + subscriptionId }
    }

    private fun generateSubscriptionId(): String {
        val b = CryptographyRandom.nextBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // RFC 4122 variant
        val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "sub-${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /**
     * Restore all active subscriptions for a specific relay that just reconnected
     */
    private fun restoreSubscriptionsForRelay(relayUrl: String, connection: Connection) {
        val subscriptionsToRestore = lock.withLock { activeSubscriptions.values.toList() }.filter { info ->
            // Include subscription if it targets all relays or specifically targets this relay
            isNetworkActionAllowed(info.liveLocationToken) &&
                (info.targetRelayUrls == null || info.targetRelayUrls.contains(relayUrl))
        }

        subscriptionsToRestore.forEach { info ->
            try {
                val message = NostrRequest.toJson(NostrRequest.Subscribe(info.id, listOf(info.filter)))

                var success = false
                runNetworkAction(info.liveLocationToken) {
                    success = connection.send(message)
                    if (success) markSubscribed(relayUrl, info.id)
                }
                if (!success) {
                    PlatformLog.w(TAG, "Failed to restore subscription: WebSocket send failed")
                }
            } catch (e: Exception) {
                PlatformLog.e(TAG, "Failed to restore subscription")
            }
        }
    }

    /**
     * One relay socket. Implements the listener the connector calls on arbitrary threads and turns
     * those calls into an ordered event stream that is handled only once the socket has been
     * attached, so handling never races with registration.
     */
    private inner class Connection(
        val url: String,
        private val liveLocationToken: Long?
    ) : RelaySocketListener {
        private val events = Channel<ConnectionEvent>(Channel.UNLIMITED)
        private val socketReady = CompletableDeferred<Unit>()

        @Volatile
        private var socket: RelaySocket? = null

        @Volatile
        private var terminated = false

        fun attach(socket: RelaySocket) {
            this.socket = socket
            socketReady.complete(Unit)
            // terminate() may have run before the socket existed; honour it now.
            if (terminated) socket.close(NORMAL_CLOSE, "Closed before open completed")
        }

        fun send(text: String): Boolean = socket?.send(text) ?: false

        /** Ends this connection from our side. Safe to call more than once and before [attach]. */
        fun terminate(code: Int, reason: String, cancel: Boolean = false) {
            terminated = true
            events.close()
            socketReady.complete(Unit)
            val current = socket ?: return
            if (cancel) current.cancel() else current.close(code, reason)
        }

        fun startProcessing() {
            scope.launch {
                socketReady.await()
                for (event in events) {
                    if (socket == null) break // attach never happened (open() threw)
                    when (event) {
                        ConnectionEvent.Opened -> handleOpened()
                        is ConnectionEvent.Message -> if (isCurrent(this@Connection)) handleMessage(event.text, url)
                        is ConnectionEvent.Closed -> {
                            handleDisconnection(url, this@Connection, Exception("WebSocket closed: ${event.code} ${event.reason}"), liveLocationToken)
                            break
                        }
                        is ConnectionEvent.Failed -> {
                            PlatformLog.e(TAG, "Nostr WebSocket failure")
                            handleDisconnection(url, this@Connection, event.error, liveLocationToken)
                            break
                        }
                    }
                }
            }
        }

        private fun handleOpened() {
            if (!desiredConnected || !isCurrent(this) || !isNetworkActionAllowed(liveLocationToken)) {
                removeConnectionIfCurrent(url, this)
                terminate(NORMAL_CLOSE, "Stale connection")
                return
            }
            lock.withLock { reconnectJobs.remove(url) }?.cancel()
            updateRelayStatus(url, true)

            // Restore all active subscriptions for this relay
            restoreSubscriptionsForRelay(url, this)

            // Process only events still pending for this relay, outside the queue lock.
            messageQueue.pendingForRelay(url)
                .filter { isNetworkActionAllowed(it.liveLocationToken) }
                .forEach { delivery ->
                    if (sendToRelay(delivery.event, this, url, delivery.liveLocationToken)) {
                        messageQueue.markDelivered(delivery.queueId, url)
                    }
                }
        }

        override fun onOpen() {
            events.trySend(ConnectionEvent.Opened)
        }

        override fun onMessage(text: String) {
            events.trySend(ConnectionEvent.Message(text))
        }

        override fun onClosed(code: Int, reason: String) {
            events.trySend(ConnectionEvent.Closed(code, reason))
        }

        override fun onFailure(error: Throwable) {
            events.trySend(ConnectionEvent.Failed(error))
        }
    }
}

/** What a relay socket reported, queued per connection and handled in order. */
private sealed interface ConnectionEvent {
    data object Opened : ConnectionEvent
    class Message(val text: String) : ConnectionEvent
    class Closed(val code: Int, val reason: String) : ConnectionEvent
    class Failed(val error: Throwable) : ConnectionEvent
}

/** Common replacement for the JVM-only `MutableMap.replaceAll`. */
private inline fun <K, V> MutableMap<K, V>.updateAll(transform: (K, V) -> V) {
    for (key in keys.toList()) {
        val value = this[key] ?: continue
        this[key] = transform(key, value)
    }
}
