package one.moveo.studycore

/// Backend + contract constants. Counterpart of the extension's
/// `src/constants.js` (minus the Chrome-specific message enums).
object BackendConstants {
    /// Public config endpoint served by the platform backend ("pigeon").
    /// NOT api.moveo.one — that host is the analytics ingestion service and
    /// does not route /api/v1/extension-config (same trap the extension repo
    /// documents). Dev/mock: override via the DEBUG settings screen.
    const val API_BASE = "https://pigeon.moveo.one/api/v1/extension-config"

    /// The a0.2 enroll-marker decision (mirrors iOS i0.2, locked 2026-08-14):
    /// the enroll body carries BOTH `client: "android"` and
    /// `extensionVersion: "android/<version>"` — the docs' preferred new
    /// field plus the interim version-prefix so backends that ignore unknown
    /// fields still see the split. Pending backend-owner sign-off that
    /// "android" is admitted (and billed/segmented); change only here.
    const val CLIENT_MARKER = "android"

    // TODO(a4): confirm final privacy policy URL before store release
    // (same open item as the extension and iOS).
    const val PRIVACY_POLICY_URL = "https://www.moveo.one/privacy"
}

/// Flow timing shared with the extension (its `LEAD_OUT_DELAY_MS`).
object FlowConstants {
    /// Delay between the target action firing and the lead-out opening.
    const val LEAD_OUT_DELAY_SECONDS = 2.0
}

/// Consent wording version (a0.5). Bump on ANY change to the wording shown in
/// ConsentScreen — the backend stores this string with the enrollment as the
/// GDPR audit reference, so each version must uniquely identify one exact
/// text. The `android-` prefix keeps it distinct from `ios-*` and extension
/// versions (each platform's wording must be identified independently).
object ConsentConstants {
    const val TEXT_VERSION = "android-2026-08-18"
}

/// Why activation (code → config) failed. Cases mirror the extension's error
/// enum where one exists; copy shown to participants lives in the app layer.
sealed class ActivationError {
    /// 404 — unknown/malformed/draft code. Deliberately generic upstream:
    /// the backend never reveals whether a code existed.
    data object NotFound : ActivationError()

    /// 429 — back off; `retryAfterSeconds` from the Retry-After header when present.
    data class RateLimited(val retryAfterSeconds: Double?) : ActivationError()

    /// Transport-level failure (offline, DNS, TLS…).
    data class Network(val message: String) : ActivationError()

    /// Config failed validation (or its apiUrl doesn't match the vendored tag).
    data class InvalidConfig(val errors: List<String>) : ActivationError()

    /// Config schemaVersion is newer than this build supports.
    data object NeedsAppUpdate : ActivationError()

    /// Any other HTTP status.
    data class Server(val status: Int) : ActivationError()
}

/// Why enrollment (consent → billing record) failed. 409 is NOT here — it is
/// success by contract (idempotent re-activation, never double-billed).
sealed class EnrollError {
    /// 410 — study ended between activation and consent.
    data object StudyEnded : EnrollError()

    /// 403 — enrollment cap reached.
    data object EnrollmentClosed : EnrollError()

    /// 400 — consent missing/false (should be unreachable from the app flow).
    data object ConsentRequired : EnrollError()

    /// 422 — request validation error.
    data object Validation : EnrollError()

    data class Network(val message: String) : EnrollError()

    data class Server(val status: Int) : EnrollError()
}
