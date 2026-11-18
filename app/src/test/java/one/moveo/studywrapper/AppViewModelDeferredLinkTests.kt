package one.moveo.studywrapper

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import one.moveo.studycore.InMemoryKeyValueStore
import one.moveo.studycore.InMemoryParticipantIdStore
import one.moveo.studycore.StudyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// Deferred setup link (docs/plan.md §a1.5): the install referrer is read
/// once per install, only into an empty app, and the recovered link takes
/// the ordinary link path (fetch → consent → enroll).
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelDeferredLinkTests {
    private lateinit var server: MockWebServer
    private lateinit var kv: InMemoryKeyValueStore
    private val logs = mutableListOf<String>()
    private val enrollBodies = mutableListOf<JsonObject>()
    private var configRequests = 0
    private var referrerFetches = 0

    companion object {
        const val CODE = "TESTCODE1234"
        const val OTHER = "OTHERCODE001"
        const val REFERRER = "moveo_code=$CODE&transaction_id=tx_ref_001"

        private fun config(name: String) = """
            {
              "schemaVersion": 1,
              "study": { "id": "st_test", "name": "$name", "status": "active" },
              "tracking": {
                "token": "MOVEO_PROJECT_TOKEN_EXAMPLE",
                "apiUrl": "https://api.moveo.one/api/analytic/event/tag",
                "origins": ["example.com"],
                "deploymentType": "STATIC_WEBSITE",
                "appVersion": "1.0.0"
              },
              "flow": {}
            }
        """.trimIndent()
    }

    @Before
    fun setUp() {
        kv = InMemoryKeyValueStore()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/enroll") -> {
                        enrollBodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        MockResponse().setResponseCode(201)
                            .setBody("""{"participantId":"p_abc","enrolledAt":"2026-08-27T10:00:00Z"}""")
                    }
                    path.endsWith("/$CODE") || path.endsWith("/$OTHER") -> {
                        configRequests += 1
                        MockResponse().setResponseCode(200)
                            .setBody(config(if (path.endsWith("/$CODE")) "Study A" else "Study B"))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /// Every model shares `kv` — a second one is "the next process".
    private fun TestScope.makeModel(
        referrer: String? = REFERRER,
        fetch: (suspend () -> String?)? = { referrerFetches += 1; referrer },
    ): AppViewModel {
        val store = StudyStore(store = kv, participantIdStore = InMemoryParticipantIdStore("p_abc"))
        store.apiBaseOverride = server.url("/api/v1/extension-config").toString()
        return AppViewModel(
            store = store,
            appVersion = "9.9.9",
            isDebugBuild = true,
            debugLog = { logs += it },
            scope = backgroundScope,
            installReferrer = fetch,
        )
    }

    private suspend fun AppViewModel.awaitPending(): AppViewModel.PendingActivation =
        (phase.first { it is AppViewModel.Phase.Consent } as AppViewModel.Phase.Consent).pending

    private fun consumed() = kv.getString("deferredLinkConsumed") != null

    @Test
    fun validReferrerActivatesWithTheTransactionId() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.checkDeferredLinkNow()
        val pending = model.awaitPending()
        assertEquals(CODE, pending.code)
        assertEquals("tx_ref_001", pending.transactionId)
        assertNull("consent first — nothing is active yet", model.activeStudy.value)
        assertTrue("no enroll before accept", enrollBodies.isEmpty())
        assertTrue(consumed())

        model.acceptConsentNow()
        assertEquals("tx_ref_001", model.activeStudy.value?.transactionId)
        assertEquals("tx_ref_001", enrollBodies.single()["transactionId"]?.jsonPrimitive?.content)
        assertFalse("raw referrer never logged", logs.any { it.contains("moveo_code") })
    }

    @Test
    fun garbageReferrerDoesNothing() = runTest(timeout = 30.seconds) {
        for (referrer in listOf("utm_source=google-play&utm_medium=organic", "moveo_code=%ZZ", "moveo_code=!!", "")) {
            kv = InMemoryKeyValueStore()
            val model = makeModel(referrer = referrer)
            model.checkDeferredLinkNow()
            advanceUntilIdle()
            assertEquals(referrer, AppViewModel.Phase.Idle, model.phase.value)
            assertEquals(referrer, "", model.codeInput.value)
            assertTrue(referrer, consumed())
        }
        assertEquals(0, configRequests)
    }

    @Test
    fun runsOnlyOncePerInstall() = runTest(timeout = 30.seconds) {
        val first = makeModel()
        first.checkDeferredLinkNow()
        first.awaitPending()
        first.declineConsent()
        assertEquals(1, referrerFetches)

        val second = makeModel()
        second.checkDeferredLinkNow()
        second.checkDeferredLink()
        advanceUntilIdle()
        assertEquals("referrer not read again", 1, referrerFetches)
        assertEquals(AppViewModel.Phase.Idle, second.phase.value)
        assertEquals(1, configRequests)
    }

    @Test
    fun flagSurvivesLeavingTheStudy() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.checkDeferredLinkNow()
        model.awaitPending()
        model.acceptConsentNow()
        model.leaveStudy()
        assertTrue(consumed())
        model.checkDeferredLinkNow()
        assertEquals(1, referrerFetches)
        assertNull(model.activeStudy.value)
    }

    @Test
    fun skippedWhenAStudyIsActive() = runTest(timeout = 30.seconds) {
        val earlier = makeModel(fetch = null)
        earlier.handleOpenUrl("moveoone://config/$OTHER")
        earlier.awaitPending()
        earlier.acceptConsentNow()
        assertFalse("no seam, no check", consumed())

        val model = makeModel()
        model.checkDeferredLinkNow()
        advanceUntilIdle()
        assertEquals(0, referrerFetches)
        assertEquals(OTHER, model.activeStudy.value?.code)
        assertEquals(AppViewModel.Phase.Idle, model.phase.value)
        assertTrue("still once per install", consumed())
    }

    @Test
    fun skippedWhenALinkWasHandledFirst() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        // MainActivity order: the launch intent's link, then the check.
        model.handleOpenUrl("moveoone://config/$OTHER?transaction_id=tx_link")
        model.checkDeferredLinkNow()
        val pending = model.awaitPending()
        assertEquals(OTHER, pending.code)
        assertEquals("tx_link", pending.transactionId)
        assertEquals(0, referrerFetches)
        assertTrue(consumed())

        // Even once that activation is over, the referrer stays unused.
        model.declineConsent()
        model.checkDeferredLinkNow()
        assertEquals(0, referrerFetches)
        assertEquals(AppViewModel.Phase.Idle, model.phase.value)
    }

    @Test
    fun linkArrivingDuringTheFetchWins() = runTest(timeout = 30.seconds) {
        lateinit var model: AppViewModel
        model = makeModel(fetch = {
            model.handleOpenUrl("moveoone://config/$OTHER")
            REFERRER
        })
        model.checkDeferredLinkNow()
        assertEquals(OTHER, model.awaitPending().code)
    }

    @Test
    fun nullOrFailingFetchStillSetsTheFlag() = runTest(timeout = 30.seconds) {
        val model = makeModel(referrer = null)
        model.checkDeferredLinkNow()
        assertTrue(consumed())
        assertEquals(AppViewModel.Phase.Idle, model.phase.value)

        kv = InMemoryKeyValueStore()
        val throwing = makeModel(fetch = { error("service unavailable") })
        throwing.checkDeferredLinkNow()
        assertTrue(consumed())
        assertEquals(AppViewModel.Phase.Idle, throwing.phase.value)
        assertEquals(0, configRequests)
    }
}
