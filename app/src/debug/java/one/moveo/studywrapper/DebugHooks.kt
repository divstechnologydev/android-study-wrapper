package one.moveo.studywrapper

import android.content.Intent

/// DEBUG variant of the QA hooks (§a2.7). The release source set replaces
/// this object with no-ops, so none of it exists in the release binary —
/// the Android analogue of the iOS `#if DEBUG` invariant.
///
/// No on-screen entry point since 2026-10-01 (with iOS, which dropped its
/// nav-bar gear): every override below is driven by intent extras instead,
/// so no QA affordance can appear in a screenshot or under a reviewer's
/// thumb. `DebugSettingsScreen` is kept, unreferenced, for whoever wants the
/// panel back.
object DebugHooks {
    const val isDebugBuild = true

    /// Scripted QA via intent extras (replaces the iOS MOVEO_* env vars):
    ///   adb shell am start -n one.moveo.studywrapper/.MainActivity \
    ///     -e MOVEO_API_BASE http://10.0.2.2:8787/api/v1/extension-config \
    ///     -e MOVEO_AUTO_CODE TESTCODE1234 -e MOVEO_AUTO_FLOW enroll \
    ///     -e MOVEO_DEMO_SITE example.com/pricing \
    ///     -e MOVEO_AUTO_SHEET howitworks|demo|results \
    ///     -e MOVEO_AUTO_NAV https://example.com/checkout/x/thanks
    fun applyLaunchExtras(intent: Intent?, model: AppViewModel) {
        intent ?: return
        intent.getStringExtra("MOVEO_API_BASE")?.takeIf { it.isNotEmpty() }?.let {
            model.store.apiBaseOverride = it
        }
        intent.getStringExtra("MOVEO_AUTO_FLOW")?.takeIf { it.isNotEmpty() }?.let {
            model.qaAutoFlow = it
        }
        intent.getStringExtra("MOVEO_AUTO_NAV")?.takeIf { it.isNotEmpty() }?.let {
            model.qaAutoNav = it
            // A live browser navigates immediately (scripted target tests);
            // otherwise the value is consumed at WebView creation.
            model.browser?.load(it)
        }
        intent.getStringExtra("MOVEO_EVENT_SPY")?.let {
            EventSpy.extraEnabled = it == "1"
        }
        intent.getStringExtra("MOVEO_INGEST_OVERRIDE")?.takeIf { it.isNotEmpty() }?.let {
            model.ingestOverride = it
        }
        intent.getStringExtra("MOVEO_DEMO_SITE")?.takeIf { it.isNotEmpty() }?.let {
            model.qaDemoSite = it
        }
        intent.getStringExtra("MOVEO_AUTO_SHEET")?.takeIf { it.isNotEmpty() }?.let {
            model.qaAutoSheet = it
        }
        intent.getStringExtra("MOVEO_AUTO_CODE")?.takeIf { it.isNotEmpty() }?.let { code ->
            model.codeInput.value = code
            model.activate()
        }
    }

    /// M5 event spy (§a3.1): the spy user script (null when disabled) and
    /// its separate "moveoDebug" bridge. Both no-ops in release.
    fun eventSpyScript(): String? = EventSpy.script()

    fun installEventSpyBridge(webView: android.webkit.WebView, rules: Set<String>) =
        EventSpy.installBridge(webView, rules)

    /// CSP relay parity capture: relayed event batches never touch
    /// `window.fetch`, so the spy's page-world wrapper cannot see them — the
    /// model hands them here instead (same JSONL capture format, same
    /// `moveo-events` log feed). The relay's own on/off state is the spy's.
    /// Release wires null, so the model carries no observer code there.
    val relayedEventsObserver: ((url: String, host: String, body: String) -> Unit) =
        { url, host, body -> EventSpy.observeRelayedPost(url = url, host = host, body = body) }

    /// DEBUG ingest redirect (§a2.7): rewrites the tag's page-world fetch
    /// URLs so dev studies' events reach the dev ingestion host. The target
    /// is the model's `effectiveIngestRedirect` (one definition shared with
    /// the native relay, which reroutes the relayed requests itself). Null =
    /// no redirect (prod behavior). The release variant of this object
    /// always returns null AND the template asset only exists in the debug
    /// source set.
    fun ingestRedirectScript(model: AppViewModel): String? {
        val target = model.effectiveIngestRedirect ?: return null
        val template = try {
            appContext?.assets?.open("moveo-ingest-redirect.android.js")
                ?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        } ?: return null
        return template
            .replace("__MOVEO_INGEST_FROM__", one.moveo.studycore.generated.TagEndpoint.apiUrl)
            .replace("__MOVEO_INGEST_TO__", target)
    }

    /// Set once by App.onCreate — asset access for the redirect template.
    var appContext: android.content.Context? = null
}
