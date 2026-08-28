package one.moveo.studywrapper

import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// The setup-link state machine (docs/a1-transactions.md §1 rules 4, 8, 9)
/// against a MockWebServer backend: what a link does to a pending /
/// active study, how the transaction id travels (link → pending → enroll
/// body → stored study → lead-out URL, and nowhere else), and that consent
/// retries reuse one enrollment id. iOS has no equivalent coverage; this is
/// the riskiest part of the a1.4 change.
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelLinkTests {
    private lateinit var server: MockWebServer
    private lateinit var kv: InMemoryKeyValueStore
    private val logs = mutableListOf<String>()
    private val enrollBodies = mutableListOf<JsonObject>()
    private var enrollStatus = 201
    private var failNextConfigFetch = false
    private var configRequests = 0

    /// Fake wall clock: runTest skips `delay` in virtual time, so the
    /// lead-out due check (a wall-clock comparison in the model) needs a
    /// clock the test advances in step.
    private var clock: Instant = Instant.parse("2026-08-27T10:00:00Z")

    companion object {
        const val CODE = "TESTCODE1234"
        const val OTHER = "OTHERCODE001"
        const val LINK_TX = "moveoone://config/$CODE?transaction_id=tx_qa_001"
        const val LINK_PLAIN = "https://app.moveo.one/extension/config/$CODE"

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
              "flow": {
                "leadInUrl": "https://forms.example.com/intro",
                "leadOutUrl": "https://forms.example.com/exit?src=moveo",
                "targetAction": { "type": "url_match", "pattern": "/checkout/success" }
              }
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
                        MockResponse().setResponseCode(enrollStatus).setBody(
                            if (enrollStatus == 201) """{"participantId":"p_abc","enrolledAt":"2026-08-27T10:00:00Z"}""" else "",
                        )
                    }
                    path.endsWith("/$CODE") || path.endsWith("/$OTHER") -> {
                        configRequests += 1
                        if (failNextConfigFetch) {
                            failNextConfigFetch = false
                            MockResponse().setResponseCode(500)
                        } else {
                            MockResponse().setResponseCode(200)
                                .setBody(config(if (path.endsWith("/$CODE")) "Study A" else "Study B"))
                        }
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

    private fun TestScope.makeModel(): AppViewModel {
        val store = StudyStore(store = kv, participantIdStore = InMemoryParticipantIdStore("p_abc"))
        store.apiBaseOverride = server.url("/api/v1/extension-config").toString()
        return AppViewModel(
            store = store,
            appVersion = "9.9.9",
            isDebugBuild = true, // honors apiBaseOverride (release is pinned to prod)
            debugLog = { logs += it },
            scope = backgroundScope,
            now = { clock },
        )
    }

    /// Fire the url_match target, then move the wall clock past the
    /// lead-out delay so the (virtually skipped) timer finds it due.
    private fun AppViewModel.reachTarget() {
        handleBrowserUrlChange("https://example.com/checkout/success")
        clock = clock.plusSeconds(3)
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = first(predicate)

    /// A link goes straight to the consent screen (no summary sheet since
    /// 2026-08-28); the pending activation lives in the Consent phase.
    private suspend fun AppViewModel.awaitPending(): AppViewModel.PendingActivation =
        (phase.await { it is AppViewModel.Phase.Consent } as AppViewModel.Phase.Consent).pending

    private fun AppViewModel.pending(): AppViewModel.PendingActivation? =
        (phase.value as? AppViewModel.Phase.Consent)?.pending

    private suspend fun AppViewModel.enrollNow() {
        acceptConsentNow()
    }

    // MARK: - link → pending → enroll body → stored study

    @Test
    fun linkCarriesTransactionIdThroughEnrollment() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        val pending = model.awaitPending()
        assertEquals(CODE, pending.code)
        assertEquals("tx_qa_001", pending.transactionId)
        assertTrue("minted before the first enroll attempt", pending.enrollmentId.startsWith("e_"))
        assertTrue(logs.any { it.startsWith("openURL code $CODE transactionId tx_qa_001") })

        model.enrollNow()
        val study = checkNotNull(model.activeStudy.value)
        assertEquals("tx_qa_001", study.transactionId)
        assertEquals(pending.enrollmentId, study.enrollmentId)
        val body = enrollBodies.single()
        assertEquals(pending.enrollmentId, body["enrollmentId"]?.jsonPrimitive?.content)
        assertEquals("tx_qa_001", body["transactionId"]?.jsonPrimitive?.content)
        assertEquals("android", body["client"]?.jsonPrimitive?.content)
    }

    @Test
    fun plainLinkOmitsTransactionId() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_PLAIN)
        assertNull(model.awaitPending().transactionId)
        model.enrollNow()
        assertNull(model.activeStudy.value?.transactionId)
        assertFalse("key absent, not null", enrollBodies.single().containsKey("transactionId"))
        assertTrue(enrollBodies.single().containsKey("enrollmentId"))
    }

    @Test
    fun pastedLinkInCodeInputKeepsTransactionId() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.codeInput.value = "  https://app.moveo.one/extension/config/test-code-1234?transaction_id=tx_paste \n"
        model.activateNow()
        val pending = checkNotNull(model.pending())
        assertEquals(CODE, pending.code)
        assertEquals("tx_paste", pending.transactionId)
    }

    // MARK: - rule 8: link arrival vs current state

    @Test
    fun sameCodeActiveIsIdempotent() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        model.awaitPending()
        model.enrollNow()
        val before = checkNotNull(model.activeStudy.value)
        val fetchesBefore = configRequests
        model.openBrowser()

        model.handleOpenUrl(LINK_TX)
        assertEquals("no re-fetch", fetchesBefore, configRequests)
        assertEquals("no second enrollment", 1, enrollBodies.size)
        assertEquals(before, model.activeStudy.value)
        assertFalse("browser closed, home shows", model.browserPresented.value)
        assertEquals(AppViewModel.Phase.Idle, model.phase.value)
        assertEquals("", model.codeInput.value)
    }

    @Test
    fun freshLinkUpdatesPendingInPlaceAndFreshIdWins() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_PLAIN)
        val first = model.awaitPending()
        assertNull(first.transactionId)

        // Consent screen up: same code with a transaction id → updated in
        // place, no restart, same enrollment id.
        model.handleOpenUrl(LINK_TX)
        val updated = checkNotNull(model.pending())
        assertEquals("tx_qa_001", updated.transactionId)
        assertEquals(first.enrollmentId, updated.enrollmentId)
        assertEquals("one fetch only", 1, configRequests)

        // A later plain link must not erase it (stale/absent never wins).
        model.handleOpenUrl(LINK_PLAIN)
        assertEquals("tx_qa_001", model.pending()?.transactionId)

        // A fresher id still wins.
        model.handleOpenUrl("moveoone://config/$CODE?transaction_id=tx_newer")
        val consent = model.phase.value as AppViewModel.Phase.Consent
        assertEquals("tx_newer", consent.pending.transactionId)
        assertEquals(first.enrollmentId, consent.pending.enrollmentId)
        assertEquals(1, configRequests)

        model.acceptConsentNow()
        assertEquals("tx_newer", model.activeStudy.value?.transactionId)
        assertEquals("tx_newer", enrollBodies.single()["transactionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun stashIsKeyedByCodeAndSurvivesAFailedFetch() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        failNextConfigFetch = true
        model.handleOpenUrl(LINK_TX)
        model.phase.await { it is AppViewModel.Phase.Failed }
        model.backToEntry()

        // A different code typed afterwards never inherits the link's id.
        model.codeInput.value = OTHER
        model.activateNow()
        val other = checkNotNull(model.pending())
        assertEquals(OTHER, other.code)
        assertNull(other.transactionId)
        model.declineConsent()

        // The stash is gone after the decline — retyping the link's code is
        // a plain typed activation now.
        model.codeInput.value = CODE
        model.activateNow()
        assertNull(model.pending()?.transactionId)
    }

    @Test
    fun retryOfTheLinkCodeAfterFailedFetchKeepsTheId() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        failNextConfigFetch = true
        model.handleOpenUrl(LINK_TX)
        model.phase.await { it is AppViewModel.Phase.Failed }
        model.backToEntry()
        // Same code re-activated (debug MOVEO_AUTO_CODE path / a future
        // manual field): the unconsumed stash still applies to THAT code.
        model.codeInput.value = CODE
        model.activateNow()
        assertEquals("tx_qa_001", model.pending()?.transactionId)
    }

    @Test
    fun declineDropsPendingAndItsIds() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        model.awaitPending()
        model.declineConsent()
        assertEquals(AppViewModel.Phase.Idle, model.phase.value)
        assertNull(model.activeStudy.value)
        assertTrue("no enroll call", enrollBodies.isEmpty())
        model.codeInput.value = CODE
        model.activateNow()
        assertNull("stash cleared with the decline", model.pending()?.transactionId)
    }

    // MARK: - rule 4: one enrollment id per enrollment, reused on retry

    @Test
    fun consentRetryReusesTheEnrollmentId() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        val pending = model.awaitPending()

        enrollStatus = 500
        model.acceptConsentNow()
        assertNotNull("retryable error keeps the consent screen", model.consentError.value)
        assertTrue(model.phase.value is AppViewModel.Phase.Consent)
        assertNull(model.activeStudy.value)

        enrollStatus = 201
        model.acceptConsentNow()
        assertNotNull(model.activeStudy.value)
        assertEquals(2, enrollBodies.size)
        val ids = enrollBodies.map { it["enrollmentId"]?.jsonPrimitive?.content }
        assertEquals("same id on both attempts", listOf(pending.enrollmentId, pending.enrollmentId), ids)
        assertEquals(pending.enrollmentId, model.activeStudy.value?.enrollmentId)
        assertTrue(enrollBodies.all { it["transactionId"]?.jsonPrimitive?.content == "tx_qa_001" })
    }

    // MARK: - rule 7: lead-out echoes the id, lead-in does not

    @Test
    fun leadOutEchoesTransactionIdLeadInDoesNot() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        model.awaitPending()
        model.enrollNow()

        model.handleBrowserUrlChange("https://example.com/")
        val leadIn = checkNotNull(model.leadSheet.value)
        assertEquals(AppViewModel.LeadSheet.Kind.LEAD_IN, leadIn.kind)
        assertEquals("https://forms.example.com/intro", leadIn.url)
        model.leadSheetLaunched(leadIn, success = true)

        model.reachTarget()
        assertTrue(model.activeStudy.value?.targetFired == true)
        assertNotNull("due time persisted BEFORE the timer", model.activeStudy.value?.leadOutDueAt)
        val leadOut = model.leadSheet.await { it?.kind == AppViewModel.LeadSheet.Kind.LEAD_OUT }!!
        assertEquals("https://forms.example.com/exit?src=moveo&transaction_id=tx_qa_001", leadOut.url)
        model.leadSheetLaunched(leadOut, success = true)
        assertTrue(logs.any { it == "lead: LEAD_OUT https://forms.example.com/exit?src=moveo&transaction_id=tx_qa_001" })
        assertNotNull(model.activeStudy.value?.leadOutShownAt)
    }

    @Test
    fun leadOutWithoutTransactionIdIsUnchanged() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_PLAIN)
        model.awaitPending()
        model.enrollNow()
        // First tracked-origin visit IS the target page: the lead-in queues
        // first and the due lead-out waits behind it until that sheet has
        // launched (then the activity-resume retry opens it).
        model.reachTarget()
        val leadIn = checkNotNull(model.leadSheet.value)
        assertEquals(AppViewModel.LeadSheet.Kind.LEAD_IN, leadIn.kind)
        model.leadSheetLaunched(leadIn, success = true)
        model.presentDueLeadOut()
        val leadOut = model.leadSheet.await { it?.kind == AppViewModel.LeadSheet.Kind.LEAD_OUT }!!
        assertEquals("https://forms.example.com/exit?src=moveo", leadOut.url)
    }

    @Test
    fun leaveClearsStoredIds() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.handleOpenUrl(LINK_TX)
        model.awaitPending()
        model.enrollNow()
        model.leaveStudy()
        assertNull(model.activeStudy.value)
        assertNull(kv.getString("activeStudy"))
    }
}
