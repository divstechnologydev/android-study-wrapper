package one.moveo.studywrapper

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/// Composite targets (config-schema §4.3; iOS `AppModel+Browser` target
/// chain; extension target-service `handleTargetLeafReached`): leaves latch
/// into `ActiveStudy.targetProgress`, the target fires once the tree
/// evaluates true, url leaves latch natively, event leaves arrive over the
/// bridge with their positional id and are trust-checked. The configs mirror
/// the mock backend's COMPOSITEAND1 / COMPOSITEOR01 (t.0 = url, t.1 = click).
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTargetChainTests {
    private lateinit var server: MockWebServer
    private lateinit var kv: InMemoryKeyValueStore
    private lateinit var store: StudyStore
    private var clock: Instant = Instant.parse("2026-10-29T10:00:00Z")

    /// What the config endpoint serves next (tests switch it for the refresh case).
    @Volatile
    private var servedTarget: String = AND_TARGET

    companion object {
        const val CODE = "COMPOSITECD1"
        const val LINK = "moveoone://config/$CODE"
        const val THANKS_URL = "https://example.com/checkout/x/thanks"
        const val OTHER_URL = "https://example.com/other"

        const val URL_LEAF = """{ "type": "url_match", "pattern": "/checkout/*/thanks" }"""
        const val CLICK_LEAF = """{ "type": "event_match", "props": { "eA": "click", "eT": "h1" } }"""
        const val AND_TARGET = """{ "type": "and", "conditions": [ $URL_LEAF, $CLICK_LEAF ] }"""
        const val OR_TARGET = """{ "type": "or", "conditions": [ $URL_LEAF, $CLICK_LEAF ] }"""
        /// Same leaves, swapped — a re-shaped tree with identical leaf set.
        const val AND_SWAPPED = """{ "type": "and", "conditions": [ $CLICK_LEAF, $URL_LEAF ] }"""
        const val EVENT_ROOT = CLICK_LEAF

        private fun config(targetAction: String) = """
            {
              "schemaVersion": 1,
              "study": { "id": "st_chain", "name": "Chain", "status": "active" },
              "tracking": {
                "token": "MOVEO_PROJECT_TOKEN_EXAMPLE",
                "apiUrl": "https://api.moveo.one/api/analytic/event/tag",
                "origins": ["example.com"]
              },
              "flow": { "leadOutUrl": "https://forms.example.com/exit", "targetAction": $targetAction }
            }
        """.trimIndent()
    }

    private class FakeBrowser : BrowserProxy {
        var lastScript: String? = null
        override fun goBackIfPossible() = false
        override fun reload() {}
        override fun load(url: String) {}
        override fun applyUserScript(source: String?) { lastScript = source }
        override fun clearBrowsingData() {}
        override suspend fun flushEvents(timeoutMillis: Long) {}
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
                    path.endsWith("/sessions") -> MockResponse().setResponseCode(204)
                    path.endsWith("/enroll") -> MockResponse().setResponseCode(201)
                        .setBody("""{"participantId":"p_abc","enrolledAt":"2026-10-29T10:00:00Z"}""")
                    path.endsWith("/$CODE") -> MockResponse().setResponseCode(200).setBody(config(servedTarget))
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

    private fun TestScope.makeModel(): AppViewModel = AppViewModel(
        store = store,
        appVersion = "9.9.9",
        isDebugBuild = true,
        assetLoader = { name -> if (name == "moveo-one.js") "/*TAG*/" else "/*BOOTSTRAP*/" },
        scope = backgroundScope,
        now = { clock },
    )

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = first(predicate)

    private suspend fun AppViewModel.enroll(target: String = AND_TARGET): FakeBrowser {
        servedTarget = target
        handleOpenUrl(LINK)
        phase.await { it is AppViewModel.Phase.Consent }
        acceptConsentNow()
        checkNotNull(activeStudy.value)
        openBrowser()
        return FakeBrowser().also { browser = it }
    }

    private fun AppViewModel.reportClick(
        leafId: String = "t.1",
        senderHost: String? = "example.com",
        senderUrl: String? = OTHER_URL,
    ) = handleBridgeMessage("target", mapOf("leafId" to leafId), senderHost = senderHost, senderUrl = senderUrl)

    private fun progress(): Set<String> = store.activeStudy?.latchedLeafIds ?: emptySet()
    private fun fired(): Boolean = store.activeStudy?.targetFired == true

    // MARK: - and / or semantics

    @Test
    fun andLatchesUrlThenFiresOnEvent() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.handleBrowserUrlChange(THANKS_URL)
        assertEquals(setOf("t.0"), progress())
        assertFalse("and needs both leaves", fired())
        assertNull(store.activeStudy?.leadOutDueAt)

        model.reportClick()
        assertTrue(fired())
        assertEquals("progress and targetFired go out in one write", setOf("t.0", "t.1"), progress())
        assertEquals(clock.plusSeconds(2), store.activeStudy?.leadOutDueAt)
    }

    @Test
    fun andIsOrderIndependent() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.reportClick()
        assertEquals(setOf("t.1"), progress())
        assertFalse(fired())

        model.handleBrowserUrlChange(THANKS_URL)
        assertTrue(fired())
    }

    @Test
    fun andFiresWhenEventHappensOnTheMatchingPageWithoutNavigation() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        // No URL feed ever saw the thanks page — the sender's URL latches the url leaf.
        model.reportClick(senderUrl = THANKS_URL)
        assertTrue(fired())
        assertEquals(setOf("t.0", "t.1"), progress())
    }

    @Test
    fun orFiresOnEitherLeafAlone() = runTest {
        val urlOnly = makeModel()
        urlOnly.enroll(OR_TARGET)
        urlOnly.handleBrowserUrlChange(THANKS_URL)
        assertTrue(fired())

        // Fresh store for the event-only path.
        store.activeStudy = null
        val eventOnly = makeModel()
        eventOnly.enroll(OR_TARGET)
        eventOnly.reportClick()
        assertTrue(fired())
    }

    @Test
    fun nonMatchingUrlLatchesNothing() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.handleBrowserUrlChange(OTHER_URL)
        assertEquals(emptySet<String>(), progress())
        assertNull("nothing latched ⇒ no progress key written", store.activeStudy?.targetProgress)
    }

    // MARK: - Bridge trust checks

    @Test
    fun malformedOrForeignLeafIdsAreDropped() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        for (bad in listOf("x", "t.100", "", "__proto__", "t.9", "t.1.0")) {
            model.reportClick(leafId = bad)
        }
        assertEquals("shape-check + findLeaf drop every one", emptySet<String>(), progress())
    }

    @Test
    fun pageCannotClaimAUrlLeafOrTheRootOfAComposite() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.reportClick(leafId = "t.0") // the url leaf — only the native feed may latch it
        model.reportClick(leafId = "t")   // the root is not a leaf on a composite tree
        assertEquals(emptySet<String>(), progress())
        assertFalse(fired())
    }

    @Test
    fun offOriginOrSenderlessReportsAreDropped() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.reportClick(senderHost = "evil.example.net")
        model.reportClick(senderHost = null, senderUrl = null)
        model.reportClick(senderHost = "")
        assertEquals(emptySet<String>(), progress())
        // A subdomain of a study origin is fine (§5 match rule).
        model.reportClick(senderHost = "shop.example.com")
        assertEquals(setOf("t.1"), progress())
    }

    @Test
    fun duplicateReportsCollapse() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.reportClick()
        val first = store.activeStudy?.targetProgress?.get("t.1")
        clock = clock.plusSeconds(30)
        model.reportClick()
        assertEquals("first latch time kept", first, store.activeStudy?.targetProgress?.get("t.1"))
        assertFalse(fired())
    }

    @Test
    fun reportsAfterTheTargetFiredAreIgnored() = runTest {
        val model = makeModel()
        model.enroll(OR_TARGET)
        model.handleBrowserUrlChange(THANKS_URL)
        val firedAt = store.activeStudy?.targetFiredAt
        clock = clock.plusSeconds(1)
        model.reportClick()
        assertEquals(firedAt, store.activeStudy?.targetFiredAt)
        assertEquals("no late latch", setOf("t.0"), progress())
    }

    // MARK: - Legacy single-leaf configs (the degenerate tree)

    @Test
    fun legacyEventRootFiresOnLeafIdT() = runTest {
        val model = makeModel()
        model.enroll(EVENT_ROOT)
        model.reportClick(leafId = "t.0")
        assertFalse("child-shaped id on a leaf root resolves to nothing", fired())
        model.reportClick(leafId = "t")
        assertTrue(fired())
        assertEquals(setOf("t"), progress())
    }

    @Test
    fun legacyUrlRootFiresOnLeafIdT() = runTest {
        val model = makeModel()
        model.enroll(URL_LEAF)
        model.handleBrowserUrlChange(THANKS_URL)
        assertTrue(fired())
        assertEquals(setOf("t"), progress())
    }

    // MARK: - Injected script carries only the unfired event leaves

    @Test
    fun scriptCarriesUnfiredEventLeavesThenNothing() = runTest {
        val model = makeModel()
        val browser = model.enroll(AND_TARGET)
        val initial = checkNotNull(model.currentUserScriptSource())
        assertTrue(initial, initial.contains("\"targetAction\":{\"leaves\":[{\"id\":\"t.1\",\"props\":{"))
        assertFalse("the url leaf never reaches the page", initial.contains("t.0\""))
        assertFalse(initial.contains("conditions"))

        model.reportClick()
        val afterLatch = checkNotNull(browser.lastScript)
        assertFalse("latched event leaf dropped from the next load", afterLatch.contains("targetAction"))

        model.handleBrowserUrlChange(THANKS_URL)
        assertTrue(fired())
        assertFalse(checkNotNull(model.currentUserScriptSource()).contains("targetAction"))
    }

    // MARK: - Config refresh

    @Test
    fun reshapedTreeOnRefreshResetsProgressButKeepsFired() = runTest {
        val model = makeModel()
        model.enroll(AND_TARGET)
        model.reportClick()
        assertEquals(setOf("t.1"), progress())

        // Same leaves, swapped positions: a latched "t.1" would now mean the
        // url leaf — fail-open. Progress must reset.
        servedTarget = AND_SWAPPED
        model.revalidateNow()
        assertNull(store.activeStudy?.targetProgress)
        assertFalse(fired())
        assertNotNull(store.activeStudy)

        // Identical tree on a later refresh keeps whatever latched since.
        model.handleBrowserUrlChange(THANKS_URL) // url leaf is now t.1
        assertEquals(setOf("t.1"), progress())
        model.revalidateNow()
        assertEquals(setOf("t.1"), progress())
    }

    @Test
    fun refreshNeverUnfiresAReachedTarget() = runTest {
        val model = makeModel()
        model.enroll(OR_TARGET)
        model.handleBrowserUrlChange(THANKS_URL)
        assertTrue(fired())
        servedTarget = AND_TARGET
        model.revalidateNow()
        // The lead-out completes the study once shown; until then the record
        // must still say fired with its progress wiped.
        store.activeStudy?.let {
            assertTrue(it.targetFired)
            assertNull(it.targetProgress)
        }
    }
}
