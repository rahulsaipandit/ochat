package com.bitchat.android.nostr

/**
 * Opens WebSocket connections to Nostr relays. Android implements this over OkHttp (so Tor routing
 * and certificate policy stay in one place); Apple targets implement it over the platform stack.
 */
fun interface RelayConnector {
    /**
     * Starts connecting to [url] and returns immediately. Progress is reported to [listener] from
     * any thread, possibly before this call has returned; callers must not assume otherwise.
     *
     * Contract for implementations, per returned socket:
     * - [RelaySocketListener.onOpen] is delivered at most once, before any message.
     * - [RelaySocketListener.onMessage] is delivered serially, in arrival order.
     * - Exactly one of [RelaySocketListener.onClosed] or [RelaySocketListener.onFailure] ends the
     *   socket's life (unless it is [RelaySocket.cancel]led first), and nothing follows it.
     * - A connection failure before opening is reported through [RelaySocketListener.onFailure].
     */
    fun open(url: String, listener: RelaySocketListener): RelaySocket
}

interface RelaySocket {
    /** Queues [text] for sending. False if the socket is closed or its send queue is full. */
    fun send(text: String): Boolean

    /** Starts a graceful close handshake. */
    fun close(code: Int, reason: String)

    /** Drops the connection immediately without a close handshake. */
    fun cancel()
}

interface RelaySocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

/**
 * The consent gate for live device location, as the relay layer sees it. Geohash relay selection is
 * derived from live location, so network actions made on behalf of a location token must stop the
 * moment consent is revoked. The gate itself stays with the location code.
 */
interface LiveLocationGate {
    fun accepts(token: Long): Boolean

    /** Runs [action] only while [token] is still accepted, excluding revocation while it runs. */
    fun runIfAllowed(token: Long, action: () -> Unit): Boolean

    /** [listener] is invoked after consent is revoked or invalidated. */
    fun addRevocationListener(listener: () -> Unit)
}

/** Chooses relays near a geohash. */
fun interface RelaySelector {
    fun closestRelaysForGeohash(geohash: String, count: Int): List<String>
}
