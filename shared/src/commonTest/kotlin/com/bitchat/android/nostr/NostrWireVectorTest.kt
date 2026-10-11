package com.bitchat.android.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-format golden vectors captured from the previous Gson-based NostrEvent / NostrRequest.
 * Event ids are hashes over the serialized bytes, so escaping must match byte for byte.
 */
class NostrWireVectorTest {
    private val privateKey = "11".repeat(32)
    private val publicKey = "4f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa"

    private class IdCase(val content: String, val tags: List<List<String>>, val createdAt: Int, val kind: Int, val id: String)

    private val idCases = listOf(
        IdCase("plain", listOf(), 1700000000, 1, "d1d523c87e07a379164ea7454ab01d98c4e6c0181f855bd5465a77352899a19c"),
        IdCase("plain", listOf(listOf("p", "abc")), 1700000001, 20000, "fae37d506e9b46591b8e8b77511c4393ab38804f98aca56a9fa4cc72d52b4dc8"),
        IdCase("plain", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000002, 1, "e52d4459b1792a5c1a9ef39fa717261376f2b97a85dfa4967ada0c6bcc5360a0"),
        IdCase("", listOf(), 1700000003, 20000, "19f3c61d4ce881a318d0d4c1866f9872f9b1f9d1e60a086f8ebcb66a7108f944"),
        IdCase("", listOf(listOf("p", "abc")), 1700000004, 1, "d56b94bfd5343548dff7f1ffd90ffe8abaaf6e294a50e829d7d8400c40a00a60"),
        IdCase("", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000005, 20000, "92e72f6b2a41853d702b82150ac77080f4268035e76c1bd351bf1213c74e6d64"),
        IdCase("quote \u0022 backslash \u005c slash / newline \u000a return \u000d tab \u0009", listOf(), 1700000006, 1, "038f7d38be44fbe6588922b215e0718a42f831250fafdef9a45ea36fc89bef23"),
        IdCase("quote \u0022 backslash \u005c slash / newline \u000a return \u000d tab \u0009", listOf(listOf("p", "abc")), 1700000007, 20000, "b95ad5f688f4e31745405d6a74ffb968f20ce3ee7590660c69a45695acb61655"),
        IdCase("quote \u0022 backslash \u005c slash / newline \u000a return \u000d tab \u0009", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000008, 1, "d40ac7747283137b79c29c080382648d3a855612c4d132cd4ff0dc99f499705a"),
        IdCase("ctrl \u0001 \u001f \u0008 \u000c del \u007f", listOf(), 1700000009, 20000, "7c1c45d1418e6271bdf2c525877a1081dfc4d2dbb5c8b61084198e6c9f3e07e1"),
        IdCase("ctrl \u0001 \u001f \u0008 \u000c del \u007f", listOf(listOf("p", "abc")), 1700000010, 1, "fbb7639fd194e25d140dd35bc8fd5e419c4d17f0d23c06f41cf4be85d13edcaa"),
        IdCase("ctrl \u0001 \u001f \u0008 \u000c del \u007f", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000011, 20000, "6a1e59250cf13460c62d71906298b2242f8db05252b33b0ee035f7493142fe8b"),
        IdCase("sep \u2028 \u2029 end", listOf(), 1700000012, 1, "9d29ee3addb6979dc2fb19f472def73a68e4a664875dc97711d893fc5afadfc0"),
        IdCase("sep \u2028 \u2029 end", listOf(listOf("p", "abc")), 1700000013, 20000, "e7b23c7cc558f426ce1ee8ed8db7e4bd941e66166a3432db4ef549a336b3dca1"),
        IdCase("sep \u2028 \u2029 end", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000014, 1, "2b1bbeca26e4658ad115db9e0489361034e2a2a1c27162d6e115acbb2f4de563"),
        IdCase("html <b>&amp; ='x'</b>", listOf(), 1700000015, 20000, "05649ad36b285730d39add9996ba9a1822d2c99262f4879aaa6a472d4716d258"),
        IdCase("html <b>&amp; ='x'</b>", listOf(listOf("p", "abc")), 1700000016, 1, "e553af7a73af63ac900de4a379b7f23ef760cef6e3e8089ed0526f29ac49c7e5"),
        IdCase("html <b>&amp; ='x'</b>", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000017, 20000, "8d8d114cf08b2454d8e42aeac22f2cd9ab90c7e4e17ff2417b2e58e93202337c"),
        IdCase("emoji \ud83d\ude00 cjk \u4e2d\u6587 latin \u00e9", listOf(), 1700000018, 1, "72ee9f1a6b4e6ed00d6f0c25af2c294971ded0fe1664b2ae3f2caca27e7dca31"),
        IdCase("emoji \ud83d\ude00 cjk \u4e2d\u6587 latin \u00e9", listOf(listOf("p", "abc")), 1700000019, 20000, "191123351d4edfa074ccea95ea8e3249e478d783de8d6f1832da804a5f0ccc04"),
        IdCase("emoji \ud83d\ude00 cjk \u4e2d\u6587 latin \u00e9", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000020, 1, "094ac612232ac555c187ede91b82ef943765c01ec0f821f89f3f5d47b5679a7a"),
        IdCase("lone \ud800 surrogate", listOf(), 1700000021, 20000, "10b37fc4ee6af7aa468e95e91fc820a36126a2ade180f7a362ba8e3f7d6d010a"),
        IdCase("lone \ud800 surrogate", listOf(listOf("p", "abc")), 1700000022, 1, "fabe947d97529de5f34a9597f8a1bc2cb98bcbe8a4ae57acf23e56d80675e53a"),
        IdCase("lone \ud800 surrogate", listOf(listOf("g", "u4pruyd"), listOf("n", "nick \u0022q\u0022 <x>"), listOf("t", "teleport"), listOf("e")), 1700000023, 20000, "955cf3adf6cb1c4423f57fe4a47ecdc83bb98d86ba33484a166b1a68c6d453c8")    )
    private val signedEventJson = "{\u0022id\u0022:\u002229d0f1625b357381cc8bc5081b5b96d864bd3a3c338db18c2b51c7c1990e8a26\u0022,\u0022pubkey\u0022:\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022,\u0022created_at\u0022:1700000123,\u0022kind\u0022:1,\u0022tags\u0022:[[\u0022g\u0022,\u0022u4pruyd\u0022]],\u0022content\u0022:\u0022a\u005cu003cb\u005cu003e\u005cu0026\u005c\u0022c\u005c\u0022\u0022,\u0022sig\u0022:\u0022cae7ca3fa12d437fde0f9933121f665c1463bd354d3314ef4624ff0c114eadeb0631a8ad653a0b538f7f40100746707cc8dbbf3d1b3cab6a8ce1f93cddb5b26c\u0022}"
    private val unsignedEventJson = "{\u0022id\u0022:\u0022\u0022,\u0022pubkey\u0022:\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022,\u0022created_at\u0022:5,\u0022kind\u0022:2,\u0022tags\u0022:[],\u0022content\u0022:\u0022x\u0022}"
    private val subscribeJson = listOf(
        "[\u0022REQ\u0022,\u0022sub-1\u0022,{\u0022kinds\u0022:[1059],\u0022since\u0022:1700000000,\u0022limit\u0022:100,\u0022#p\u0022:[\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022]},{\u0022kinds\u0022:[20000,20001],\u0022limit\u0022:50,\u0022#g\u0022:[\u0022u4pruyd\u0022]}]",
        "[\u0022REQ\u0022,\u0022sub-2\u0022,{\u0022ids\u0022:[\u0022a\u0022,\u0022b\u0022],\u0022authors\u0022:[\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022],\u0022kinds\u0022:[1,2],\u0022since\u0022:1700000000,\u0022until\u0022:1800000000,\u0022limit\u0022:7,\u0022#p\u0022:[\u0022pp\u0022],\u0022#x\u0022:[\u00221\u0022,\u00222\u0022]},{\u0022ids\u0022:[\u0022e1\u0022]}]",
        "[\u0022REQ\u0022,\u0022sub-3\u0022]"
    )
    private val closeJson = "[\u0022CLOSE\u0022,\u0022sub-\u005c\u00221\u0022]"
    private val eventRequestJson = listOf(
        "[\u0022EVENT\u0022,{\u0022id\u0022:\u002229d0f1625b357381cc8bc5081b5b96d864bd3a3c338db18c2b51c7c1990e8a26\u0022,\u0022pubkey\u0022:\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022,\u0022created_at\u0022:1700000123,\u0022kind\u0022:1,\u0022tags\u0022:[[\u0022g\u0022,\u0022u4pruyd\u0022]],\u0022content\u0022:\u0022a<b>&\u005c\u0022c\u005c\u0022\u0022,\u0022sig\u0022:\u0022cae7ca3fa12d437fde0f9933121f665c1463bd354d3314ef4624ff0c114eadeb0631a8ad653a0b538f7f40100746707cc8dbbf3d1b3cab6a8ce1f93cddb5b26c\u0022}]",
        "[\u0022EVENT\u0022,{\u0022id\u0022:\u0022i\u0022,\u0022pubkey\u0022:\u00224f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa\u0022,\u0022created_at\u0022:5,\u0022kind\u0022:2,\u0022tags\u0022:[],\u0022content\u0022:\u0022x\u0022}]"
    )
    private val powCases = listOf(
        "0000abc" to 16,
        "00ff" to 8,
        "1fff" to 3,
        "2" to 2,
        "3" to 2,
        "7" to 1,
        "8" to 0,
        "f" to 0
    )

