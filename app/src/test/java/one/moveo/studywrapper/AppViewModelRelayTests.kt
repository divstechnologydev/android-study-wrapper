package one.moveo.studywrapper

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import one.moveo.studycore.InMemoryKeyValueStore
import one.moveo.studycore.InMemoryParticipantIdStore
import one.moveo.studycore.SiteInput
import one.moveo.studycore.StudyStore
import one.moveo.studycore.TagRelay
import one.moveo.studycore.generated.TagEndpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// The model's half of the CSP relay (← iOS `AppModel.relayTagRequest`):
/// the study-derived URL/token, the demo and no-study refusals, and the
/// DEBUG knock-on effects — the ingest redirect applies natively (the
/// page-world fetch rewrite cannot see relayed requests) and the event spy
/// seam sees relayed batches. The builder's own cases live in studycore's
/// `TagRelayTests`.
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelRelayTests {
    private lateinit var server: MockWebServer
    private lateinit var store: StudyStore
    private val observed = mutableListOf<Triple<String, String, String>>()
    private val ingestRequests = java.util.Collections.synchronizedList(mutableListOf<RecordedRequest>())

    companion object {
        const val CODE = "TESTCODE1234"
        const val TOKEN = "MOVEO_PROJECT_TOKEN_EXAMPLE"
        const val SENDER = "www.example.com"
        const val EVENTS = """{"events":[{"type":"track","prop":{"eA":"click","eV":"Buy now"}}]}"""

        private val CONFIG = """
            {
              "schemaVersion": 1,
              "study": { "id": "st_test", "name": "Study A", "status": "active" },
              "tracking": {
                "token": "$TOKEN",
                "apiUrl": "${TagEndpoint.apiUrl}",
                "origins": ["example.com"]
              }
            }
        """.trimIndent()
    }

    @Before
    fun setUp() {
        store = StudyStore(store = InMemoryKeyValueStore(), participantIdStore = InMemoryParticipantIdStore("p_abc"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/ingest/api/analytic/event/tag" -> {
                        ingestRequests += request
                        MockResponse().setResponseCode(200).setBody("""{"accepted":true}""")
                    }
                    path == "/api/my-ip" -> {
                        ingestRequests += request
                        MockResponse().setResponseCode(200).setBody("""{"ip":"203.0.113.9"}""")
                    }
                    path.endsWith("/enroll") -> MockResponse().setResponseCode(201).setBody("""{"participantId":"p_abc"}""")
                    path.endsWith("/$CODE") -> MockResponse().setResponseCode(200).setBody(CONFIG)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        store.apiBaseOverride = server.url("/api/v1/extension-config").toString()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun TestScope.makeModel(debug: Boolean = true): AppViewModel = AppViewModel(
        store = store,
        appVersion = "9.9.9",
        isDebugBuild = debug,
        relayedEventsObserver = { url, host, body -> observed += Triple(url, host, body) },
        scope = backgroundScope,
    )

    private suspend fun AppViewModel.enroll() {
        codeInput.value = CODE
        activateNow()
        phase.first { it is AppViewModel.Phase.Consent }
        acceptConsentNow()
        checkNotNull(activeStudy.value)
    }

    /// Points the DEBUG ingest redirect at the mock server, like a dev study
    /// (or `MOVEO_INGEST_OVERRIDE`) would.
    private fun AppViewModel.redirectIngestToMock() {
        ingestOverride = server.url("/ingest/api/analytic/event/tag").toString()
    }

    private fun message(kind: String, body: String? = null): JsonObject = buildJsonObject {
        put("id", "r-1")
        put("kind", kind)
        body?.let { put("body", it) }
    }

    @Test
    fun relayedEventsReachTheIngestHostWithTheStudyTokenAndAreMirroredToTheSpy() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enroll()
        model.redirectIngestToMock()

        val reply = model.relayTagRequestNow(message("events", EVENTS), senderHost = SENDER)
        assertEquals(TagRelay.Reply(ok = true, status = 200, body = """{"accepted":true}"""), reply)

        val request = ingestRequests.single()
        assertEquals("POST", request.method)
        assertEquals("/ingest/api/analytic/event/tag", request.path)
        assertEquals("token comes from the active study", TOKEN, request.getHeader("Authorization"))
        assertEquals(EVENTS, request.body.readUtf8())

        // DEBUG spy seam: the batch, where it went, and the page it came from.
        assertEquals(
            listOf(Triple(model.ingestOverride!!, "www.example.com", EVENTS)),
            observed,
        )
    }

    @Test
    fun ipLookupFollowsTheRedirectedHostAndIsNotMirrored() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enroll()
        model.redirectIngestToMock()

        val reply = model.relayTagRequestNow(message("ip"), senderHost = "Secure.Checkout.Example.com")
        assertEquals(TagRelay.Reply(ok = true, status = 200, body = """{"ip":"203.0.113.9"}"""), reply)
        val request = ingestRequests.single()
        assertEquals("GET", request.method)
        assertEquals("/api/my-ip", request.path)
        assertNull(request.getHeader("Authorization"))
        assertTrue("only event batches are study data worth capturing", observed.isEmpty())
    }

    @Test
    fun spyIsToldTheLowercasedSenderHost() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enroll()
        model.redirectIngestToMock()
        model.relayTagRequestNow(message("events", EVENTS), senderHost = "WWW.Example.COM")
        assertEquals("www.example.com", observed.single().second)
    }

    @Test
    fun offOriginSenderIsRefusedWithoutANetworkCall() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enroll()
        model.redirectIngestToMock()

        assertEquals(TagRelay.Reply.FAILED, model.relayTagRequestNow(message("events", EVENTS), senderHost = "evil.example"))
        assertEquals(TagRelay.Reply.FAILED, model.relayTagRequestNow(message("ip"), senderHost = ""))
        assertTrue(ingestRequests.isEmpty())
        assertTrue("refused requests never reach the spy", observed.isEmpty())
    }

    @Test
    fun noActiveStudyRefuses() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.redirectIngestToMock()
        assertEquals(TagRelay.Reply.FAILED, model.relayTagRequestNow(message("events", EVENTS), senderHost = SENDER))
        assertTrue(ingestRequests.isEmpty())
    }

    @Test
    fun theDemoRefusesEveryRelay() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        // Demo on example.com: no tag is injected, so nothing legitimate can
        // relay; a page forging relay messages gets nothing either.
        model.startDemo(checkNotNull(SiteInput.parse("example.com")))
        model.phase.first { it is AppViewModel.Phase.Consent }
        model.acceptConsentNow()
        assertTrue(checkNotNull(store.activeStudy).isDemo)
        model.redirectIngestToMock()

        assertEquals(TagRelay.Reply.FAILED, model.relayTagRequestNow(message("events", EVENTS), senderHost = SENDER))
        assertEquals(TagRelay.Reply.FAILED, model.relayTagRequestNow(message("ip"), senderHost = SENDER))
        assertTrue(ingestRequests.isEmpty())
        assertTrue(observed.isEmpty())
    }

    @Test
    fun ingestRedirectIsDebugOnlyAndPrefersTheExplicitOverride() = runTest(timeout = 30.seconds) {
        val debug = makeModel(debug = true)
        debug.enroll()
        assertNull("prod apiUrl matches the tag ⇒ no automatic redirect", debug.effectiveIngestRedirect)
        debug.ingestOverride = "https://dev-api.example/api/analytic/event/tag"
        assertEquals("https://dev-api.example/api/analytic/event/tag", debug.effectiveIngestRedirect)

        val release = makeModel(debug = false)
        release.ingestOverride = "https://dev-api.example/api/analytic/event/tag"
        assertNull("release never reroutes, whatever the extras say", release.effectiveIngestRedirect)
    }

    @Test
    fun devStudyApiUrlRedirectsAutomaticallyInDebug() = runTest(timeout = 30.seconds) {
        val model = makeModel(debug = true)
        model.enroll()
        val study = checkNotNull(store.activeStudy)
        val dev = "https://dev-api.moveo.one/api/analytic/event/tag"
        store.activeStudy = study.copy(config = study.config.copy(tracking = study.config.tracking.copy(apiUrl = dev)))
        assertEquals(dev, model.effectiveIngestRedirect)
    }

    @Test
    fun callbackFormAnswersOnTheModelScope() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enroll()
        model.redirectIngestToMock()
        val answered = CompletableDeferred<TagRelay.Reply>()
        model.relayTagRequest(message("ip"), senderHost = SENDER) { answered.complete(it) }
        assertEquals(200, answered.await().status)
        assertEquals(1, ingestRequests.size)
    }
}
