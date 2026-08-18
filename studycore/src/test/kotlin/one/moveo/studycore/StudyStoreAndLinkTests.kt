package one.moveo.studycore

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
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
}
