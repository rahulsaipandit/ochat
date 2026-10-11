package com.bitchat.android.nostr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSocket(val url: String, val listener: RelaySocketListener) : RelaySocket {
    val sent = mutableListOf<String>()
    var acceptSends = true
    var closeCode: Int? = null
    var cancelled = false

    val isClosed get() = closeCode != null || cancelled

    override fun send(text: String): Boolean {
        if (!acceptSends || isClosed) return false
        sent += text
        return true
    }

    override fun close(code: Int, reason: String) {
        if (closeCode == null) closeCode = code
    }

    override fun cancel() {
        cancelled = true
    }

    fun open() = listener.onOpen()
    fun receive(text: String) = listener.onMessage(text)
    fun serverClose(code: Int = 1001) = listener.onClosed(code, "bye")
    fun fail(error: Throwable = RuntimeException("network down")) = listener.onFailure(error)
}

private class FakeConnector : RelayConnector {
    val sockets = mutableListOf<FakeSocket>()
    var failOpenWith: Throwable? = null

    /** Runs inside open(), before it returns, to model a transport that reports progress early. */
    var duringOpen: ((FakeSocket) -> Unit)? = null

    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        failOpenWith?.let { throw it }
        val socket = FakeSocket(url, listener)
        sockets += socket
        duringOpen?.invoke(socket)
        return socket
    }

    fun socketsFor(url: String) = sockets.filter { it.url == url }
    fun latest(url: String) = socketsFor(url).last()
}

private class FakeGate : LiveLocationGate {
    private val accepted = mutableSetOf<Long>()
    private val listeners = mutableListOf<() -> Unit>()

    fun grant(token: Long) {
        accepted += token
    }

    fun revoke(token: Long) {
        accepted -= token
        listeners.forEach { it() }
    }

    /** Revokes without notifying listeners: the window before teardown has run. */
    fun revokeSilently(token: Long) {
        accepted -= token
    }

    override fun accepts(token: Long) = token in accepted

    override fun runIfAllowed(token: Long, action: () -> Unit): Boolean {
        if (token !in accepted) return false
        action()
        return true
    }

    override fun addRevocationListener(listener: () -> Unit) {
        listeners += listener
    }
}

private const val VALIDATION_MS = 60_000L
private const val DAMUS = "wss://relay.damus.io"
private const val PRIMAL = "wss://relay.primal.net"
private const val LIVE_RELAY = "wss://live.example"
private val PRIVATE_KEY = "11".repeat(32)

private class Harness(private val testScope: TestScope) {
    private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    /** Stands in for the UI thread: subscription handlers run only when [drainHandlers] is called. */
    private val handlerQueue = ArrayDeque<Runnable>()
    private val handlerDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            handlerQueue.addLast(block)
        }
    }
    val connector = FakeConnector()
    val gate = FakeGate()
    var nearest: List<String> = emptyList()
    val dedup = NostrEventDeduplicator(maxCapacity = 100)
    val now get() = testScope.testScheduler.currentTime

    val manager = NostrRelayManager(
        connector = connector,
        relaySelector = RelaySelector { _, _ -> nearest },
        liveLocationGate = gate,
        validationIntervalMs = { flowOf(VALIDATION_MS) },
        handlerDispatcher = handlerDispatcher,
        scope = scope,
        eventDeduplicator = dedup,
        clock = { testScope.testScheduler.currentTime },
    )

    /** Runs pending coroutines without letting subscription handlers run. */
    fun runCoroutinesOnly() = testScope.runCurrent()

    fun drainHandlers() {
        while (handlerQueue.isNotEmpty()) handlerQueue.removeFirst().run()
    }

    fun runCurrent() {
        do {
            testScope.runCurrent()
            drainHandlers()
        } while (handlerQueue.isNotEmpty())
    }

    fun advanceBy(ms: Long) = testScope.advanceTimeBy(ms)
    fun shutdown() {
        manager.disconnect()
        scope.cancel()
    }

    /** Connects and opens every socket the manager created. */
    fun connectAll() {
        manager.connect()
        runCurrent()
        connector.sockets.toList().forEach { it.open() }
        runCurrent()
    }

    fun relay(url: String) = manager.getRelayStatuses().first { it.url == url }
}

