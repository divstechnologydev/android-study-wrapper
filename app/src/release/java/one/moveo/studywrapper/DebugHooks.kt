package one.moveo.studywrapper

import android.content.Intent

/// RELEASE variant: the QA surface does not exist in the release binary
/// (§a2.7) — intent extras are ignored, no settings screen, no ingest
/// redirect (release keeps the strict tag-endpoint guard and is
/// pinned to prod).
object DebugHooks {
    const val isDebugBuild = false

    var appContext: android.content.Context? = null

    /// No relay observer in release: the model's observer branch is dead.
    val relayedEventsObserver: ((url: String, host: String, body: String) -> Unit)? = null

    @Suppress("UNUSED_PARAMETER")
    fun applyLaunchExtras(intent: Intent?, model: AppViewModel) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun ingestRedirectScript(model: AppViewModel): String? = null

    fun eventSpyScript(): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun installEventSpyBridge(webView: android.webkit.WebView, rules: Set<String>) = Unit
}
