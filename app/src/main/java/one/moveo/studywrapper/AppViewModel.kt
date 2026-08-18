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
import one.moveo.studycore.SetupLink
import one.moveo.studycore.StudyConfig
import one.moveo.studycore.StudyStore

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

    /// Scripted-QA auto flow (debug builds only — release never sets this;
    /// the Android analogue of the iOS MOVEO_AUTO_FLOW env var, §a2.7).
    var qaAutoFlow: String? = null

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
    }

    fun dismissEndedStudy() {
        store.endedStudy = null
        _endedStudy.value = null
    }

    // MARK: - M4 stubs (browser + injection + lead flow, phase a2.3–a2.6)

    fun openBrowser() {
        _browserPresented.value = true
    }

    private fun presentDueLeadOut() {
        // a2.6: recover a persisted leadOutDueAt after an app kill.
    }

    private fun refreshUserScript() {
        // a2.3: re-apply the document-start script after a config change.
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