private fun signedNote(content: String, createdAt: Int = 1_700_000_000, tags: List<List<String>> = emptyList()) =
    NostrEvent.createTextNote(
        content = content,
        publicKeyHex = NostrCrypto.derivePublicKey(PRIVATE_KEY),
        privateKeyHex = PRIVATE_KEY,
        tags = tags,
        createdAt = createdAt,
    )

private fun eventMessage(subscriptionId: String, event: NostrEvent) =
    "[\"EVENT\",\"$subscriptionId\",${event.toJsonString()}]"

private fun subscribeMessage(id: String, filter: NostrFilter) =
    NostrRequest.toJson(NostrRequest.Subscribe(id, listOf(filter)))

private val textFilter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))

class NostrRelayManagerTest {

    private fun harness(block: suspend Harness.() -> Unit) = runTest {
        val h = Harness(this)
        try {
            h.block()
        } finally {
            h.shutdown()
        }
    }

    // --- connecting ---

    @Test
    fun connectOpensOneSocketPerDefaultRelayAndTracksOpenState() = harness {
        manager.connect()
        runCurrent()

        assertEquals(NostrRelayManager.defaultRelays().toSet(), connector.sockets.map { it.url }.toSet())
        assertFalse(manager.isConnected.value, "not connected until a socket reports open")

        connector.latest(DAMUS).open()
        runCurrent()

        assertTrue(manager.isConnected.value)
        assertTrue(relay(DAMUS).isConnected)
        assertFalse(relay(PRIMAL).isConnected)
    }

    @Test
    fun relayStateFlowEmitsDistinctSnapshotsOnEachChange() = harness {
        manager.connect()
        runCurrent()
        val before = manager.relays.value
        connector.latest(DAMUS).open()
        runCurrent()
        val after = manager.relays.value

        assertTrue(before.first { it.url == DAMUS } != after.first { it.url == DAMUS })
        assertFalse(before.first { it.url == DAMUS }.isConnected, "an earlier snapshot must not change")
    }

    @Test
    fun connectIsIdempotentWhileSocketsExist() = harness {
        manager.connect()
        runCurrent()
        manager.connect()
        runCurrent()

        assertEquals(NostrRelayManager.defaultRelays().size, connector.sockets.size)
    }

    @Test
    fun transportThatReportsOpenBeforeOpenReturnsStillConnects() = harness {
        manager.subscribe(textFilter, id = "early", handler = {})
        connector.duringOpen = { it.open() }

        manager.connect()
        runCurrent()

        assertTrue(manager.isConnected.value, "an early open callback must not be treated as stale")
        assertTrue(connector.sockets.none { it.isClosed })
        assertEquals(listOf(subscribeMessage("early", textFilter)), connector.latest(DAMUS).sent)
    }

    @Test
    fun failureToOpenSchedulesAReconnect() = harness {
        connector.failOpenWith = RuntimeException("no route")
        manager.connect()
        runCurrent()

        assertTrue(connector.sockets.isEmpty())
        assertEquals(1, relay(DAMUS).reconnectAttempts)
        assertEquals(now + RelayReconnectPolicy.backoffMs(1), relay(DAMUS).nextReconnectTime)

        connector.failOpenWith = null
        advanceBy(RelayReconnectPolicy.backoffMs(1))
        runCurrent()
        assertEquals(NostrRelayManager.defaultRelays().toSet(), connector.sockets.map { it.url }.toSet())
    }

    // --- reconnect ---

    @Test
    fun socketFailureReconnectsWithGrowingBackoffAndResetsOnceOpen() = harness {
        connectAll()
        val first = connector.latest(DAMUS)

        first.fail()
        runCurrent()
        assertFalse(relay(DAMUS).isConnected)
        assertEquals(1, relay(DAMUS).reconnectAttempts)
        assertEquals(now + RelayReconnectPolicy.backoffMs(1), relay(DAMUS).nextReconnectTime)

        advanceBy(RelayReconnectPolicy.backoffMs(1) - 1)
        runCurrent()
        assertEquals(1, connector.socketsFor(DAMUS).size, "must wait out the full backoff")

        advanceBy(1)
        runCurrent()
        val second = connector.latest(DAMUS)
        assertEquals(2, connector.socketsFor(DAMUS).size)

        second.fail()
        runCurrent()
        assertEquals(2, relay(DAMUS).reconnectAttempts)
        assertEquals(now + RelayReconnectPolicy.backoffMs(2), relay(DAMUS).nextReconnectTime)

        advanceBy(RelayReconnectPolicy.backoffMs(2))
        runCurrent()
        connector.latest(DAMUS).open()
        runCurrent()
        assertTrue(relay(DAMUS).isConnected)
        assertEquals(0, relay(DAMUS).reconnectAttempts)
        assertNull(relay(DAMUS).nextReconnectTime)
    }