    @Test
    fun eventIdsMatchGsonSerialization() {
        for (c in idCases) {
            val event = NostrEvent(pubkey = publicKey, createdAt = c.createdAt, kind = c.kind, tags = c.tags, content = c.content)
            assertEquals(c.id, event.computeEventIdHex(), "content=${c.content}")
        }
    }

    @Test
    fun signedEventFromPreviousImplementationVerifies() {
        val event = assertNotNull(NostrEvent.fromJsonString(signedEventJson))
        assertEquals("29d0f1625b357381cc8bc5081b5b96d864bd3a3c338db18c2b51c7c1990e8a26", event.id)
        assertEquals("a<b>&\"c\"", event.content)
        assertTrue(event.isValidSignature())
        assertTrue(event.isValid())
        assertFalse(event.copy(content = "tampered").isValidSignature())
    }

    @Test
    fun serializedEventRoundTrips() {
        val signed = NostrEvent(
            pubkey = publicKey, createdAt = 1700000123, kind = 1,
            tags = listOf(listOf("g", "u4pruyd")), content = "a<b>&\"c\"   \n"
        ).sign(privateKey)
        val parsed = assertNotNull(NostrEvent.fromJsonString(signed.toJsonString()))
        assertEquals(signed, parsed)
        assertTrue(parsed.isValid())
    }

