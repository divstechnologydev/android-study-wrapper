package one.moveo.studycore

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StudyStoreTests {
    private lateinit var kv: InMemoryKeyValueStore

    @Before
    fun setUp() {
        kv = InMemoryKeyValueStore()
    }

    private fun makeStore() = StudyStore(store = kv, participantIdStore = InMemoryParticipantIdStore())

    private fun sampleConfig(): StudyConfig {
        val result = ConfigValidator.validate(Fixtures.text("valid-full.json"))
        return checkNotNull(result.configOrNull) { "fixture must validate" }
    }

    @Test
    fun activeStudyRoundTripAcrossInstances() {
        val store = makeStore()
        assertNull(store.activeStudy)
        val study = ActiveStudy(
            code = "TESTCODE1234", config = sampleConfig(),
            enrolledAt = Instant.ofEpochSecond(1_700_000_000), targetFired = true,
        )
        store.activeStudy = study
        // A second store over the same backing sees the identical record —
        // this is the "survives app restart" property.
        assertEquals(study, makeStore().activeStudy)
        store.activeStudy = null
        assertNull(makeStore().activeStudy)
    }

    @Test
    fun activeStudyRoundTripsEnrollmentAndTransactionIds() {
        val store = makeStore()
        val study = ActiveStudy(
            code = "TESTCODE1234", config = sampleConfig(),
            enrolledAt = Instant.ofEpochSecond(1_700_000_000),
            enrollmentId = "e_8d5f0c1e-2b3a-4c5d-8e9f-0a1b2c3d4e5f",
            transactionId = "tx_8f31",
        )
        store.activeStudy = study
        val reloaded = checkNotNull(makeStore().activeStudy)
        assertEquals(study, reloaded)
        assertEquals("e_8d5f0c1e-2b3a-4c5d-8e9f-0a1b2c3d4e5f", reloaded.enrollmentId)
        assertEquals("tx_8f31", reloaded.transactionId)
    }

    @Test
    fun activeStudyWrittenBeforeIdsExistedStillDecodes() {
        // Upgrade path: a record persisted by a build that predates
        // enrollmentId/transactionId has neither key. It must decode with
        // both null — the participant keeps their study across the update.
        val store = makeStore()
        store.activeStudy = ActiveStudy(
            code = "TESTCODE1234", config = sampleConfig(),
            enrolledAt = Instant.ofEpochSecond(1_700_000_000),
            enrollmentId = "e_x", transactionId = "tx_x", targetFired = true,
        )
        val json = Json.parseToJsonElement(checkNotNull(kv.getString("activeStudy"))).jsonObject
        assertTrue("precondition: ids were written", json.containsKey("enrollmentId"))
        kv.putString("activeStudy", JsonObject(json - "enrollmentId" - "transactionId").toString())

        val legacy = checkNotNull(makeStore().activeStudy)
        assertEquals("TESTCODE1234", legacy.code)
        assertTrue(legacy.targetFired)
        assertNull(legacy.enrollmentId)
        assertNull(legacy.transactionId)
    }

    @Test
    fun absentIdsAreOmittedNotNullInStorage() {
        // Extension parity for the persisted shape: a typed-code activation
        // writes no transactionId key at all (explicitNulls = false).
        makeStore().activeStudy = ActiveStudy(code = "TESTCODE1234", config = sampleConfig())
        val json = Json.parseToJsonElement(checkNotNull(kv.getString("activeStudy"))).jsonObject
        assertFalse(json.containsKey("transactionId"))
        assertFalse(json.containsKey("enrollmentId"))
    }

    @Test
    fun consentAndEndedStudyRoundTrip() {
        val store = makeStore()
        val consent = ConsentRecord(
            code = "C1", acceptedAt = Instant.ofEpochSecond(1_700_000_000),
            textVersion = "android-2026-08-18",
        )
        store.consent = consent
        assertEquals(consent, makeStore().consent)

        val ended = EndedStudy(
            code = "C1", name = "Study", endedAt = Instant.ofEpochSecond(1_700_000_500),
            leadOutUrl = "https://x.example/exit",
        )
        store.endedStudy = ended
        assertEquals(ended, makeStore().endedStudy)
    }

    @Test
    fun ownTagHosts() {
        val store = makeStore()
        assertEquals(emptyMap<String, Instant>(), store.ownTagHosts)
        val whenDetected = Instant.ofEpochSecond(1_700_000_000)
        store.ownTagHosts = mapOf("shop.example.com" to whenDetected)
        assertEquals(whenDetected, makeStore().ownTagHosts["shop.example.com"])
    }

    @Test
    fun corruptStorageReturnsNull() {
        kv.putString("activeStudy", "garbage")
        assertNull(makeStore().activeStudy)
    }

    @Test
    fun participantIdShapeAndStability() {
        val store = makeStore()
        val id = store.participantId()
        assertTrue(id.startsWith("p_"))
        assertNotNull("p_ + UUID", UUID.fromString(id.removePrefix("p_")))
        assertEquals("stable across calls", id, store.participantId())
    }
}