    @Test
    fun serverInitiatedCloseAlsoReconnects() = harness {
        connectAll()
        connector.latest(DAMUS).serverClose()
        runCurrent()
        advanceBy(RelayReconnectPolicy.backoffMs(1))
        runCurrent()

        assertEquals(2, connector.socketsFor(DAMUS).size)
    }

    @Test
    fun reconnectedSocketRestoresSubscriptions() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "durable", handler = {})
        runCurrent()
        connector.latest(DAMUS).fail()
        runCurrent()
        advanceBy(RelayReconnectPolicy.backoffMs(1))
        runCurrent()
        val reopened = connector.latest(DAMUS)
        reopened.open()
        runCurrent()

        assertEquals(listOf(subscribeMessage("durable", textFilter)), reopened.sent)
    }

    // --- stale callbacks ---

    @Test
    fun callbacksFromADisconnectedSocketNeverScheduleAReconnect() = harness {
        connectAll()
        val socket = connector.latest(DAMUS)
        manager.disconnect()
        socket.fail()
        socket.serverClose()
        runCurrent()
        advanceBy(10 * 60_000)
        runCurrent()

        assertEquals(1, connector.socketsFor(DAMUS).size)
        assertEquals(1000, socket.closeCode, "disconnect closes with a normal close code")
    }

    @Test
    fun callbacksFromAReplacedSocketAreIgnored() = harness {
        connectAll()
        val old = connector.latest(DAMUS)
        manager.retryConnection(DAMUS)
        runCurrent()
        val replacement = connector.latest(DAMUS)
        replacement.open()
        runCurrent()
        assertTrue(old !== replacement)

        old.fail()
        old.receive("[\"NOTICE\",\"late\"]")
        runCurrent()

        assertTrue(relay(DAMUS).isConnected, "the replacement stays registered")
        assertEquals(0, relay(DAMUS).reconnectAttempts)
        assertEquals(1000, old.closeCode)
    }

    @Test
    fun disconnectRacingAnOpenClosesTheLateSocket() = harness {
        var duringOpenManager: NostrRelayManager? = manager
        connector.duringOpen = { duringOpenManager?.disconnect() }
        manager.connect()
        runCurrent()
        duringOpenManager = null

        assertTrue(connector.sockets.isNotEmpty())
        assertTrue(connector.sockets.all { it.isClosed }, "a socket opened after disconnect() must not leak")
    }

    // --- subscriptions ---

    @Test
    fun subscriptionIsSentToConnectedRelaysAndTracked() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "s1", handler = {})
        runCurrent()

        connector.sockets.forEach { assertEquals(listOf(subscribeMessage("s1", textFilter)), it.sent) }
        assertTrue(manager.validateSubscriptionConsistency().isConsistent)
    }

    @Test
    fun targetedSubscriptionOnlyGoesToItsRelays() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "s1", handler = {}, targetRelayUrls = listOf(PRIMAL))
        runCurrent()

        assertEquals(listOf(subscribeMessage("s1", textFilter)), connector.latest(PRIMAL).sent)
        assertTrue(connector.latest(DAMUS).sent.isEmpty())
        assertTrue(manager.validateSubscriptionConsistency().isConsistent)
    }

    @Test
    fun unsubscribeSendsCloseOnlyWhereSubscribed() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "s1", handler = {}, targetRelayUrls = listOf(PRIMAL))
        runCurrent()
        manager.unsubscribe("s1")
        runCurrent()

        assertEquals(
            listOf(subscribeMessage("s1", textFilter), NostrRequest.toJson(NostrRequest.Close("s1"))),
            connector.latest(PRIMAL).sent
        )
        assertTrue(connector.latest(DAMUS).sent.isEmpty())
        assertEquals(0, manager.getActiveSubscriptionCount())
    }

    @Test
    fun unsubscribeOwnerRemovesOnlyThatOwnersSubscriptions() = harness {
        manager.subscribe(textFilter, id = "a", handler = {}, owner = "ui")
        manager.subscribe(textFilter, id = "b", handler = {}, owner = NostrRelayManager.OWNER_BACKGROUND)
        runCurrent()
        manager.unsubscribeOwner("ui")

        assertEquals(setOf("b"), manager.getActiveSubscriptions().keys)
    }

    @Test
    fun generatedSubscriptionIdsAreUniqueUuidShapedStrings() = harness {
        val a = manager.subscribe(textFilter, handler = {})
        val b = manager.subscribe(textFilter, handler = {})

        assertTrue(a != b)
        val uuid = Regex("sub-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        assertTrue(uuid.matches(a), a)
    }

    @Test
    fun subscriptionConsistencyRepairsAMissedSubscription() = harness {
        connectAll()
        val socket = connector.latest(DAMUS)
        socket.acceptSends = false
        manager.subscribe(textFilter, id = "missed", handler = {}, targetRelayUrls = listOf(DAMUS))
        runCurrent()
        assertFalse(manager.validateSubscriptionConsistency().isConsistent)

        socket.acceptSends = true
        advanceBy(VALIDATION_MS + 1)
        runCurrent()

        assertEquals(listOf(subscribeMessage("missed", textFilter)), socket.sent)
        assertTrue(manager.validateSubscriptionConsistency().isConsistent)
    }

    @Test
    fun reestablishAllSubscriptionsResendsOnConnectedRelays() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "s1", handler = {}, targetRelayUrls = listOf(DAMUS))
        runCurrent()
        manager.reestablishAllSubscriptions()
        runCurrent()

        assertEquals(2, connector.latest(DAMUS).sent.size)
    }

    // --- inbound events ---

    @Test
    fun eventsAreDeliveredToTheSubscriptionHandlerOncePerEventId() = harness {
        connectAll()
        val received = mutableListOf<NostrEvent>()
        manager.subscribe(textFilter, id = "s1", handler = { received += it })
        runCurrent()
        val event = signedNote("hello")

        connector.latest(DAMUS).receive(eventMessage("s1", event))
        connector.latest(PRIMAL).receive(eventMessage("s1", event))
        runCurrent()

        assertEquals(listOf(event), received)
        assertEquals(1, relay(DAMUS).messagesReceived)
        assertEquals(1, relay(PRIMAL).messagesReceived)
    }

    @Test
    fun eventsThatDoNotMatchTheSubscriptionFilterAreDroppedWithoutBeingMarkedSeen() = harness {
        connectAll()
        val received = mutableListOf<NostrEvent>()
        manager.subscribe(
            NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE), since = 1_800_000_000),
            id = "s1",
            handler = { received += it }
        )
        manager.subscribe(textFilter, id = "s2", handler = { received += it })
        runCurrent()
        val old = signedNote("old", createdAt = 1_700_000_000)

        connector.latest(DAMUS).receive(eventMessage("s1", old))
        runCurrent()
        assertTrue(received.isEmpty())
        assertFalse(dedup.contains(old.id))

        connector.latest(DAMUS).receive(eventMessage("s2", old))
        runCurrent()
        assertEquals(listOf(old), received)
    }

    @Test
    fun eventsForUnknownSubscriptionsAreIgnored() = harness {
        connectAll()
        connector.latest(DAMUS).receive(eventMessage("nobody", signedNote("x")))
        runCurrent()

        assertEquals(0, dedup.size())
    }

    @Test
    fun malformedAndInformationalMessagesAreHarmless() = harness {
        connectAll()
        val socket = connector.latest(DAMUS)
        listOf(
            "not json", "{\"a\":1}", "[]", "[\"NOTICE\",\"hi\"]", "[\"EOSE\",\"s1\"]",
            "[\"OK\",\"abc\",false,\"blocked: spam\"]", "[\"OK\",\"abc\",true]", "[\"AUTH\",\"c\"]"
        ).forEach { socket.receive(it) }
        runCurrent()

        assertTrue(relay(DAMUS).isConnected)
    }

    // --- publishing ---

    @Test
    fun eventSentWhileConnectedGoesOutImmediatelyAndIsNotResentOnReconnect() = harness {
        connectAll()
        val event = signedNote("outbound")
        manager.sendEvent(event, listOf(DAMUS))
        runCurrent()

        val expected = NostrRequest.toJson(NostrRequest.Event(event))
        assertEquals(listOf(expected), connector.latest(DAMUS).sent)
        assertEquals(1, relay(DAMUS).messagesSent)

        connector.latest(DAMUS).fail()
        runCurrent()
        advanceBy(RelayReconnectPolicy.backoffMs(1))
        runCurrent()
        connector.latest(DAMUS).open()
        runCurrent()
        assertTrue(connector.latest(DAMUS).sent.isEmpty(), "a delivered event must not be queued again")
    }

    @Test
    fun eventSentWhileDisconnectedIsDeliveredWhenTheRelayOpens() = harness {
        val event = signedNote("queued")
        manager.sendEvent(event, listOf(DAMUS))
        runCurrent()
        assertTrue(connector.sockets.isEmpty())

        connectAll()

        assertEquals(listOf(NostrRequest.toJson(NostrRequest.Event(event))), connector.latest(DAMUS).sent)
        assertTrue(connector.latest(PRIMAL).sent.isEmpty(), "only the targeted relay receives it")
    }

    @Test
    fun eventWithNoUsableTargetsIsNotQueued() = harness {
        manager.sendEvent(signedNote("nowhere"), relayUrls = emptyList())
        manager.sendEvent(signedNote("blank"), relayUrls = listOf(" ", ""))
        connectAll()

        assertTrue(connector.sockets.all { it.sent.isEmpty() })
    }

    @Test
    fun eventRejectedBySendIsRetriedOnNextOpen() = harness {
        connectAll()
        val socket = connector.latest(DAMUS)
        socket.acceptSends = false
        val event = signedNote("retry")
        manager.sendEvent(event, listOf(DAMUS))
        runCurrent()
        assertTrue(socket.sent.isEmpty())

        socket.fail()
        runCurrent()
        advanceBy(RelayReconnectPolicy.backoffMs(1))
        runCurrent()
        connector.latest(DAMUS).open()
        runCurrent()

        assertEquals(listOf(NostrRequest.toJson(NostrRequest.Event(event))), connector.latest(DAMUS).sent)
    }

    // --- lifecycle ---

    @Test
    fun disconnectClosesSocketsButKeepsLogicalSubscriptions() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "keep", handler = {})
        runCurrent()
        manager.disconnect()

        assertTrue(connector.sockets.all { it.closeCode == 1000 })
        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })
        assertEquals(setOf("keep"), manager.getActiveSubscriptions().keys)
    }

    @Test
    fun resetAllConnectionsReconnectsOnlyWhenConnectionWasDesired() = harness {
        manager.resetAllConnections()
        runCurrent()
        assertTrue(connector.sockets.isEmpty())

        connectAll()
        val before = connector.sockets.size
        manager.resetAllConnections()
        runCurrent()
        assertEquals(before * 2, connector.sockets.size)
    }

    @Test
    fun retryConnectionResetsBackoffAndReconnectsImmediately() = harness {
        connectAll()
        connector.latest(DAMUS).fail()
        runCurrent()
        assertEquals(1, relay(DAMUS).reconnectAttempts)

        manager.retryConnection(DAMUS)
        runCurrent()

        assertEquals(0, relay(DAMUS).reconnectAttempts)
        assertNull(relay(DAMUS).nextReconnectTime)
        assertEquals(2, connector.socketsFor(DAMUS).size)
    }

    @Test
    fun retryConnectionForAnUntrackedRelayDoesNothing() = harness {
        manager.retryConnection("wss://not-configured.example")
        runCurrent()
        assertTrue(connector.sockets.isEmpty())
    }

    @Test
    fun clearAllOnPanicDropsSubscriptionsCacheAndSocketsButStaysDesired() = harness {
        connectAll()
        manager.subscribe(textFilter, id = "gone", handler = {})
        runCurrent()
        connector.latest(DAMUS).receive(eventMessage("gone", signedNote("seen")))
        runCurrent()
        assertEquals(1, dedup.size())

        manager.clearAllOnPanic()
        runCurrent()

        assertEquals(0, manager.getActiveSubscriptionCount())
        assertEquals(0, dedup.size())
        assertTrue(connector.sockets.all { it.isClosed })

        // Still wanted: a new relay selection connects again.
        nearest = listOf(LIVE_RELAY)
        manager.ensureGeohashRelaysConnected("u4pru")
        runCurrent()
        assertEquals(1, connector.socketsFor(LIVE_RELAY).size)
    }

    // --- geohash routing ---

    @Test
    fun geohashSubscriptionConnectsToSelectedRelaysAndTargetsThem() = harness {
        nearest = listOf(LIVE_RELAY)
        connectAll()
        val id = manager.subscribeForGeohash("u4pru", textFilter, handler = {})
        runCurrent()
        connector.latest(LIVE_RELAY).open()
        runCurrent()

        assertEquals(listOf(LIVE_RELAY), manager.getRelaysForGeohash("u4pru"))
        assertEquals(listOf(subscribeMessage(id, textFilter)), connector.latest(LIVE_RELAY).sent)
        assertEquals("u4pru", manager.getActiveSubscriptions().getValue(id).originGeohash)
        assertTrue(connector.latest(DAMUS).sent.isEmpty())
    }

    @Test
    fun geohashEventFallsBackToDefaultRelaysWhenNoneAreSelected() = harness {
        connectAll()
        val event = signedNote("fallback")
        manager.sendEventToGeohash(event, "u4pru")
        runCurrent()

        assertEquals(NostrRelayManager.defaultRelays().toSet(), connector.sockets.filter { it.sent.isNotEmpty() }.map { it.url }.toSet())
    }

    // --- live-location privacy ---

    @Test
    fun liveLocationActionsAreRefusedWithoutAnAcceptedToken() = harness {
        nearest = listOf(LIVE_RELAY)
        manager.connect()
        runCurrent()
        val before = connector.sockets.size

        val id = manager.subscribeForGeohash("u4pru", textFilter, handler = {}, liveLocationToken = 7)
        runCurrent()

        assertEquals(before, connector.sockets.size)
        assertFalse(manager.getActiveSubscriptions().containsKey(id))
        assertTrue(manager.getRelaysForGeohash("u4pru").isEmpty())
    }

    @Test
    fun revokingLocationConsentClosesLiveSubscriptionsAndDropsLiveOnlyRelays() = harness {
        gate.grant(7)
        nearest = listOf(LIVE_RELAY)
        connectAll()
        val liveEvents = mutableListOf<NostrEvent>()
        val dmEvents = mutableListOf<NostrEvent>()
        val dm = manager.subscribe(textFilter, id = "dm", handler = { dmEvents += it })
        val live = manager.subscribeForGeohash("u4pru", textFilter, handler = { liveEvents += it }, liveLocationToken = 7)
        runCurrent()
        val liveSocket = connector.latest(LIVE_RELAY)
        liveSocket.open()
        runCurrent()
        manager.sendEvent(signedNote("live"), listOf(LIVE_RELAY), liveLocationToken = 7)
        runCurrent()
        assertTrue(liveSocket.sent.any { it == subscribeMessage(live, textFilter) })

        gate.revoke(7)
        runCurrent()

        assertTrue(
            liveSocket.sent.contains(NostrRequest.toJson(NostrRequest.Close(live))) || liveSocket.isClosed,
            "server-side delivery must stop"
        )
        assertEquals(setOf(dm), manager.getActiveSubscriptions().keys)
        assertTrue(liveSocket.isClosed, "a relay used only for live location is dropped")
        assertTrue(manager.getRelayStatuses().none { it.url == LIVE_RELAY })
        assertTrue(manager.getRelaysForGeohash("u4pru").isEmpty())
        assertTrue(connector.latest(DAMUS).sent.none { it.contains("\"CLOSE\"") }, "ordinary subscriptions are untouched")
        assertTrue(liveEvents.isEmpty() && dmEvents.isEmpty())
    }

    @Test
    fun liveLocationEventsArrivingAfterRevocationAreNeverDelivered() = harness {
        gate.grant(7)
        nearest = listOf(PRIMAL)
        connectAll()
        val liveEvents = mutableListOf<NostrEvent>()
        val live = manager.subscribeForGeohash("u4pru", textFilter, handler = { liveEvents += it }, liveLocationToken = 7)
        runCurrent()
        val socket = connector.latest(PRIMAL)

        gate.revoke(7)
        socket.receive(eventMessage(live, signedNote("after revoke")))
        runCurrent()

        assertTrue(liveEvents.isEmpty())
    }

    @Test
    fun liveLocationEventReceivedInTheRevocationWindowIsRejectedOnArrival() = harness {
        gate.grant(7)
        nearest = listOf(PRIMAL)
        connectAll()
        val liveEvents = mutableListOf<NostrEvent>()
        val live = manager.subscribeForGeohash("u4pru", textFilter, handler = { liveEvents += it }, liveLocationToken = 7)
        runCurrent()

        gate.revokeSilently(7) // consent gone, teardown listener not yet run
        connector.latest(PRIMAL).receive(eventMessage(live, signedNote("in the window")))
        runCurrent()

        assertTrue(liveEvents.isEmpty())
        assertEquals(0, dedup.size(), "a rejected event must not be marked seen")
    }

    @Test
    fun liveLocationEventQueuedForDeliveryIsDroppedIfConsentIsRevokedBeforeItRuns() = harness {
        gate.grant(7)
        nearest = listOf(PRIMAL)
        connectAll()
        val liveEvents = mutableListOf<NostrEvent>()
        val live = manager.subscribeForGeohash("u4pru", textFilter, handler = { liveEvents += it }, liveLocationToken = 7)
        runCurrent()

        connector.latest(PRIMAL).receive(eventMessage(live, signedNote("queued")))
        runCoroutinesOnly() // accepted and handed to the handler dispatcher...
        gate.revokeSilently(7) // ...but consent is revoked before the handler runs
        drainHandlers()

        assertTrue(liveEvents.isEmpty())
    }

    @Test
    fun liveLocationEventIsDeliveredWhenConsentHoldsUntilTheHandlerRuns() = harness {
        gate.grant(7)
        nearest = listOf(PRIMAL)
        connectAll()
        val liveEvents = mutableListOf<NostrEvent>()
        val live = manager.subscribeForGeohash("u4pru", textFilter, handler = { liveEvents += it }, liveLocationToken = 7)
        runCurrent()
        val event = signedNote("allowed")

        connector.latest(PRIMAL).receive(eventMessage(live, event))
        runCurrent()

        assertEquals(listOf(event), liveEvents)
    }

    @Test
    fun queuedLiveLocationPublishesAreDroppedOnRevocation() = harness {
        gate.grant(7)
        nearest = listOf(LIVE_RELAY)
        manager.sendEvent(signedNote("queued live"), listOf(LIVE_RELAY), liveLocationToken = 7)
        runCurrent()

        gate.revoke(7)
        gate.grant(8) // a later consent period
        manager.ensureGeohashRelaysConnected("u4pru", liveLocationToken = 8)
        manager.connect()
        runCurrent()
        connector.sockets.toList().forEach { it.open() }
        runCurrent()

        assertTrue(connector.sockets.all { socket -> socket.sent.none { it.contains("queued live") } })
    }

    @Test
    fun aRelayAlsoUsedForNonLiveTrafficSurvivesRevocation() = harness {
        gate.grant(7)
        connectAll()
        // DAMUS is a default (non-live) relay that the live selection also picked.
        nearest = listOf(DAMUS)
        manager.ensureGeohashRelaysConnected("u4pru", liveLocationToken = 7)
        runCurrent()

        gate.revoke(7)
        runCurrent()

        assertFalse(connector.latest(DAMUS).isClosed)
        assertTrue(manager.getRelayStatuses().any { it.url == DAMUS })
    }

    @Test
    fun relayReportingOpenAfterConsentWasRevokedIsClosedAndNeverUsed() = harness {
        gate.grant(7)
        nearest = listOf(LIVE_RELAY)
        manager.connect()
        runCurrent()
        manager.ensureGeohashRelaysConnected("u4pru", liveLocationToken = 7)
        runCurrent()
        val socket = connector.latest(LIVE_RELAY)

        gate.revoke(7)
        socket.open()
        runCurrent()

        assertTrue(socket.isClosed)
        assertTrue(socket.sent.isEmpty())
    }

    // --- sockets that refuse to send ---

    @Test
    fun failingToCloseARevokedSubscriptionDropsTheSocket() = harness {
        gate.grant(7)
        nearest = listOf(LIVE_RELAY)
        connectAll()
        val id = manager.subscribeForGeohash("u4pru", textFilter, handler = {}, liveLocationToken = 7)
        runCurrent()
        val socket = connector.latest(LIVE_RELAY)
        socket.open()
        runCurrent()
        assertNotNull(socket.sent.firstOrNull { it == subscribeMessage(id, textFilter) })

        socket.acceptSends = false // CLOSE cannot be queued
        gate.revoke(7)
        runCurrent()

        assertTrue(socket.cancelled || socket.isClosed)
        assertContentEquals(emptyList(), manager.getActiveSubscriptions().keys.toList())
    }
}
