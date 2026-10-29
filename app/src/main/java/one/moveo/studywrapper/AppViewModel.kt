package one.moveo.studywrapper

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import one.moveo.studycore.ActivationError
import one.moveo.studycore.ActiveStudy
import one.moveo.studycore.ApiResult
import one.moveo.studycore.BackendConstants
import one.moveo.studycore.Codes
import one.moveo.studycore.ConfigService
import one.moveo.studycore.ConsentConstants
import one.moveo.studycore.ConsentRecord
import one.moveo.studycore.EndedStudy
import one.moveo.studycore.EnrollError
import one.moveo.studycore.Enrollment
import one.moveo.studycore.FlowConstants
import one.moveo.studycore.LeadUrl
import one.moveo.studycore.Origins
import one.moveo.studycore.ScriptBuilder
import one.moveo.studycore.SiteInput
import one.moveo.studycore.SessionIds
import one.moveo.studycore.SetupLink
import one.moveo.studycore.StudyConfig
import one.moveo.studycore.StudyStore
import one.moveo.studycore.TagRelay
import one.moveo.studycore.TargetMatch
import one.moveo.studycore.generated.TagEndpoint
import one.moveo.studywrapper.browser.BrowserProxy

/// App-level state machine (← iOS AppModel.swift). Owns the store and drives
/// which screen shows; all contract logic (validation, error mapping,
/// normalization) lives in :studycore — this layer only sequences it and
/// holds user-facing copy. Application-scoped (the iOS `@StateObject` in the
/// App struct), so state survives activity recreation.
class AppViewModel(
    val store: StudyStore,
    private val appVersion: String,
    /// BuildConfig.DEBUG from the app — gates the API-base override and the
    /// relaxed tag-endpoint guard (§a2.7). Release passes false and R8 strips
    /// the debug branches.
    private val isDebugBuild: Boolean,
    private val debugLog: ((String) -> Unit)? = null,
    /// Reads a bundled asset (the vendored tag + bootstrap); injected so this
    /// class stays constructor-testable. Returns null when missing.
    private val assetLoader: (String) -> String? = { null },
    /// DEBUG parity capture seam for the CSP relay (← iOS
    /// `handleDebugSpyMessage` under `#if DEBUG`): called with every relayed
    /// event batch that passed the relay's checks, BEFORE it is sent —
    /// `(ingestUrl, senderHost, body)`. The debug source set wires the event
    /// spy here; release passes null, so no observer code exists there.
    private val relayedEventsObserver: ((url: String, host: String, body: String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /// Wall clock for every persisted timestamp and the lead-out due check;
    /// injected so JVM tests can advance it in step with virtual time.
    private val now: () -> Instant = { Instant.now() },
) {
    data class PendingActivation(
        val code: String,
        val config: StudyConfig,
        /// Name of the currently active study this activation would replace.
        val replacingName: String?,
        /// Minted once per activation attempt, BEFORE the first enroll call,
        /// so consent-screen retries resend the same id (extension parity:
        /// "one id per enrollment, generated before the first attempt").
        val enrollmentId: String,
        /// Panel-provider id from the setup link; null for typed codes. A
        /// fresh link for the same code may update it (fresh id wins).
        val transactionId: String? = null,
    )

    sealed class Phase {
        data object Idle : Phase()
        data object Fetching : Phase()
        data class Failed(val title: String, val message: String) : Phase()
        data class StudyEnded(val name: String) : Phase()
        /// Config fetched and valid — showing the consent screen (a0.5
        /// wording). Same as the extension's link flow: link → consent, no
        /// intermediate summary sheet; the consent page itself names the
        /// study, lists the tracked websites and warns about replacing a
        /// study (removed 2026-08-28, with iOS).
        data class Consent(val pending: PendingActivation) : Phase()
    }

    val codeInput = MutableStateFlow("")
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /// Mirror of `store.activeStudy` — non-null switches the root to the
    /// study home screen.
    private val _activeStudy = MutableStateFlow(store.activeStudy)
    val activeStudy: StateFlow<ActiveStudy?> = _activeStudy

    private val _enrolling = MutableStateFlow(false)
    val enrolling: StateFlow<Boolean> = _enrolling

    /// Inline error on the consent screen (network/server enroll failures
    /// keep the participant there so Accept can be retried).
    private val _consentError = MutableStateFlow<String?>(null)
    val consentError: StateFlow<String?> = _consentError

    private val _endedStudy = MutableStateFlow(store.endedStudy)
    val endedStudy: StateFlow<EndedStudy?> = _endedStudy

    // M4: browser session + lifecycle.
    private val _browserPresented = MutableStateFlow(false)
    val browserPresented: StateFlow<Boolean> = _browserPresented

    /// Non-null asks the UI layer to launch a lead survey (Custom Tab). The
    /// UI consumes it via `leadSheetLaunched` — "presented" = successful
    /// launch (Android has no render callback; recorded in plan §6).
    data class LeadSheet(val url: String, val kind: Kind) {
        enum class Kind { LEAD_IN, LEAD_OUT }
    }

    private val _leadSheet = MutableStateFlow<LeadSheet?>(null)
    val leadSheet: StateFlow<LeadSheet?> = _leadSheet

    /// Live WebView handle while the browser is on screen (← iOS `weak var
    /// browser`); set by the controller on attach, cleared on detach.
    var browser: BrowserProxy? = null

    /// Participant-side completion (target reached / Finish study) that is
    /// already persisted — ended record written, active study cleared — but
    /// whose on-screen transition waits for the closing page (lead-out
    /// Custom Tab) to close, so it is not pulled away from under the
    /// participant. Applied on the next activity resume (§a2.8).
    private var pendingCompletion: EndedStudy? = null
    val hasPendingCompletion: Boolean
        get() = pendingCompletion != null

    /// Last hostname the injected tag reported "initialized" from (bridge
    /// health ping) — debug-build surface only (release never renders it).
    private val _tagInitializedHost = MutableStateFlow<String?>(null)
    val tagInitializedHost: StateFlow<String?> = _tagInitializedHost

    /// Scripted-QA auto flow (debug builds only — release never sets this;
    /// the Android analogue of the iOS MOVEO_AUTO_FLOW env var, §a2.7).
    var qaAutoFlow: String? = null

    /// Scripted-QA: URL to navigate the browser to after launch (debug only).
    var qaAutoNav: String? = null

    /// Scripted-QA: site the demo runs on when it is started without the
    /// picker (debug only; the iOS MOVEO_DEMO_SITE env var).
    var qaDemoSite: String? = null

    /// Scripted-QA / store-screenshot capture: opens one of the activation
    /// screen's full-screen destinations on launch — "howitworks", "demo" or
    /// "results" (debug only; the iOS MOVEO_AUTO_SHEET env var).
    var qaAutoSheet: String? = null

    /// DEBUG explicit ingest-redirect override (the iOS `ingestOverride`).
    var ingestOverride: String? = null

    /// Where the tag's events should be rerouted in DEBUG builds (the iOS
    /// `effectiveIngestRedirect`): an explicit override wins; otherwise a
    /// config that declares a different endpoint than the tag's baked one
    /// (dev studies) is honored automatically. Null = no redirect (prod
    /// behavior), always null in release. One definition for both consumers:
    /// the page-world fetch rewrite (DebugHooks.ingestRedirectScript) and the
    /// native relay below.
    val effectiveIngestRedirect: String?
        get() {
            if (!isDebugBuild) return null
            ingestOverride?.takeIf { it.isNotEmpty() }?.let { return it }
            return store.activeStudy?.config?.tracking?.apiUrl
                ?.takeIf { it != TagEndpoint.apiUrl }
        }

    val apiBase: HttpUrl
        get() {
            if (isDebugBuild) {
                store.apiBaseOverride?.toHttpUrlOrNull()?.let { return it }
            }
            return BackendConstants.API_BASE.toHttpUrl()
        }

    val configService: ConfigService
        get() = if (isDebugBuild) {
            // Dev studies declare the dev ingestion URL, which mismatches the
            // tag's baked prod endpoint; accept them and let the DEBUG ingest
            // redirect route events. Release keeps the strict guard.
            ConfigService(apiBase = apiBase, appVersion = appVersion, enforceTagEndpoint = false, debugLog = debugLog)
        } else {
            ConfigService(apiBase = apiBase, appVersion = appVersion)
        }

    /// Transaction id delivered by a setup link, waiting for the activation
    /// of THAT code to consume it. Keyed by code so a lingering id can never
    /// attach to a different code typed afterwards.
    private data class LinkTransactionId(val code: String, val id: String)
    private var linkTransactionId: LinkTransactionId? = null

    /// App Link / custom-scheme entry (the extension's `source: "link"`
    /// activation). A link-delivered code is treated exactly like a typed
    /// one — fetch → validate → confirm — plus it may carry the
    /// panel-provider transaction id.
    fun handleOpenUrl(url: String) {
        val link = SetupLink.parse(url) ?: return
        // Codes/ids only — never the token or config (security §6); debug
        // builds only (null sink in release).
        debugLog?.invoke(
            "openURL code ${link.code} transactionId ${link.transactionId ?: "-"} " +
                "active ${store.activeStudy?.code ?: "-"} phase ${_phase.value::class.simpleName}",
        )

        // Same code already active: idempotent, no re-fetch, no second
        // enrollment (the extension's `alreadyActive`). Straight to home.
        val active = store.activeStudy
        if (active != null && active.code == link.code) {
            linkTransactionId = null
            codeInput.value = ""
            _phase.value = Phase.Idle
            _browserPresented.value = false
            return
        }

        // Same code already on the consent screen: update in place instead
        // of restarting — a transaction id on the fresh link wins over a
        // stale or absent one.
        (_phase.value as? Phase.Consent)?.pending?.let { pending ->
            if (pending.code == link.code) {
                _phase.value = Phase.Consent(
                    pending.copy(transactionId = link.transactionId ?: pending.transactionId),
                )
                return
            }
        }

        linkTransactionId = link.transactionId?.let { LinkTransactionId(code = link.code, id = it) }
        codeInput.value = link.code
        // The consent screen is owned by ActivationScreen — it cannot show
        // under the study browser's full-screen cover.
        _browserPresented.value = false
        activate()
    }

    private data class ResolvedInput(val code: String, val transactionId: String?)

    /// `codeInput` accepts a bare code OR a pasted setup link (the only
    /// manual path that keeps the transaction id). Returns the code and the
    /// transaction id that applies to it.
    private fun resolveInput(): ResolvedInput? {
        val trimmed = codeInput.value.trim()
        SetupLink.parse(trimmed)?.let { return ResolvedInput(it.code, it.transactionId) }
        val code = Codes.normalize(trimmed) ?: return null
        val linked = linkTransactionId?.takeIf { it.code == code }?.id
        return ResolvedInput(code, linked)
    }

    /// The demo runs entirely on-device. Interception point 1 of 3:
    /// activation never reaches the network. (2: `acceptConsentNow` never
    /// enrolls. 3: `currentUserScriptSource` never injects the tag, and
    /// `revalidateNow` never re-fetches.)
    fun isDemoCode(code: String): Boolean = code == DemoStudy.CODE

    /// Entry point for the demo site picker. Goes to the same consent screen
    /// a real activation does — there is one demo path to reason about, and
    /// typing the reserved code is the same thing on the default site.
    fun startDemo(site: SiteInput.Parsed) {
        val config = DemoStudy.config(site)
        if (config == null) {
            _phase.value = Phase.Failed(
                title = "Check the website address",
                message = "Enter a website address like example.com, or paste a full link to the page " +
                    "the demo should start on.",
            )
            return
        }
        presentDemoConsent(config)
    }

    private fun presentDemoConsent(config: StudyConfig) {
        _consentError.value = null
        linkTransactionId = null
        codeInput.value = DemoStudy.CODE
        _browserPresented.value = false
        _phase.value = Phase.Consent(
            PendingActivation(
                code = DemoStudy.CODE,
                config = config,
                replacingName = store.activeStudy?.let { active ->
                    if (active.code == DemoStudy.CODE) null else active.config.study.name
                },
                // Never sent anywhere — the demo does not enroll — but the
                // type requires one and a real-looking id keeps the demo on
                // the same code path as a real activation.
                enrollmentId = Enrollment.generateId(),
                transactionId = null,
            ),
        )
    }

    fun activate() {
        scope.launch { activateNow() }
    }

    suspend fun activateNow() {
        val input = resolveInput()
        if (input == null) {
            _phase.value = Phase.Failed(
                title = "Check the code",
                message = "Study codes are 4–32 letters and numbers (dashes and spaces don't matter). " +
                    "You can also paste the full setup link.",
            )
            return
        }
        val code = input.code

        // The reserved code runs the demo on its default site — the picker is
        // the normal way in, this keeps codes and links consistent.
        if (isDemoCode(code)) {
            val config = DemoStudy.defaultConfig(qaDemoSite)
            if (config != null) {
                presentDemoConsent(config)
                runAutoFlowIfRequested()
                return
            }
        }

        _phase.value = Phase.Fetching
        when (val result = configService.fetchConfig(code = code)) {
            is ApiResult.Success -> {
                val config = result.value
                if (config.study.status == StudyConfig.Status.ENDED) {
                    _phase.value = Phase.StudyEnded(name = config.study.name)
                } else {
                    val replacing = store.activeStudy?.let { active ->
                        if (active.code == code) null else active.config.study.name
                    }
                    val pending = PendingActivation(
                        code = code, config = config, replacingName = replacing,
                        enrollmentId = Enrollment.generateId(),
                        transactionId = input.transactionId,
                    )
                    linkTransactionId = null // consumed by this activation
                    // Straight to consent. Nothing is stored yet and the
                    // backend hasn't been told anything; enrollment happens
                    // only on accept, and Decline is the way out.
                    _consentError.value = null
                    _phase.value = Phase.Consent(pending)
                    runAutoFlowIfRequested()
                }
            }
            is ApiResult.Failure -> _phase.value = failedPhase(result.error)
        }
    }

    /// Scripted QA (milestone smoke scripts, no UI interaction), driven from
    /// the consent screen whichever way it was reached — a real code or the
    /// demo:
    ///   MOVEO_AUTO_FLOW=enroll  — accept consent + enroll
    ///   MOVEO_AUTO_FLOW=browser — …and open the study browser
    private suspend fun runAutoFlowIfRequested() {
        when (qaAutoFlow) {
            "enroll" -> acceptConsentNow()
            "browser" -> {
                acceptConsentNow()
                if (_activeStudy.value != null) openBrowser()
            }
        }
    }

    /// Consent accepted → POST /enroll (the billing truth; 409 = success).
    /// Only after a successful enroll does the study become active locally.
    /// Retries (Accept tapped again after a network error) reuse the
    /// pending activation's enrollment id — never a new one per attempt.
    fun acceptConsent() {
        scope.launch { acceptConsentNow() }
    }

    suspend fun acceptConsentNow() {
        val pending = (_phase.value as? Phase.Consent)?.pending ?: return
        if (_enrolling.value) return

        // Interception point 2: the demo is not an enrollment. No participant
        // id is read, no call is made, and no consent record is written —
        // there is nothing to consent to when nothing is collected.
        if (isDemoCode(pending.code)) {
            store.activeStudy = ActiveStudy(
                code = pending.code,
                config = pending.config,
                enrolledAt = now(),
                enrollmentId = pending.enrollmentId,
            )
            store.endedStudy = null
            _activeStudy.value = store.activeStudy
            _endedStudy.value = null
            codeInput.value = ""
            _phase.value = Phase.Idle
            return
        }

        _enrolling.value = true
        _consentError.value = null
        try {
            val participantId = store.participantId()
            val result = configService.enroll(
                code = pending.code,
                participantId = participantId,
                enrollmentId = pending.enrollmentId,
                transactionId = pending.transactionId,
                consentTextVersion = ConsentConstants.TEXT_VERSION,
            )
            when (result) {
                is ApiResult.Success -> {
                    val acceptedAt = now()
                    store.consent = ConsentRecord(
                        code = pending.code, acceptedAt = acceptedAt, textVersion = ConsentConstants.TEXT_VERSION,
                    )
                    // Replaces any previous study wholesale (one active study per
                    // install; the confirmation sheet warned about the swap).
                    store.activeStudy = ActiveStudy(
                        code = pending.code,
                        config = pending.config,
                        enrolledAt = parseIsoDate(result.value.enrolledAt) ?: acceptedAt,
                        enrollmentId = pending.enrollmentId,
                        transactionId = pending.transactionId,
                    )
                    store.endedStudy = null
                    _activeStudy.value = store.activeStudy
                    _endedStudy.value = null
                    codeInput.value = ""
                    _phase.value = Phase.Idle
                }
                is ApiResult.Failure -> when (val error = result.error) {
                    is EnrollError.StudyEnded ->
                        _phase.value = Phase.StudyEnded(name = pending.config.study.name)
                    is EnrollError.EnrollmentClosed ->
                        _phase.value = Phase.Failed(
                            title = "Study is full",
                            message = "This study is no longer accepting participants.",
                        )
                    is EnrollError.Network ->
                        _consentError.value =
                            "The app couldn't reach Moveo One. Check your connection and tap Accept again."
                    is EnrollError.ConsentRequired, is EnrollError.Validation ->
                        _consentError.value =
                            "Enrollment didn't go through. Please try again, or contact study support."
                    is EnrollError.Server ->
                        _consentError.value =
                            "Moveo One had a problem (error ${error.status}). Please tap Accept to try again."
                }
            }
        } finally {
            _enrolling.value = false
        }
    }

    /// Decline ⇒ nothing stored, nothing tracked, no backend call. The
    /// pending activation (and its enrollment id) is discarded with it.
    fun declineConsent() {
        _consentError.value = null
        linkTransactionId = null
        _phase.value = Phase.Idle
    }

    /// M3 scope: clears local study state. M4 extends this to also clear the
    /// study-origin website data (cookies) and the injected user script.
    fun leaveStudy() {
        store.activeStudy = null
        store.consent = null
        store.endedStudy = null
        store.ownTagHosts = emptyMap() // extension deactivate() clears these too
        _activeStudy.value = null
        _endedStudy.value = null
        pendingCompletion = null
        linkTransactionId = null
        codeInput.value = ""
        _phase.value = Phase.Idle
    }

    fun backToEntry() {
        _phase.value = Phase.Idle
    }

    // MARK: - Re-validation & kill switch (a2.6)

    /// Runs on every app-foreground (the reliable trigger — background
    /// refresh is best-effort bonus only, not the mechanism).
    suspend fun revalidateNow() {
        presentDueLeadOut() // crash recovery for a pending lead-out (M4)

        val study = store.activeStudy ?: return
        // The demo has no server-side existence: re-validating it would 404
        // and revoke it out from under the participant. It ends only when the
        // participant finishes or leaves.
        if (study.isDemo) return
        // A record whose lead-out was already shown but is still active can
        // only come from a build where the lead-out did not end the study —
        // the participant finished; complete it now.
        if (study.leadOutShownAt != null) {
            completeStudy(study, leadOutShownAt = study.leadOutShownAt)
            applyPendingCompletionNow()
            return
        }
        when (val result = configService.fetchConfig(code = study.code)) {
            is ApiResult.Success -> {
                // The participant may have finished or left during the fetch
                // — never resurrect a study that storage no longer holds.
                if (store.activeStudy?.code != study.code) return
                val fresh = result.value
                if (fresh.study.status == StudyConfig.Status.ENDED) {
                    endStudy(study, revoked = false)
                } else if (fresh != study.config) {
                    // Leaf ids in targetProgress are positional paths into the
                    // targetAction tree (§4.3): if the author re-shapes the
                    // tree, a latched "t.1" could pre-satisfy a different
                    // condition (fail-open). Reset progress when the target
                    // definition changes; targetFired is deliberately kept —
                    // a reached target stays reached (extension applyNewConfig).
                    val targetChanged = fresh.flow.targetAction != study.config.flow.targetAction
                    val updated = study.copy(
                        config = fresh,
                        targetProgress = if (targetChanged) null else study.targetProgress,
                    )
                    store.activeStudy = updated
                    _activeStudy.value = updated
                    refreshUserScript()
                }
            }
            is ApiResult.Failure -> when (result.error) {
                // Code revoked/unknown → deactivate with a distinct message.
                is ActivationError.NotFound -> {
                    if (store.activeStudy?.code != study.code) return
                    endStudy(study, revoked = true)
                }
                // Network/server/rate-limit: keep the stale-but-active study —
                // tracking continues until a SUCCESSFUL fetch says otherwise
                // (same posture as the extension).
                else -> Unit
            }
        }
    }

    fun revalidate() {
        scope.launch { revalidateNow() }
    }

    private fun endStudy(study: ActiveStudy, revoked: Boolean) {
        // No fallback lead-out on end — `flow.leadOutOnEnd` was dropped from
        // the product (extension commit 973d0d1); the record keeps the
        // lead-out history for parity with the extension's endedStudy shape.
        val ended = EndedStudy(
            code = study.code,
            name = study.config.study.name,
            endedAt = now(),
            leadOutUrl = study.config.flow.leadOutUrl,
            leadOutShownAt = study.leadOutShownAt,
            revoked = revoked,
        )
        store.endedStudy = ended
        store.activeStudy = null
        _activeStudy.value = null
        _endedStudy.value = ended
        pendingCompletion = null
        _browserPresented.value = false
        _leadSheet.value = null
    }

    fun dismissEndedStudy() {
        store.endedStudy = null
        _endedStudy.value = null
    }

    // MARK: - Browser session (← iOS AppModel+Browser)

    /// First origin at `flow.prepositionPath` when configured, else its root.
    val browserStartUrl: String?
        get() = store.activeStudy?.config?.startUrl

    val studyOrigins: List<String>?
        get() = store.activeStudy?.config?.tracking?.origins

    fun openBrowser() {
        _browserPresented.value = true
    }

    fun closeBrowser() {
        _browserPresented.value = false
    }

    /// The combined user script for the CURRENT state — null when there is no
    /// study (no injection at all).
    fun currentUserScriptSource(): String? {
        val study = store.activeStudy ?: return null
        // Interception point 3: the demo never injects the tag. This is what
        // makes "nothing is recorded" a property of the build rather than a
        // promise in the copy — with no tag in the page there is no
        // collection and no transport.
        if (study.isDemo) return null
        val tag = assetLoader("moveo-one.js") ?: return null
        val bootstrap = assetLoader("moveo-bootstrap.android.js") ?: return null
        // event_match leaves still to be latched (config-schema §4.3): the
        // tree never reaches the page — only a flat list of unfired event
        // leaves with their ids, and nothing once the target has fired
        // (extension bridge: collectEventLeaves filtered by targetProgress).
        val eventLeaves = if (study.targetFired) {
            emptyList()
        } else {
            TargetMatch.unfiredEventLeaves(study.config.flow.targetAction, study.latchedLeafIds)
        }
        return ScriptBuilder.userScriptSource(
            config = study.config,
            tagSource = tag,
            bootstrapSource = bootstrap,
            yieldHosts = store.ownTagHosts.keys.toList(),
            eventLeaves = eventLeaves,
        )
    }

    /// Rebuild + reapply the injected script (config refresh, own-tag yield,
    /// target fired). Takes effect on the next page load.
    private fun refreshUserScript() {
        browser?.applyUserScript(currentUserScriptSource())
    }

    // MARK: - CSP relay (← iOS AppModel.relayTagRequest; extension: background TAG_FETCH → src/tag-relay.js)

    /// Answers one relayed tag request (`{ id, kind, body }` from the
    /// bootstrap's transport, already parsed). URL and token come from the
    /// active study, never from the message; `senderHost` is the posting
    /// frame's origin host. In DEBUG the ingest redirect applies here (the
    /// page-world fetch rewrite cannot see relayed requests) and the event
    /// spy mirrors relayed batches. Never throws; a refused or failed request
    /// is the `FAILED` reply, which the tag sees as a failed fetch.
    suspend fun relayTagRequestNow(message: JsonObject, senderHost: String): TagRelay.Reply {
        // The demo never injects the tag, so nothing legitimate relays from
        // it — refusing keeps "nothing is recorded" true even for a page that
        // forges relay messages against the demo's token.
        val study = store.activeStudy?.takeIf { !it.isDemo } ?: return TagRelay.Reply.FAILED
        val ingestUrl = effectiveIngestRedirect ?: TagEndpoint.apiUrl
        val request = TagRelay.buildRequest(
            message = message, study = study, senderHost = senderHost, ingestUrl = ingestUrl,
        ) ?: return TagRelay.Reply.FAILED
        relayedEventsObserver?.let { observe ->
            if ((message["kind"] as? JsonPrimitive)?.content == "events") {
                (message["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { body ->
                    observe(ingestUrl, senderHost.lowercase(), body)
                }
            }
        }
        return TagRelay.perform(request)
    }

    /// Callback form for the WebView listener. Runs in the app scope, not
    /// the browser's: a `pagehide` drain posted by an unloading page — or by
    /// a browser the participant is closing — must complete even after the
    /// WebView is gone (the iOS unstructured `Task` has the same lifetime).
    /// `onReply` is invoked on the main thread.
    fun relayTagRequest(message: JsonObject, senderHost: String, onReply: (TagRelay.Reply) -> Unit) {
        scope.launch { onReply(relayTagRequestNow(message, senderHost)) }
    }

    // MARK: - URL-driven flow (extension: target-service.handleUrlChange)

    fun handleBrowserUrlChange(url: String) {
        val study = store.activeStudy ?: return
        val parsed = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return
        }
        val scheme = parsed.scheme?.lowercase() ?: return
        if (scheme != "http" && scheme != "https") return
        val host = parsed.host ?: return
        if (!Origins.hostnameMatches(host, study.config.tracking.origins)) return

        // Lead-in (§4.1): on the participant's FIRST visit to a tracked
        // origin — not at enrollment. Once per enrollment; pointless after
        // completion.
        val leadIn = study.config.flow.leadInUrl
        if (study.leadInShownAt == null && !study.targetFired &&
            leadIn != null && _leadSheet.value == null
        ) {
            LeadUrl.build(leadIn, study.config.flow, participantId = { store.participantId() })?.let { url ->
                _leadSheet.value = LeadSheet(url = url, kind = LeadSheet.Kind.LEAD_IN)
            }
        }

        // url_match leaves (§4.1, §4.3) — evaluated natively on every URL
        // change (doUpdateVisitedHistory catches pushState too). event_match
        // leaves arrive via the bridge.
        if (study.targetFired || study.config.flow.targetAction == null) return
        latchUrlLeaves(study.code, url)
    }

    /// Latch every unfired url_match leaf the URL matches (extension
    /// target-service `latchUrlLeaves` / `evaluateUrlLeaves`). Also called
    /// with the sender frame's URL when an event leaf reports, so an
    /// and(url, event) tree does not wait for a navigation that never comes
    /// when the participant is already on the matching page. Each hit is
    /// re-checked by the single writer; the study is re-read per leaf since
    /// a latch may fire the target (and complete the study) mid-loop.
    private fun latchUrlLeaves(code: String, url: String) {
        val scheme = try {
            java.net.URI(url).scheme?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return
        if (scheme != "http" && scheme != "https") return
        val study = store.activeStudy?.takeIf { it.code == code && !it.targetFired } ?: return
        val progress = study.latchedLeafIds
        val hits = TargetMatch.flatten(study.config.flow.targetAction).mapNotNull { leaf ->
            (leaf as? TargetMatch.TargetLeaf.Url)
                ?.takeIf { it.id !in progress && TargetMatch.matchesUrl(url, it.pattern) }
                ?.id
        }
        for (leafId in hits) {
            if (store.activeStudy?.targetFired != false) return
            targetLeafReached(code, leafId, expected = LeafKind.URL)
        }
    }

    // MARK: - Bridge (a2.4)

    /// `senderHost` is the posting frame's origin host as the WebView reports
    /// it (the WebMessageListener's `sourceOrigin`) and `senderUrl` the
    /// frame's URL when known (the main frame's `WebView.url`; null for
    /// subframes) — both set by the engine, never by the page (the
    /// extension's `sender.url`). Only the "target" message consults them.
    fun handleBridgeMessage(
        type: String,
        body: Map<String, String?>,
        senderHost: String? = null,
        senderUrl: String? = null,
    ) {
        // Debug-only observability (null sink in release): type + hostname,
        // never event payloads.
        debugLog?.invoke("bridge: $type ${body["hostname"] ?: ""}")
        when (type) {
            "target" -> {
                // An event_match leaf hit in the page, identified by its
                // positional leaf id (extension TARGET_REACHED). The page can
                // spoof this — same trust class as ownTag — but the impact is
                // bounded: the id is shape-checked (bridge rule), resolved
                // against the CURRENT tree and must name an event leaf; the
                // sender frame must sit on a study origin (the bridge object
                // only exists there to begin with); and completion truth
                // lives in the analytics stream, so a spoof can at worst open
                // the participant's own lead-out early. The url leaves are
                // then evaluated against the sender's URL so an and(url,
                // event) tree fires when the participant is already on the
                // matching page.
                val leafId = body["leafId"]?.takeIf { TargetMatch.isValidLeafId(it) } ?: return
                val study = store.activeStudy?.takeIf { !it.targetFired } ?: return
                val host = senderHost?.takeIf { it.isNotEmpty() } ?: return
                if (!Origins.hostnameMatches(host, study.config.tracking.origins)) return
                targetLeafReached(study.code, leafId, expected = LeafKind.EVENT)
                senderUrl?.let { latchUrlLeaves(study.code, it) }
            }
            "ownTag" -> {
                val hostname = body["hostname"]?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
                if (store.ownTagHosts[hostname] == null) {
                    store.ownTagHosts = store.ownTagHosts + (hostname to now())
                    // Yield takes effect from the next page load on this host.
                    refreshUserScript()
                }
            }
            "initialized" -> {
                if (isDebugBuild) _tagInitializedHost.value = body["hostname"]
            }
            "session" -> {
                // Latest tag session id for this page load (the latest one
                // wins) — linked to the enrollment at study completion
                // (extension SESSION_OBSERVED). Untrusted page-adjacent
                // input: re-validate the shape even though the bootstrap
                // only reports our own init; the impact of a spoof is
                // bounded — at worst a wrong id is linked, and the
                // analytics stream stays the ground truth for which
                // sessions really happened. No serialization chain needed
                // (unlike the extension's async storage): bridge messages
                // and all state mutation share the main thread.
                val sessionId = body["sessionId"] ?: return
                if (!SessionIds.isValid(sessionId)) return
                val study = store.activeStudy ?: return // stale page after leave/replace
                if (study.sessionId == sessionId) return
                val updated = study.copy(sessionId = sessionId)
                store.activeStudy = updated
                _activeStudy.value = updated
            }
            "flushed" -> {
                // Completion flush answered (the controller resolves the wait;
                // this is the QA oracle — counts only, never payloads).
                debugLog?.invoke(
                    "completion flush → sent ${body["sent"] ?: "?"} timedOut ${body["timedOut"] ?: "?"}",
                )
            }
        }
    }

    // MARK: - Target & lead-out (extension: target-service)

    private val leadOutDelayMillis = (FlowConstants.LEAD_OUT_DELAY_SECONDS * 1000).toLong()
    private val completionFlushTimeoutMillis = (FlowConstants.COMPLETION_FLUSH_TIMEOUT_SECONDS * 1000).toLong()

    /// Which kind of leaf a report may latch — a page report can never
    /// claim a url leaf, and the native URL feed never claims an event leaf.
    private enum class LeafKind { URL, EVENT }

    /// Single writer for targetProgress and targetFired (extension
    /// `handleTargetLeafReached`). Safe to call redundantly — duplicate
    /// reports, racing url/event matchers and stale pages all collapse on
    /// the re-check. `leafId` is untrusted (it may come from the page): it
    /// is resolved against the current tree and must be a leaf of
    /// `expected`, so a page can neither invent ids nor claim a url leaf. A
    /// report naming the root ("t") on a composite tree resolves to no leaf
    /// and is dropped. Latching the last needed leaf fires the target —
    /// progress and targetFired go out in ONE write.
    private fun targetLeafReached(code: String, leafId: String, expected: LeafKind) {
        val study = store.activeStudy?.takeIf { it.code == code && !it.targetFired } ?: return
        val leaf = TargetMatch.findLeaf(study.config.flow.targetAction, leafId) ?: return
        val kind = if (leaf is TargetMatch.TargetLeaf.Url) LeafKind.URL else LeafKind.EVENT
        if (kind != expected) return
        val progress = study.targetProgress ?: emptyMap()
        if (progress.containsKey(leaf.id)) return // already latched
        val updated = study.copy(targetProgress = progress + (leaf.id to now()))

        if (TargetMatch.evaluate(updated.config.flow.targetAction, updated.latchedLeafIds)) {
            fireTarget(updated)
        } else {
            store.activeStudy = updated
            _activeStudy.value = updated
            // The latched event leaf is dropped from the next page load (the
            // extension bridge filters by targetProgress per load).
            refreshUserScript()
        }
    }

    /// The target is reached: marks it once per participant per study and
    /// schedules the lead-out. `leadOutDueAt` is persisted BEFORE the timer
    /// so an app kill inside the delay self-heals on next launch;
    /// `leadOutShownAt` is only written after the Custom Tab actually
    /// launches — lost lead-outs self-heal, shown ones never repeat. Reaching
    /// the target completes the study (extension `fireTarget`): via the
    /// lead-out when there is one, otherwise right here.
    private fun fireTarget(study: ActiveStudy) {
        val firedAt = now()
        var updated = study.copy(targetFired = true, targetFiredAt = firedAt)
        val hasLeadOut = updated.config.flow.leadOutUrl != null && updated.leadOutShownAt == null
        if (hasLeadOut) {
            updated = updated.copy(leadOutDueAt = firedAt.plusMillis(leadOutDelayMillis))
        }
        store.activeStudy = updated
        _activeStudy.value = updated
        refreshUserScript() // drops the event leaves from future loads
        if (hasLeadOut) {
            scope.launch {
                delay(leadOutDelayMillis)
                presentDueLeadOut()
            }
        } else {
            // Nothing to show — the target itself completes the study.
            // Persist now (crash-safe); let the participant see their own
            // confirmation page for the lead-out delay before the browser
            // gives way to the completion screen.
            completeStudy(updated, leadOutShownAt = updated.leadOutShownAt)
            scope.launch {
                delay(leadOutDelayMillis)
                applyPendingCompletionNow()
            }
        }
    }

    // MARK: - Completion (extension: config-service.finishStudy / completeStudy)

    /// "Finish study" — browser Done or the home-screen button, after the
    /// participant confirmed. Opens the lead-out now (with the panel
    /// transaction id, so the provider credits the completion) and completes
    /// the study; a lead-out already shown by the target flow is not shown
    /// again. Without a lead-out the study completes immediately.
    fun finishStudy() {
        val study = store.activeStudy ?: return
        if (study.config.flow.leadOutUrl != null && study.leadOutShownAt == null) {
            // Due-time persisted first: a kill before the Custom Tab launches
            // is recovered on the next foreground (presentDueLeadOut),
            // exactly like the target path.
            val updated = study.copy(leadOutDueAt = now())
            store.activeStudy = updated
            _activeStudy.value = updated
            presentDueLeadOut()
        } else {
            completeStudy(study, leadOutShownAt = study.leadOutShownAt)
            scope.launch { applyPendingCompletionNow() }
        }
    }

    /// Participant-side completion: write the ended record (the thank-you
    /// screen) and deactivate the study in storage NOW — same durability as
    /// the extension's completeStudy — while the visible transition is
    /// deferred to `applyPendingCompletionNow`. Tracking stops from the next
    /// page load (null user script). Idempotent.
    private fun completeStudy(study: ActiveStudy, leadOutShownAt: Instant?) {
        if (pendingCompletion != null) return
        reportSession(study)
        val ended = EndedStudy(
            code = study.code,
            name = study.config.study.name,
            endedAt = now(),
            leadOutUrl = study.config.flow.leadOutUrl,
            leadOutShownAt = leadOutShownAt,
            revoked = false,
            completed = true,
        )
        store.endedStudy = ended
        store.activeStudy = null
        store.consent = null
        store.ownTagHosts = emptyMap()
        pendingCompletion = ended
        refreshUserScript()
    }

    /// Link the tag's tracking session to the enrollment — POST
    /// `/{code}/sessions`, best effort (one retry inside ConfigService).
    /// The ids are captured from the record BEFORE completeStudy clears
    /// storage; the call itself is fire-and-forget so completion is
    /// persisted without waiting on the network (the extension awaits it
    /// only because deactivate() would wipe the ids it reads — recorded in
    /// plan §6). Skipped when the study never observed a session or the
    /// record predates enrollment ids — the extension's early-out.
    private fun reportSession(study: ActiveStudy) {
        val enrollmentId = study.enrollmentId ?: return
        val sessionId = study.sessionId ?: return
        val service = configService
        scope.launch {
            service.reportSession(
                code = study.code,
                participantId = store.participantId(),
                enrollmentId = enrollmentId,
                sessionId = sessionId,
            )
        }
    }

    /// Moves the UI to the completion screen: drains the tag's event buffer
    /// while the WebView is still alive, then closes the browser and clears
    /// the study website data (the participant's logins on the study sites
    /// do not outlive the study — same rule as leaving). No-op unless a
    /// completion is pending. UI state is mirrored FROM storage rather than
    /// forced to null: a setup link that arrived while the closing page was
    /// up (onNewIntent precedes onResume) may already be mid-activation.
    suspend fun applyPendingCompletionNow() {
        pendingCompletion ?: return
        pendingCompletion = null
        // Closing the browser tears down the web process; anything the tag
        // still holds (up to one 5 s flush interval, plus in-flight posts)
        // would be lost. Bounded wait — see COMPLETION_FLUSH_TIMEOUT_SECONDS.
        browser?.flushEvents(completionFlushTimeoutMillis)
        browser?.applyUserScript(null)
        browser?.clearBrowsingData()
        _browserPresented.value = false
        _leadSheet.value = null
        _activeStudy.value = store.activeStudy
        _endedStudy.value = store.endedStudy
        clearWebsiteData()
    }

    /// MainActivity.onResume — the Android "lead sheet dismissed" signal
    /// (the Custom Tab / system browser returned). A completion that waited
    /// for the closing page to close is applied now; otherwise a lead-out
    /// queued behind a lead-in gets its turn (crash recovery too).
    fun activityResumed() {
        if (pendingCompletion != null) {
            scope.launch { applyPendingCompletionNow() }
            return
        }
        presentDueLeadOut()
    }

    /// Opens a due lead-out (fast path: the timer above; recovery path:
    /// app-foreground re-validation / activity resume after a Custom Tab
    /// closes). No-ops while another sheet is pending. The lead-out — and
    /// only the lead-out — echoes the setup link's transaction id so the
    /// panel provider can credit the completion (extension: openDueLeadOut).
    fun presentDueLeadOut() {
        val study = store.activeStudy ?: return
        val dueAt = study.leadOutDueAt ?: return
        if (dueAt.isAfter(now())) return
        if (study.leadOutShownAt != null) return
        if (_leadSheet.value != null) return
        val raw = study.config.flow.leadOutUrl ?: return
        val url = LeadUrl.build(
            raw, study.config.flow,
            participantId = { store.participantId() },
            transactionId = study.transactionId,
        ) ?: return
        _leadSheet.value = LeadSheet(url = url, kind = LeadSheet.Kind.LEAD_OUT)
    }

    /// The UI layer attempted the Custom Tab launch. On success write the
    /// shown-at flags (the "after presentation" rule — launch is the closest
    /// Android signal to "rendered", plan §6); on failure keep the due state
    /// so the lead-out self-heals on a later foreground.
    fun leadSheetLaunched(sheet: LeadSheet, success: Boolean) {
        _leadSheet.value = null
        if (!success) return
        // QA oracle (§a3.2): the OS redacts intent URIs in logcat, so this is
        // the only observable record of the launched lead URL (debug builds
        // only; carries at most the pseudonymous participant id).
        debugLog?.invoke("lead: ${sheet.kind} ${sheet.url}")
        val study = store.activeStudy ?: return
        when (sheet.kind) {
            LeadSheet.Kind.LEAD_IN -> {
                val updated = study.copy(leadInShownAt = now())
                store.activeStudy = updated
                _activeStudy.value = updated
            }
            LeadSheet.Kind.LEAD_OUT -> {
                val shownAt = now()
                val updated = study.copy(leadOutShownAt = shownAt, leadOutDueAt = null)
                store.activeStudy = updated
                _activeStudy.value = updated
                // Lead-out shown → the participant is done (extension
                // openDueLeadOut → completeStudy). Persisted now; the screen
                // changes when they close the closing page (activityResumed).
                completeStudy(updated, leadOutShownAt = shownAt)
            }
        }
    }

    // MARK: - Leave with data clearing (a2.6)

    /// Leave clears study state AND the WebView environment wholesale
    /// (cookies, storage, cache) — the participant's login on the study site
    /// does not outlive the study. Android has no per-origin records API like
    /// WKWebsiteDataStore, and the WebView only ever held study browsing, so
    /// wholesale ⊇ per-origin (plan §6).
    fun leaveStudyAndClearData() {
        _browserPresented.value = false
        _leadSheet.value = null
        browser?.applyUserScript(null)
        browser?.clearBrowsingData()
        leaveStudy()
        clearWebsiteData()
    }

    /// Cookies + storage, wholesale (leave and completion; the lead surveys
    /// live in the Custom Tab's separate store and are untouched).
    private fun clearWebsiteData() {
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // WebView provider unavailable (browser never opened, JVM tests)
            // — nothing to clear.
        }
    }

    private fun parseIsoDate(raw: String?): Instant? {
        if (raw == null) return null
        return try {
            Instant.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    /// Participant-facing copy per error — kept aligned with the extension's
    /// landing-page wording so support sees one vocabulary.
    private fun failedPhase(error: ActivationError): Phase = when (error) {
        is ActivationError.NotFound -> Phase.Failed(
            title = "Code not recognized",
            message = "This code wasn't recognized. Check it and try again, or contact study support.",
        )
        is ActivationError.RateLimited -> Phase.Failed(
            title = "Too many attempts",
            message = "Please wait a moment and try again.",
        )
        is ActivationError.Network -> Phase.Failed(
            title = "Connection problem",
            message = "The app couldn't reach Moveo One. Check your connection and retry.",
        )
        is ActivationError.InvalidConfig -> Phase.Failed(
            title = "Study can't be loaded",
            message = "This study's configuration isn't valid. Contact study support.",
        )
        is ActivationError.NeedsAppUpdate -> Phase.Failed(
            title = "App update needed",
            message = "This study requires a newer version of this app. Please update it and try again.",
        )
        is ActivationError.Server -> Phase.Failed(
            title = "Something went wrong",
            message = "Moveo One had a problem (error ${error.status}). Please retry in a moment.",
        )
    }
}
