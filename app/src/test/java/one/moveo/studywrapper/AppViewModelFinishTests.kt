package one.moveo.studywrapper

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import one.moveo.studycore.EndedStudy
import one.moveo.studycore.InMemoryKeyValueStore
import one.moveo.studycore.InMemoryParticipantIdStore
import one.moveo.studycore.StudyStore
import one.moveo.studywrapper.browser.BrowserProxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// Finishing a study (docs/a2-finish.md §1 rules 2–6, 8–10): Done / Finish
/// study and the target both complete the study — persisted at once, shown
/// once the closing page closes (`activityResumed`) — with the tag's event
/// buffer flushed before the browser is torn down. Mirrors the iOS
/// `done-finish` model; the Android specifics (resume as the dismissal
/// signal, storage-mirroring on apply) are what these tests pin.
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelFinishTests {
    private lateinit var server: MockWebServer
    private lateinit var kv: InMemoryKeyValueStore
    private lateinit var store: StudyStore
    private val logs = mutableListOf<String>()
    private var configRequests = 0

    /// When set, config responses block until released (re-validation race).
    @Volatile
    private var configGate: CountDownLatch? = null

    private var clock: Instant = Instant.parse("2026-08-28T10:00:00Z")

    companion object {
        const val CODE = "TESTCODE1234" // lead-in + lead-out + url_match target
        const val NOLEAD = "NOLEADCODE01" // target only — no lead surveys
        const val LINK_TX = "moveoone://config/$CODE?transaction_id=tx_fin_001"
        const val LINK_NOLEAD = "moveoone://config/$NOLEAD?transaction_id=tx_fin_002"
        const val TARGET_URL = "https://example.com/checkout/success"
        const val LEAD_OUT_WITH_TX = "https://forms.example.com/exit?src=moveo&transaction_id=tx_fin_001"

        private fun config(name: String, withLeads: Boolean) = """
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
              "flow": {
                ${if (withLeads) """"leadInUrl": "https://forms.example.com/intro", "leadOutUrl": "https://forms.example.com/exit?src=moveo",""" else ""}
                "targetAction": { "type": "url_match", "pattern": "/checkout/success" }
              }
            }
        """.trimIndent()
    }

    /// Records what the model asks of the live WebView, and the UI state at
    /// flush time (the flush must run while the browser is still up).
    private class FakeBrowser(private val model: AppViewModel) : BrowserProxy {
        val calls = mutableListOf<String>()
        var browserPresentedAtFlush: Boolean? = null
        override fun goBackIfPossible() = false
        override fun reload() { calls += "reload" }
        override fun load(url: String) { calls += "load" }
        override fun applyUserScript(source: String?) { calls += if (source == null) "script:null" else "script:set" }
        override fun clearBrowsingData() { calls += "clear" }
        override suspend fun flushEvents(timeoutMillis: Long) {
            calls += "flush:$timeoutMillis"
            browserPresentedAtFlush = model.browserPresented.value
        }
    }

    @Before
    fun setUp() {
        kv = InMemoryKeyValueStore()
        store = StudyStore(store = kv, participantIdStore = InMemoryParticipantIdStore("p_abc"))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.endsWith("/enroll") -> MockResponse().setResponseCode(201)
                        .setBody("""{"participantId":"p_abc","enrolledAt":"2026-08-28T10:00:00Z"}""")
                    path.endsWith("/$CODE") || path.endsWith("/$NOLEAD") -> {
                        configRequests += 1
                        configGate?.await(20, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(200).setBody(
                            if (path.endsWith("/$CODE")) config("Study A", withLeads = true)
                            else config("Study N", withLeads = false),
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        store.apiBaseOverride = server.url("/api/v1/extension-config").toString()
    }

    @After
    fun tearDown() {
        configGate?.countDown()
        server.shutdown()
    }

    private fun TestScope.makeModel(): AppViewModel = AppViewModel(
        store = store,
        appVersion = "9.9.9",
        isDebugBuild = true,
        debugLog = { logs += it },
        scope = backgroundScope,
        now = { clock },
    )

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = first(predicate)

    /// Link → consent → enrolled, browser open with a fake WebView.
    private suspend fun AppViewModel.enrollAndOpen(link: String): FakeBrowser {
        handleOpenUrl(link)
        phase.await { it is AppViewModel.Phase.Consent }
        acceptConsentNow()
        checkNotNull(activeStudy.value)
        openBrowser()
        return FakeBrowser(this).also { browser = it }
    }

    /// Fire the url_match target, then move the wall clock past the lead-out
    /// delay so the (virtually skipped) timer finds it due.
    private fun AppViewModel.reachTarget() {
        handleBrowserUrlChange(TARGET_URL)
        clock = clock.plusSeconds(3)
    }

    /// Study A's lead-in queues on the first tracked-origin visit; get it out
    /// of the way so lead-out assertions are unambiguous.
    private fun AppViewModel.passLeadIn() {
        handleBrowserUrlChange("https://example.com/")
        val leadIn = checkNotNull(leadSheet.value)
        assertEquals(AppViewModel.LeadSheet.Kind.LEAD_IN, leadIn.kind)
        leadSheetLaunched(leadIn, success = true)
    }

    private fun assertCompletedRecord(ended: EndedStudy?, name: String, leadOutUrl: String?) {
        val record = checkNotNull(ended)
        assertEquals(name, record.name)
        assertEquals(true, record.completed)
        assertEquals(false, record.revoked)
        assertEquals(leadOutUrl, record.leadOutUrl)
    }

    // MARK: - rule 2/6/8: Finish with a lead-out

    @Test
    fun finishWithLeadOutOpensItWithTransactionIdThenCompletesOnResume() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        val browser = model.enrollAndOpen(LINK_TX)
        model.passLeadIn()

        model.finishStudy()
        val leadOut = checkNotNull(model.leadSheet.value)
        assertEquals(AppViewModel.LeadSheet.Kind.LEAD_OUT, leadOut.kind)
        assertEquals("lead-out carries the panel id", LEAD_OUT_WITH_TX, leadOut.url)
        assertNotNull("due time persisted before the launch", store.activeStudy?.leadOutDueAt)
        assertFalse("nothing completed until the closing page is actually shown", model.hasPendingCompletion)

        // Custom Tab launched → completion persisted at once, UI untouched.
        model.leadSheetLaunched(leadOut, success = true)
        assertTrue(model.hasPendingCompletion)
        assertNull("active study cleared from storage", kv.getString("activeStudy"))
        assertNull(store.consent)
        assertCompletedRecord(store.endedStudy, "Study A", "https://forms.example.com/exit?src=moveo")
        assertNotNull(store.endedStudy?.leadOutShownAt)
        assertNotNull("browser still shows the study underneath the tab", model.activeStudy.value)
        assertTrue(model.browserPresented.value)
        assertNull(model.endedStudy.value)
        assertTrue("tracking stops from the next load", browser.calls.contains("script:null"))

        // Closing page closed → activity resumes → transition.
        model.activityResumed()
        val ended = model.endedStudy.await { it != null }
        assertCompletedRecord(ended, "Study A", "https://forms.example.com/exit?src=moveo")
        assertFalse(model.hasPendingCompletion)
        assertNull(model.activeStudy.value)
        assertFalse(model.browserPresented.value)
        assertNull(model.leadSheet.value)
        assertEquals("flushed while the browser was still up", true, browser.browserPresentedAtFlush)
        val flushIndex = browser.calls.indexOfFirst { it.startsWith("flush:") }
        assertTrue("flush happens before the WebView cleanup", flushIndex in 0 until browser.calls.indexOf("clear"))
        assertEquals("flush:3000", browser.calls[flushIndex])
    }

    @Test
    fun finishWithoutLeadOutCompletesImmediately() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        val browser = model.enrollAndOpen(LINK_NOLEAD)
        model.finishStudy()
        val ended = model.endedStudy.await { it != null }
        assertCompletedRecord(ended, "Study N", null)
        assertNull(ended?.leadOutShownAt)
        assertNull("no closing page to open", model.leadSheet.value)
        assertTrue(logs.none { it.startsWith("lead:") })
        assertNull(model.activeStudy.value)
        assertFalse(model.browserPresented.value)
        assertTrue(browser.calls.any { it.startsWith("flush:") })
    }

    // MARK: - rule 3: a lead-out already shown is never shown again

    @Test
    fun finishAfterLeadOutAlreadyShownSkipsItAndKeepsTheShownAt() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_TX)
        // A record from a build where the lead-out did not end the study.
        val shownAt = clock.minusSeconds(600)
        store.activeStudy = checkNotNull(store.activeStudy).copy(leadOutShownAt = shownAt)

        model.finishStudy()
        val ended = model.endedStudy.await { it != null }
        assertNull("no second lead-out", model.leadSheet.value)
        assertCompletedRecord(ended, "Study A", "https://forms.example.com/exit?src=moveo")
        assertEquals(shownAt, ended?.leadOutShownAt)
    }

    // MARK: - rule 4/5: the target completes the study

    @Test
    fun targetWithoutLeadOutCompletesAtOnceAndTheUiFollowsAfterTheDelay() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        val browser = model.enrollAndOpen(LINK_NOLEAD)

        model.handleBrowserUrlChange(TARGET_URL)
        // Persisted immediately (crash-safe)…
        assertTrue(model.hasPendingCompletion)
        assertNull(kv.getString("activeStudy"))
        assertCompletedRecord(store.endedStudy, "Study N", null)
        // …while the participant still sees their confirmation page.
        assertTrue(model.browserPresented.value)
        assertNotNull(model.activeStudy.value)
        assertNull(model.endedStudy.value)

        val ended = model.endedStudy.await { it != null } // after the 2 s delay
        assertCompletedRecord(ended, "Study N", null)
        assertFalse(model.browserPresented.value)
        assertNull(model.activeStudy.value)
        assertEquals(true, browser.browserPresentedAtFlush)
    }

    @Test
    fun targetWithLeadOutCompletesOnlyOnceTheLeadOutLaunched() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_TX)
        model.passLeadIn()

        model.reachTarget()
        assertTrue(store.activeStudy?.targetFired == true)
        assertFalse("still active during the delay", model.hasPendingCompletion)
        val leadOut = model.leadSheet.await { it?.kind == AppViewModel.LeadSheet.Kind.LEAD_OUT }!!
        assertEquals(LEAD_OUT_WITH_TX, leadOut.url)
        assertFalse("sheet queued, not yet launched", model.hasPendingCompletion)
        assertNotNull(store.activeStudy)

        model.leadSheetLaunched(leadOut, success = true)
        assertTrue(model.hasPendingCompletion)
        assertNull(store.activeStudy)
        assertNotNull(store.endedStudy?.leadOutShownAt)

        model.activityResumed()
        val ended = model.endedStudy.await { it != null }
        assertCompletedRecord(ended, "Study A", "https://forms.example.com/exit?src=moveo")
    }

    @Test
    fun leadOutLaunchFailureKeepsTheStudyActiveAndResumeRetries() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_TX)
        model.passLeadIn()
        model.finishStudy()
        val leadOut = checkNotNull(model.leadSheet.value)

        model.leadSheetLaunched(leadOut, success = false)
        assertFalse(model.hasPendingCompletion)
        assertNotNull("study stays active", store.activeStudy)
        assertNotNull("due time kept for the self-heal", store.activeStudy?.leadOutDueAt)
        assertNull(store.activeStudy?.leadOutShownAt)
        assertNull(store.endedStudy)
        assertNull(model.leadSheet.value)

        // Next resume (no completion pending) = the ordinary lead-out retry.
        model.activityResumed()
        assertEquals(LEAD_OUT_WITH_TX, model.leadSheet.value?.url)
        assertFalse(model.hasPendingCompletion)
    }

    // MARK: - rule 10: re-validation

    @Test
    fun legacyRecordWithShownLeadOutIsCompletedOnRevalidateWithoutAFetch() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_TX)
        val shownAt = clock.minusSeconds(60)
        store.activeStudy = checkNotNull(store.activeStudy).copy(leadOutShownAt = shownAt)
        val fetchesBefore = configRequests

        model.revalidateNow()
        assertEquals("no config fetch for a finished participant", fetchesBefore, configRequests)
        assertCompletedRecord(model.endedStudy.value, "Study A", "https://forms.example.com/exit?src=moveo")
        assertEquals(shownAt, model.endedStudy.value?.leadOutShownAt)
        assertNull(model.activeStudy.value)
        assertNull(store.activeStudy)
        assertFalse(model.browserPresented.value)
    }

    @Test
    fun finishDuringRevalidationFetchIsNeverResurrected() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_NOLEAD)

        val gate = CountDownLatch(1)
        configGate = gate
        val revalidated = CompletableDeferred<Unit>()
        backgroundScope.launch {
            model.revalidateNow()
            revalidated.complete(Unit)
        }
        // The participant finishes while the fetch is in flight.
        model.finishStudy()
        model.endedStudy.await { it != null }
        assertNull(store.activeStudy)

        gate.countDown()
        revalidated.await()
        assertNull("fresh config must not re-activate the study", store.activeStudy)
        assertNull(model.activeStudy.value)
        assertEquals(true, model.endedStudy.value?.completed)
    }

    // MARK: - leave / decline clean-up

    @Test
    fun leaveDropsAPendingCompletionAndTheOwnTagMap() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_NOLEAD)
        model.handleBridgeMessage("ownTag", mapOf("hostname" to "example.com"))
        assertTrue(store.ownTagHosts.isNotEmpty())

        model.handleBrowserUrlChange(TARGET_URL)
        assertTrue(model.hasPendingCompletion)
        model.leaveStudy()
        assertFalse(model.hasPendingCompletion)
        assertTrue(store.ownTagHosts.isEmpty())
        assertNull(store.endedStudy)
        assertNull(model.endedStudy.value)
        // A later resume has nothing to apply.
        model.activityResumed()
        assertNull(model.endedStudy.value)
    }

    @Test
    fun completionClearsTheOwnTagMap() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_NOLEAD)
        model.handleBridgeMessage("ownTag", mapOf("hostname" to "example.com"))
        model.finishStudy()
        model.endedStudy.await { it != null }
        assertTrue(store.ownTagHosts.isEmpty())
    }

    // MARK: - after completion

    @Test
    fun theSameLinkAfterCompletionStartsAFreshActivation() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.enrollAndOpen(LINK_NOLEAD)
        model.finishStudy()
        model.endedStudy.await { it != null }
        model.dismissEndedStudy()
        assertNull(model.endedStudy.value)

        // No active study any more → not the idempotent "already active"
        // path: fetch → consent again (the backend's 409 makes the re-enroll
        // idempotent on its side).
        model.handleOpenUrl(LINK_NOLEAD)
        val pending = (model.phase.await { it is AppViewModel.Phase.Consent } as AppViewModel.Phase.Consent).pending
        assertEquals(NOLEAD, pending.code)
        assertEquals("tx_fin_002", pending.transactionId)
    }
}
