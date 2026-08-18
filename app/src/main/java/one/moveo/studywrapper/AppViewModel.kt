package one.moveo.studywrapper

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import one.moveo.studycore.FlowConstants
import one.moveo.studycore.Origins
import one.moveo.studycore.ScriptBuilder
import one.moveo.studycore.SetupLink
import one.moveo.studycore.StudyConfig
import one.moveo.studycore.StudyStore
import one.moveo.studycore.TargetAction
import one.moveo.studycore.TargetMatch
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    data class PendingActivation(
        val code: String,
        val config: StudyConfig,
        /// Name of the currently active study this activation would replace.
        val replacingName: String?,
    )

    sealed class Phase {
        data object Idle : Phase()
        data object Fetching : Phase()
        data class Failed(val title: String, val message: String) : Phase()
        data class StudyEnded(val name: String) : Phase()
        /// Confirmation accepted — showing the consent screen (a0.5 wording).
        data class Consent(val pending: PendingActivation) : Phase()
    }

    val codeInput = MutableStateFlow("")
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /// Non-null shows the study-summary confirmation sheet.
    private val _pendingConfirmation = MutableStateFlow<PendingActivation?>(null)
    val pendingConfirmation: StateFlow<PendingActivation?> = _pendingConfirmation

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

    /// Last hostname the injected tag reported "initialized" from (bridge
    /// health ping) — debug-build surface only (release never renders it).
    private val _tagInitializedHost = MutableStateFlow<String?>(null)
    val tagInitializedHost: StateFlow<String?> = _tagInitializedHost

    /// Scripted-QA auto flow (debug builds only — release never sets this;
    /// the Android analogue of the iOS MOVEO_AUTO_FLOW env var, §a2.7).
    var qaAutoFlow: String? = null

    /// Scripted-QA: URL to navigate the browser to after launch (debug only).
    var qaAutoNav: String? = null

    /// DEBUG explicit ingest-redirect override (the iOS `ingestOverride`).
    var ingestOverride: String? = null

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

    /// App Link / custom-scheme entry. A scheme-delivered code is treated
    /// exactly like a typed one: fetch → validate → confirm.
    fun handleOpenUrl(url: String) {
        val code = SetupLink.code(from = url) ?: return
        codeInput.value = code
        activate()
    }

    fun activate() {
        scope.launch { activateNow() }
    }

    suspend fun activateNow() {
        val code = Codes.normalize(codeInput.value)
        if (code == null) {
            _phase.value = Phase.Failed(
                title = "Check the code",
                message = "Study codes are 4–32 letters and numbers (dashes and spaces don't matter).",
            )
            return
        }
        _phase.value = Phase.Fetching
        _pendingConfirmation.value = null
        when (val result = configService.fetchConfig(code = code)) {
            is ApiResult.Success -> {
                val config = result.value
                if (config.study.status == StudyConfig.Status.ENDED) {
                    _phase.value = Phase.StudyEnded(name = config.study.name)
                } else {
                    val replacing = store.activeStudy?.let { active ->
                        if (active.code == code) null else active.config.study.name
                    }
                    val pending = PendingActivation(code = code, config = config, replacingName = replacing)
                    _phase.value = Phase.Idle
                    // Scripted QA (milestone smoke scripts, no UI interaction):
                    //   MOVEO_AUTO_FLOW=consent — jump to the consent screen
                    //   MOVEO_AUTO_FLOW=enroll  — accept consent + enroll too
                    when (qaAutoFlow) {
                        "consent" -> {
                            _phase.value = Phase.Consent(pending)
                            return
                        }
                        "enroll" -> {
                            _phase.value = Phase.Consent(pending)
                            acceptConsentNow()
                            return
                        }
                        "browser" -> {
                            _phase.value = Phase.Consent(pending)
                            acceptConsentNow()
                            if (_activeStudy.value != null) openBrowser()
                            return
                        }
                    }
                    _pendingConfirmation.value = pending
                }
            }
            is ApiResult.Failure -> _phase.value = failedPhase(result.error)
        }
    }

    /// Study summary confirmed — show consent. Nothing is stored yet and the
    /// backend hasn't been told anything; enrollment happens only on accept.
    fun confirmActivation() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        _consentError.value = null
        _phase.value = Phase.Consent(pending)
    }

    fun cancelActivation() {
        _pendingConfirmation.value = null
        _phase.value = Phase.Idle
    }

    /// Consent accepted → POST /enroll (the billing truth; 409 = success).
    /// Only after a successful enroll does the study become active locally.
    fun acceptConsent() {
        scope.launch { acceptConsentNow() }
    }

    suspend fun acceptConsentNow() {
        val pending = (_phase.value as? Phase.Consent)?.pending ?: return
        if (_enrolling.value) return
        _enrolling.value = true
        _consentError.value = null
        try {
            val participantId = store.participantId()
            val result = configService.enroll(
                code = pending.code,
                participantId = participantId,
                consentTextVersion = ConsentConstants.TEXT_VERSION,
            )
            when (result) {
                is ApiResult.Success -> {
                    val now = Instant.now()
                    store.consent = ConsentRecord(
                        code = pending.code, acceptedAt = now, textVersion = ConsentConstants.TEXT_VERSION,
                    )
                    // Replaces any previous study wholesale (one active study per
                    // install; the confirmation sheet warned about the swap).
                    store.activeStudy = ActiveStudy(
                        code = pending.code,
                        config = pending.config,
                        enrolledAt = parseIsoDate(result.value.enrolledAt) ?: now,
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

    /// Decline ⇒ nothing stored, nothing tracked, no backend call.
    fun declineConsent() {
        _consentError.value = null
        _phase.value = Phase.Idle
    }

    /// M3 scope: clears local study state. M4 extends this to also clear the
    /// study-origin website data (cookies) and the injected user script.
    fun leaveStudy() {
        store.activeStudy = null
        store.consent = null
        store.endedStudy = null
        _activeStudy.value = null
        _endedStudy.value = null
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
        when (val result = configService.fetchConfig(code = study.code)) {
            is ApiResult.Success -> {
                val fresh = result.value
                if (fresh.study.status == StudyConfig.Status.ENDED) {
                    endStudy(study, revoked = false)
                } else if (fresh != study.config) {
                    val updated = study.copy(config = fresh)
                    store.activeStudy = updated
                    _activeStudy.value = updated
                    refreshUserScript()
                }
            }
            is ApiResult.Failure -> when (result.error) {
                // Code revoked/unknown → deactivate with a distinct message.
                is ActivationError.NotFound -> endStudy(study, revoked = true)
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
            endedAt = Instant.now(),
            leadOutUrl = study.config.flow.leadOutUrl,
            leadOutShownAt = study.leadOutShownAt,
            revoked = revoked,
        )
        store.endedStudy = ended
        store.activeStudy = null
        _activeStudy.value = null
        _endedStudy.value = ended
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
        val tag = assetLoader("moveo-one.js") ?: return null
        val bootstrap = assetLoader("moveo-bootstrap.android.js") ?: return null
        val includeEventTarget =
            study.config.flow.targetAction is TargetAction.EventMatch && !study.targetFired
        return ScriptBuilder.userScriptSource(
            config = study.config,
            tagSource = tag,
            bootstrapSource = bootstrap,
            yieldHosts = store.ownTagHosts.keys.toList(),
            includeEventTarget = includeEventTarget,
        )
    }

    /// Rebuild + reapply the injected script (config refresh, own-tag yield,
    /// target fired). Takes effect on the next page load.
    private fun refreshUserScript() {
        browser?.applyUserScript(currentUserScriptSource())
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
            leadUrl(leadIn, study.config.flow)?.let { url ->
                _leadSheet.value = LeadSheet(url = url, kind = LeadSheet.Kind.LEAD_IN)
            }
        }

        // url_match target (§4.2.2) — evaluated natively on every URL change
        // (doUpdateVisitedHistory catches pushState too). event_match arrives
        // via the bridge.
        val target = study.config.flow.targetAction
        if (!study.targetFired && target is TargetAction.UrlMatch &&
            TargetMatch.matchesUrl(url, target.pattern)
        ) {
            targetReached(study)
        }
    }

    // MARK: - Bridge (a2.4)

    fun handleBridgeMessage(type: String, body: Map<String, String?>) {
        // Debug-only observability (null sink in release): type + hostname,
        // never event payloads.
        debugLog?.invoke("bridge: $type ${body["hostname"] ?: ""}")
        when (type) {
            "target" -> {
                val study = store.activeStudy ?: return
                if (!study.targetFired) targetReached(study)
            }
            "ownTag" -> {
                val hostname = body["hostname"]?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
                if (store.ownTagHosts[hostname] == null) {
                    store.ownTagHosts = store.ownTagHosts + (hostname to Instant.now())
                    // Yield takes effect from the next page load on this host.
                    refreshUserScript()
                }
            }
            "initialized" -> {
                if (isDebugBuild) _tagInitializedHost.value = body["hostname"]
            }
        }
    }

    // MARK: - Target & lead-out (extension: target-service)

    /// Marks the target once per participant per study, schedules the
    /// lead-out. `leadOutDueAt` is persisted BEFORE the timer so an app kill
    /// inside the delay self-heals on next launch; `leadOutShownAt` is only
    /// written after the Custom Tab actually launches — lost lead-outs
    /// self-heal, shown ones never repeat.
    private fun targetReached(study: ActiveStudy) {
        val now = Instant.now()
        var updated = study.copy(targetFired = true, targetFiredAt = now)
        if (updated.config.flow.leadOutUrl != null && updated.leadOutShownAt == null) {
            updated = updated.copy(
                leadOutDueAt = now.plusMillis((FlowConstants.LEAD_OUT_DELAY_SECONDS * 1000).toLong()),
            )
        }
        store.activeStudy = updated
        _activeStudy.value = updated
        refreshUserScript() // drops the event_match spec from future loads
        if (updated.leadOutDueAt != null) {
            scope.launch {
                kotlinx.coroutines.delay((FlowConstants.LEAD_OUT_DELAY_SECONDS * 1000).toLong())
                presentDueLeadOut()
            }
        }
    }

    /// Opens a due lead-out (fast path: the timer above; recovery path:
    /// app-foreground re-validation / activity resume after a Custom Tab
    /// closes). No-ops while another sheet is pending.
    fun presentDueLeadOut() {
        val study = store.activeStudy ?: return
        val dueAt = study.leadOutDueAt ?: return
        if (dueAt.isAfter(Instant.now())) return
        if (study.leadOutShownAt != null) return
        if (_leadSheet.value != null) return
        val raw = study.config.flow.leadOutUrl ?: return
        val url = leadUrl(raw, study.config.flow) ?: return
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
        val updated = when (sheet.kind) {
            LeadSheet.Kind.LEAD_IN -> study.copy(leadInShownAt = Instant.now())
            LeadSheet.Kind.LEAD_OUT -> study.copy(leadOutShownAt = Instant.now(), leadOutDueAt = null)
        }
        store.activeStudy = updated
        _activeStudy.value = updated
    }

    /// Lead URL with the participant id appended — only when the study
    /// author opted in (it hands the id to a third-party form tool).
    private fun leadUrl(raw: String, flow: StudyConfig.Flow): String? {
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (!flow.appendParticipantId) return parsed.toString()
        return parsed.newBuilder()
            .removeAllQueryParameters("participantId")
            .addQueryParameter("participantId", store.participantId())
            .build()
            .toString()
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
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // WebView provider unavailable (browser never opened) — nothing
            // to clear.
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
