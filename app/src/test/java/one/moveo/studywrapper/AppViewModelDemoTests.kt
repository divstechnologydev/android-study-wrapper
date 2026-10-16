package one.moveo.studywrapper

import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import one.moveo.studycore.InMemoryKeyValueStore
import one.moveo.studycore.InMemoryParticipantIdStore
import one.moveo.studycore.SiteInput
import one.moveo.studycore.StudyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// The demo's promise — "nothing is recorded" — is a property of the build,
/// not of the copy, and these are the three interception points that make it
/// one (`DemoStudy`): activation never fetches, accepting never enrolls, and
/// no tag is ever injected (nor is the demo re-validated, which would 404 and
/// revoke it). The MockWebServer here answers everything with 500: any call
/// the demo made would fail the assertion that it made none.
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelDemoTests {
    private lateinit var server: MockWebServer
    private lateinit var kv: InMemoryKeyValueStore
    private var requests = 0
    private var clock: Instant = Instant.parse("2026-09-30T10:00:00Z")

    @Before
    fun setUp() {
        kv = InMemoryKeyValueStore()
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requests += 1
                return MockResponse().setResponseCode(500)
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
            isDebugBuild = true,
            // A tag that would be injected if the demo ever asked for one.
            assetLoader = { "// $it" },
            scope = backgroundScope,
            now = { clock },
        )
    }

    private fun site(raw: String): SiteInput.Parsed = requireNotNull(SiteInput.parse(raw))

    // MARK: - Interception point 1: activation never reaches the network

    @Test
    fun startDemoGoesStraightToConsentWithoutFetching() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(site("example.com/pricing"))

        val pending = (model.phase.value as AppViewModel.Phase.Consent).pending
        assertEquals(DemoStudy.CODE, pending.code)
        assertEquals(listOf("example.com"), pending.config.tracking.origins)
        assertEquals("/pricing", pending.config.flow.prepositionPath)
        assertNull("the demo never carries a panel transaction id", pending.transactionId)
        assertEquals("no network call for a demo activation", 0, requests)
    }

    /// The reserved code is the same thing typed: also intercepted before any
    /// fetch, on the default site.
    @Test
    fun reservedCodeRunsTheDemo() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.codeInput.value = DemoStudy.CODE
        model.activateNow()

        val pending = (model.phase.value as AppViewModel.Phase.Consent).pending
        assertEquals(DemoStudy.CODE, pending.code)
        assertEquals(listOf(DemoStudy.DEFAULT_SITE), pending.config.tracking.origins)
        assertEquals(0, requests)
    }

    @Test
    fun unusableSiteFailsWithoutLeavingTheApp() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(SiteInput.Parsed(origin = "not a host", path = null))

        assertTrue(model.phase.value is AppViewModel.Phase.Failed)
        assertEquals(0, requests)
    }

    // MARK: - Interception point 2: accepting is not an enrollment

    @Test
    fun acceptingTheDemoNeverEnrolls() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(site("example.com"))
        model.acceptConsentNow()

        val active = model.activeStudy.value!!
        assertEquals(DemoStudy.CODE, active.code)
        assertTrue(active.isDemo)
        assertEquals("no enroll call", 0, requests)
        assertNull("no consent record is written for a demo", model.store.consent)
    }

    // MARK: - Interception point 3: no tag, ever — and no re-validation

    @Test
    fun theDemoNeverInjectsTheTag() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(site("example.com"))
        model.acceptConsentNow()

        assertNull("no user script means no collection and no transport", model.currentUserScriptSource())
    }

    @Test
    fun revalidationLeavesTheDemoAlone() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(site("example.com"))
        model.acceptConsentNow()
        model.revalidateNow()

        assertEquals("re-validating would 404 and revoke the demo", 0, requests)
        assertNotNull(model.activeStudy.value)
        assertNull(model.endedStudy.value)
    }

    // MARK: - Finishing

    @Test
    fun finishingTheDemoCompletesItLocally() = runTest(timeout = 30.seconds) {
        val model = makeModel()
        model.startDemo(site("example.com"))
        model.acceptConsentNow()
        model.finishStudy()
        model.applyPendingCompletionNow()

        assertNull(model.activeStudy.value)
        val ended = model.endedStudy.value!!
        assertEquals(DemoStudy.CODE, ended.code)
        assertTrue(ended.completed == true)
        assertFalse(ended.revoked == true)
        // No lead-out is configured for the demo, so no survey opens.
        assertNull(model.leadSheet.value)
        assertEquals("completion reports no session for a demo", 0, requests)
    }
}
