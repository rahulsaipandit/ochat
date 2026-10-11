package com.bitchat.android.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * JSON helpers for Nostr wire messages.
 *
 * Output is written by hand because the NIP-01 event id is a hash over the exact serialized bytes, and
 * the existing clients were built on Gson with HTML escaping disabled. [quote] reproduces that
 * escaping exactly (it escapes U+2028/U+2029 and never escapes `<`, `>`, `&`, `=` or `'`), so ids stay
 * stable across the Gson to common migration. Parsing uses kotlinx.serialization's [JsonElement] tree
 * and mirrors Gson's lenient accessors (see [asLenientString]).
 */
internal object NostrJson {

    fun quote(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    fun writeStringList(out: StringBuilder, values: List<String>) {
        out.append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) out.append(',')
            quote(out, v)
        }
        out.append(']')
    }

    fun writeTags(out: StringBuilder, tags: List<List<String>>) {
        out.append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) out.append(',')
            writeStringList(out, tag)
        }
        out.append(']')
    }

    /** The UTF-8 bytes Java produces for [s]: unpaired surrogates become '?'. */
    fun utf8(s: String): ByteArray {
        var needsFix = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                i += 2
                continue
            }
            if (c.isSurrogate()) {
                needsFix = true
                break
            }
            i++
        }
        if (!needsFix) return s.encodeToByteArray()
        val fixed = StringBuilder(s.length)
        i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                fixed.append(c).append(s[i + 1])
                i += 2
            } else {
                fixed.append(if (c.isSurrogate()) '?' else c)
                i++
            }
        }
        return fixed.toString().encodeToByteArray()
    }

    fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

    /**
     * Gson's `asString` on a primitive. A JSON null, object or array is an error, which callers
     * treat as a malformed message (JsonNull is a primitive in kotlinx, so it is rejected explicitly).
     */
    fun JsonElement.asLenientString(): String {
        if (this is JsonNull || this !is JsonPrimitive) throw IllegalStateException("Not a JSON primitive")
        return content
    }

    /** Gson's `asInt`: accepts numbers and numeric strings. */
    fun JsonElement.asLenientInt(): Int {
        val text = asLenientString()
        return text.toIntOrNull() ?: text.toDouble().toInt()
    }

    /** Gson's `asBoolean`: a JSON boolean, otherwise `"true"` (any case) is true and everything else false. */
    fun JsonElement.asLenientBoolean(): Boolean {
        if (this is JsonNull || this !is JsonPrimitive) throw IllegalStateException("Not a JSON primitive")
        return booleanOrNull ?: content.equals("true", ignoreCase = true)
    }

    fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: throw IllegalStateException("Not a JSON array")

    fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw IllegalStateException("Not a JSON object")

    /** Tags as Gson-era clients parsed them: a non-array tag becomes an empty tag. */
    fun parseTags(tags: JsonElement?): List<List<String>> {
        if (tags == null) return emptyList()
        return tags.asArray().map { tag ->
            if (tag is JsonArray) tag.map { it.asLenientString() } else emptyList()
        }
    }

    /** Builds an event from a JSON object, defaulting missing fields exactly like the old parser. */
    fun parseEvent(obj: JsonObject): NostrEvent = NostrEvent(
        id = obj["id"]?.asLenientString() ?: "",
        pubkey = obj["pubkey"]?.asLenientString() ?: "",
        createdAt = obj["created_at"]?.asLenientInt() ?: 0,
        kind = obj["kind"]?.asLenientInt() ?: 0,
        tags = parseTags(obj["tags"]),
        content = obj["content"]?.asLenientString() ?: "",
        sig = obj["sig"]?.asLenientString()
    )
}