    @Test
    fun unsignedEventJsonOmitsSignature() {
        val event = NostrEvent(pubkey = publicKey, createdAt = 5, kind = 2, tags = emptyList(), content = "x")
        assertEquals(unsignedEventJson, event.toJsonString())
    }

    @Test
    fun fromJsonStringRejectsIncompleteOrInvalidJson() {
        assertNull(NostrEvent.fromJsonString("{}"))
        assertNull(NostrEvent.fromJsonString("not json"))
        assertNull(NostrEvent.fromJsonString("[]"))
        assertNull(NostrEvent.fromJsonString("{\"pubkey\":\"p\",\"created_at\":1,\"kind\":1,\"content\":\"c\"}"))
    }

    @Test
    fun subscribeAndCloseRequestsMatchPreviousWireFormat() {
        val f1 = NostrFilter.giftWrapsFor(publicKey, 1_700_000_000_000L)
        val f2 = NostrFilter.geohashEphemeral("u4pruyd", null, 50)
        val f3 = NostrFilter.Builder().ids("a", "b").authors(publicKey).kinds(1, 2)
            .since(1_700_000_000_000L).until(1_800_000_000_000L).limit(7).tagP("pp").tag("x", "1", "2").build()
        val f4 = NostrFilter.forEvents(listOf("e1"))
        assertEquals(subscribeJson[0], NostrRequest.toJson(NostrRequest.Subscribe("sub-1", listOf(f1, f2))))
        assertEquals(subscribeJson[1], NostrRequest.toJson(NostrRequest.Subscribe("sub-2", listOf(f3, f4))))
        assertEquals(subscribeJson[2], NostrRequest.toJson(NostrRequest.Subscribe("sub-3", emptyList())))
        assertEquals(closeJson, NostrRequest.toJson(NostrRequest.Close("sub-\"1")))
    }

