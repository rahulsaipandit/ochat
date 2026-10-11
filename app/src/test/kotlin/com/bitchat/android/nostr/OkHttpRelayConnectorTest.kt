package com.bitchat.android.nostr

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the OkHttp transport against a local WebSocket server. These pin the behaviours the
 * relay manager's reconnect logic relies on, chiefly that every way a socket can end reaches the
 * listener exactly once.
 */
@RunWith(RobolectricTestRunner::class)
class OkHttpRelayConnectorTest {
    private val server = MockWebServer()
    private val connector = OkHttpRelayConnector { OkHttpClient() }
    private val events = LinkedBlockingQueue<String>()
    private val serverReceived = LinkedBlockingQueue<String>()

    private val recorder = object : RelaySocketListener {
        override fun onOpen() { events.add("open") }
        override fun onMessage(text: String) { events.add("message:$text") }
        override fun onClosed(code: Int, reason: String) { events.add("closed:$code") }
        override fun onFailure(error: Throwable) { events.add("failure") }
    }

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun wsUrl() = server.url("/").toString().replaceFirst("http", "ws")

    private fun next(): String =
        events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("timed out waiting for a listener callback")

    private fun serverSide(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
                override fun onMessage(webSocket: WebSocket, text: String) { serverReceived.add(text) }
                // A relay answers a close frame; without this the client waits out OkHttp's close timeout.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }).build()
        )
    }

    @Test
    fun reportsOpenThenMessagesInOrder() {
        serverSide { it.send("one"); it.send("two") }

        connector.open(wsUrl(), recorder)

        assertEquals("open", next())
        assertEquals("message:one", next())
        assertEquals("message:two", next())
    }

    @Test
    fun textSentByTheClientReachesTheServer() {
        serverSide()
        val socket = connector.open(wsUrl(), recorder)
        assertEquals("open", next())

        assertTrue(socket.send("[\"REQ\",\"s\"]"))

        assertEquals("[\"REQ\",\"s\"]", serverReceived.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun serverInitiatedCloseCompletesAndReportsClosed() {
        // Without answering the closing handshake OkHttp never reports onClosed, which would leave
        // a relay that closed gracefully (restart, "going away") dead with no reconnect.
        serverSide { it.close(1001, "going away") }

        connector.open(wsUrl(), recorder)

        assertEquals("open", next())
        assertEquals("closed:1001", next())
    }

    @Test
    fun clientInitiatedCloseReportsClosedOnce() {
        serverSide()
        val socket = connector.open(wsUrl(), recorder)
        assertEquals("open", next())

        socket.close(1000, "bye")

        assertEquals("closed:1000", next())
        assertEquals(null, events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun cancelReportsAFailureAndRefusesFurtherSends() {
        serverSide()
        val socket = connector.open(wsUrl(), recorder)
        assertEquals("open", next())

        socket.cancel()

        assertEquals("failure", next())
        assertFalse(socket.send("late"))
    }

    @Test
    fun connectionRefusedIsReportedAsAFailureWithoutOpening() {
        val url = wsUrl()
        server.close()

        connector.open(url, recorder)

        assertEquals("failure", next())
        assertEquals(null, events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedUrlIsRejectedSynchronously() {
        connector.open("not a url", recorder)
    }
}
