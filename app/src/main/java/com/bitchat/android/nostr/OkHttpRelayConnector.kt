package com.bitchat.android.nostr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * [RelayConnector] over OkHttp. [client] is resolved on every connection so a client that honours
 * the current Tor setting (see OkHttpProvider) is always the one used.
 */
class OkHttpRelayConnector(private val client: () -> OkHttpClient) : RelayConnector {

    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        val request = Request.Builder().url(url).build()
        val webSocket = client().newWebSocket(request, Adapter(listener))
        return OkHttpRelaySocket(webSocket)
    }

    private class Adapter(private val listener: RelaySocketListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The relay has finished sending. OkHttp only reports onClosed once our side has closed
            // too, so answer the handshake; otherwise a relay that closes gracefully (restart,
            // "going away") would leave the socket half-open and never trigger a reconnect.
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
    }

    private class OkHttpRelaySocket(private val webSocket: WebSocket) : RelaySocket {
        override fun send(text: String): Boolean = webSocket.send(text)

        override fun close(code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun cancel() = webSocket.cancel()
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