    @Test
    fun eventRequestsMatchPreviousWireFormat() {
        val signed = assertNotNull(NostrEvent.fromJsonString(signedEventJson))
        assertEquals(eventRequestJson[0], NostrRequest.toJson(NostrRequest.Event(signed)))
        val unsigned = NostrEvent(id = "i", pubkey = publicKey, createdAt = 5, kind = 2, tags = emptyList(), content = "x")
        assertEquals(eventRequestJson[1], NostrRequest.toJson(NostrRequest.Event(unsigned)))
    }

    @Test
    fun parsesRelayMessagesLikePreviousImplementation() {
        val signed = assertNotNull(NostrEvent.fromJsonString(signedEventJson))
        val event = NostrResponse.parse("[\"EVENT\",\"s1\",$signedEventJson]") as NostrResponse.Event
        assertEquals("s1", event.subscriptionId)
        assertEquals(signed, event.event)

        // A non-array tag becomes an empty tag; missing sig stays null.
        val lenient = NostrResponse.parse(
            "[\"EVENT\",\"s1\",{\"id\":\"x\",\"pubkey\":\"p\",\"created_at\":9,\"kind\":1," +
                "\"tags\":[[\"a\",\"b\"],\"bad\",[]],\"content\":\"c\"}]"
        ) as NostrResponse.Event
        assertEquals(NostrEvent("x", "p", 9, 1, listOf(listOf("a", "b"), emptyList(), emptyList()), "c", null), lenient.event)

        // Missing fields default rather than fail.
        val sparse = NostrResponse.parse("[\"EVENT\",\"s1\",{\"id\":\"x\"}]") as NostrResponse.Event
        assertEquals(NostrEvent("x", "", 0, 0, emptyList(), "", null), sparse.event)

        assertEquals(NostrResponse.EndOfStoredEvents("s1"), NostrResponse.parse("[\"EOSE\",\"s1\"]"))
        assertEquals(NostrResponse.Ok("id1", true, null), NostrResponse.parse("[\"OK\",\"id1\",true]"))
        assertEquals(NostrResponse.Ok("id1", false, "blocked: spam"), NostrResponse.parse("[\"OK\",\"id1\",false,\"blocked: spam\"]"))
        assertEquals(NostrResponse.Ok("id1", false, null), NostrResponse.parse("[\"OK\",\"id1\",\"yes\"]"))
        assertEquals(NostrResponse.Ok("id1", true, null), NostrResponse.parse("[\"OK\",\"id1\",\"TRUE\"]"))
        assertEquals(NostrResponse.Notice("hello"), NostrResponse.parse("[\"NOTICE\",\"hello\"]"))
    }

    @Test
    fun unrecognisedOrTruncatedRelayMessagesAreUnknown() {
        for (text in listOf(
            "[\"EVENT\",\"s1\"]", "[\"EOSE\"]", "[\"OK\",\"id1\"]", "[\"NOTICE\"]", "[\"AUTH\",\"challenge\"]", "[]", "[1,2]",
            "[\"EVENT\",\"s1\",{\"id\":null}]", "[\"EVENT\",\"s1\",[]]"
        )) {
            assertTrue(NostrResponse.parse(text) is NostrResponse.Unknown, text)
        }
    }

    @Test
    fun nonArrayRelayMessagesAreRejected() {
        assertNull(NostrResponse.parse("{\"a\":1}"))
        assertNull(NostrResponse.parse("not json"))
        assertNull(NostrResponse.parse("\"EOSE\""))
    }

    @Test
    fun filterMatchesEventsByEveryCriterion() {
        val event = NostrEvent("id1", "pk", 100, 1, listOf(listOf("g", "u4pr"), listOf("p", "x")), "c")
        assertTrue(NostrFilter.Builder().ids("id1").authors("pk").kinds(1).tagG("u4pr").build().matches(event))
        assertFalse(NostrFilter.Builder().ids("other").build().matches(event))
        assertFalse(NostrFilter.Builder().authors("other").build().matches(event))
        assertFalse(NostrFilter.Builder().kinds(2).build().matches(event))
        assertFalse(NostrFilter.Builder().tagG("zzzz").build().matches(event))
        assertFalse(NostrFilter(since = 101).matches(event))
        assertFalse(NostrFilter(until = 99).matches(event))
        assertEquals("u4pr", NostrFilter.geohashMessages("u4pr").getGeohash())
    }

    @Test
    fun proofOfWorkDifficultyCountsLeadingZeroBits() {
        for ((id, expected) in powCases) assertEquals(expected, NostrProofOfWork.calculateDifficulty(id), id)
    }
}
