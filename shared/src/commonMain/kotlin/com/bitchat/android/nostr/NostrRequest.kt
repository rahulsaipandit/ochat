package com.bitchat.android.nostr

import com.bitchat.android.nostr.NostrJson.asArray
import com.bitchat.android.nostr.NostrJson.asLenientBoolean
import com.bitchat.android.nostr.NostrJson.asLenientString
import com.bitchat.android.nostr.NostrJson.asObject
import kotlinx.serialization.json.JsonArray

/**
 * Nostr protocol request messages
 * Supports EVENT, REQ, and CLOSE message types
 */
sealed class NostrRequest {

    /**
     * EVENT message - publish an event
     */
    data class Event(val event: NostrEvent) : NostrRequest()

    /**
     * REQ message - subscribe to events
     */
    data class Subscribe(
        val subscriptionId: String,
        val filters: List<NostrFilter>
    ) : NostrRequest()

    /**
     * CLOSE message - close a subscription
     */
    data class Close(val subscriptionId: String) : NostrRequest()

    companion object {
        /**
         * Serialize request to the JSON array a relay expects
         */
        fun toJson(request: NostrRequest): String {
            val out = StringBuilder()
            when (request) {
                is Event -> {
                    out.append("[\"EVENT\",")
                    request.event.writeJson(out)
                    out.append(']')
                }

                is Subscribe -> {
                    out.append("[\"REQ\",")
                    NostrJson.quote(out, request.subscriptionId)
                    request.filters.forEach { filter ->
                        out.append(',')
                        filter.writeJson(out)
                    }
                    out.append(']')
                }

                is Close -> {
                    out.append("[\"CLOSE\",")
                    NostrJson.quote(out, request.subscriptionId)
                    out.append(']')
                }
            }
            return out.toString()
        }
    }
}

/**
 * Nostr protocol response messages
 * Handles EVENT, EOSE, OK, and NOTICE responses
 */
sealed class NostrResponse {

    /**
     * EVENT response - received event from subscription
     */
    data class Event(
        val subscriptionId: String,
        val event: NostrEvent
    ) : NostrResponse()

    /**
     * EOSE response - end of stored events
     */
    data class EndOfStoredEvents(
        val subscriptionId: String
    ) : NostrResponse()

    /**
     * OK response - event publication result
     */
    data class Ok(
        val eventId: String,
        val accepted: Boolean,
        val message: String?
    ) : NostrResponse()

    /**
     * NOTICE response - relay notice
     */
    data class Notice(
        val message: String
    ) : NostrResponse()

    /**
     * Unknown response type
     */
    data class Unknown(
        val raw: String
    ) : NostrResponse()

    companion object {
        /**
         * Parse a relay message. Returns null when [text] is not a JSON array at all; a well-formed
         * array that is not a recognised message becomes [Unknown].
         */
        fun parse(text: String): NostrResponse? {
            val element = NostrJson.parse(text) as? JsonArray ?: return null
            return fromJsonArray(element)
        }

        /**
         * Parse JSON array response
         */
        fun fromJsonArray(jsonArray: JsonArray): NostrResponse {
            return try {
                when (jsonArray[0].asLenientString()) {
                    "EVENT" -> {
                        if (jsonArray.size >= 3) {
                            val subscriptionId = jsonArray[1].asLenientString()
                            val event = NostrJson.parseEvent(jsonArray[2].asObject())
                            Event(subscriptionId, event)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "EOSE" -> {
                        if (jsonArray.size >= 2) {
                            EndOfStoredEvents(jsonArray[1].asLenientString())
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "OK" -> {
                        if (jsonArray.size >= 3) {
                            val eventId = jsonArray[1].asLenientString()
                            val accepted = jsonArray[2].asLenientBoolean()
                            val message = if (jsonArray.size >= 4) jsonArray[3].asLenientString() else null
                            Ok(eventId, accepted, message)
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    "NOTICE" -> {
                        if (jsonArray.size >= 2) {
                            Notice(jsonArray[1].asLenientString())
                        } else {
                            Unknown(jsonArray.toString())
                        }
                    }

                    else -> Unknown(jsonArray.toString())
                }
            } catch (e: Exception) {
                Unknown(jsonArray.toString())
            }
        }
    }
}