class SetupLinkTests {
    @Test
    fun customScheme() {
        assertEquals("TESTCODE1234", SetupLink.code(from = "moveoone://config/TESTCODE1234"))
        assertEquals("normalized", "TESTCODE1234", SetupLink.code(from = "moveoone://config/test-code-1234"))
        assertEquals("4831", SetupLink.code(from = "moveoone://config/4831"))
        assertNull("too short", SetupLink.code(from = "moveoone://config/ab"))
        assertNull("wrong host", SetupLink.code(from = "moveoone://settings/x"))
    }

    @Test
    fun appLinkShape() {
        assertEquals(
            "7TQ2M4K9XW3FZ",
            SetupLink.code(from = "https://app.moveo.one/extension/config/7TQ2-M4K9-XW3FZ"),
        )
        assertEquals(
            "mock landing page URL works for QA",
            "TESTCODE1234",
            SetupLink.code(from = "http://localhost:8787/extension/config/TESTCODE1234"),
        )
        assertNull(SetupLink.code(from = "https://app.moveo.one/pricing"))
        assertNull("missing code", SetupLink.code(from = "https://app.moveo.one/extension/config/"))
    }

    // MARK: - transaction id (extension enrollment-id branch parity)

    @Test
    fun transactionIdOnBothLinkShapes() {
        assertEquals(
            SetupLink.Parsed(code = "7TQ2M4K9XW3FZ", transactionId = "tx_8f31-AbC_9"),
            SetupLink.parse("https://app.moveo.one/extension/config/7TQ2-M4K9-XW3FZ?transaction_id=tx_8f31-AbC_9"),
        )
        assertEquals(
            SetupLink.Parsed(code = "TESTCODE1234", transactionId = "tx_8f31"),
            SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=tx_8f31"),
        )
        assertEquals(
            "other params ignored",
            SetupLink.Parsed(code = "TESTCODE1234", transactionId = "abc"),
            SetupLink.parse("https://dev-app.moveo.one/extension/config/TESTCODE1234?utm_source=panel&transaction_id=abc"),
        )
    }

    @Test
    fun noTransactionIdIsNull() {
        assertEquals(
            SetupLink.Parsed(code = "TESTCODE1234", transactionId = null),
            SetupLink.parse("https://app.moveo.one/extension/config/TESTCODE1234"),
        )
        assertNull("empty value", SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=")?.transactionId)
        assertNull("no value", SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id")?.transactionId)
        assertNull("wrong name", SetupLink.parse("moveoone://config/TESTCODE1234?transactionId=abc")?.transactionId)
    }

    @Test
    fun invalidTransactionIdDegradesToNullButCodeStillParses() {
        // setup-link.js: invalid values must never block activation.
        val bad = SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=bad%20id!")
        assertEquals("TESTCODE1234", bad?.code)
        assertNull(bad?.transactionId)
        assertNull("dot not allowed", SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=a.b")?.transactionId)
        assertNull("non-ASCII", SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=caf%C3%A9")?.transactionId)
        val tooLong = "a".repeat(257)
        assertNull(SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=$tooLong")?.transactionId)
        val maxLen = "a".repeat(256)
        assertEquals(maxLen, SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=$maxLen")?.transactionId)
    }

    @Test
    fun percentEncodedTransactionIdIsDecodedBeforeValidation() {
        // A provider that encodes the (already-safe) id still yields the plain value.
        assertEquals(
            "tx_8f31-x",
            SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=tx%5F8f31%2Dx")?.transactionId,
        )
    }

    @Test
    fun firstTransactionIdOccurrenceWins() {
        // URLSearchParams.get semantics in the extension — including an
        // empty first value, which yields "no transaction id".
        assertEquals(
            "first",
            SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=first&transaction_id=second")?.transactionId,
        )
        assertNull(SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=&transaction_id=second")?.transactionId)
    }

    @Test
    fun invalidCodeIsNullEvenWithTransactionId() {
        assertNull(SetupLink.parse("moveoone://config/ab?transaction_id=tx"))
    }

    @Test
    fun queryRejectedByUriStillYieldsTheCode() {
        // Android-only hardening: a raw query java.net.URI cannot parse
        // (literal space, `|`, braces) must not swallow the code — the query
        // is parsed separately from the path, like location.search vs
        // location.pathname in setup-link.js.
        val parsed = SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=bad id|{}")
        assertEquals("TESTCODE1234", parsed?.code)
        assertNull(parsed?.transactionId)
        assertEquals(
            "a valid id survives a hostile sibling param",
            "tx_ok",
            SetupLink.parse("https://app.moveo.one/extension/config/TESTCODE1234?x=a b|&transaction_id=tx_ok")?.transactionId,
        )
        assertEquals("fragment ignored", "tx_ok", SetupLink.parse("moveoone://config/TESTCODE1234?transaction_id=tx_ok#frag")?.transactionId)
    }
}

class TransactionIdTests {
    @Test
    fun normalize() {
        assertEquals("abcXYZ019_-", TransactionId.normalize("abcXYZ019_-"))
        assertNull(TransactionId.normalize(null))
        assertNull(TransactionId.normalize(""))
        assertNull(TransactionId.normalize(" tx"))
        assertNull(TransactionId.normalize("tx=1"))
        assertNull(TransactionId.normalize("z".repeat(257)))
        assertEquals(256, TransactionId.normalize("z".repeat(256))?.length)
    }
}
