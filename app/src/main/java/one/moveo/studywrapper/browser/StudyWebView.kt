package one.moveo.studywrapper.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import one.moveo.studycore.Origins
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DebugHooks

/// What the model needs from the live WebView (← iOS BrowserProxy in
/// StudyWebView.swift). The controller implements this and registers itself
/// with the model while the browser is on screen.
interface BrowserProxy {
    /// Returns false when there is no history to go back to (the caller
    /// closes the browser instead — Android back dispatch).
    fun goBackIfPossible(): Boolean

    fun reload()

    fun load(url: String)

    /// Replace the injected user script (null = remove — study left).
    /// Affects the NEXT navigation, mirroring iOS applyUserScript and the
    /// extension's re-registration.
    fun applyUserScript(source: String?)

    /// Wholesale in-WebView cleanup on leave (cache + history; cookies and
    /// storage are cleared globally by the model).
    fun clearBrowsingData()
}

/// Both features have shipped in Android System WebView for years; the gate
/// is a safety net, not an expected path (§a0.3 — no racy fallback
/// injection, ever).
fun isWebViewSupported(): Boolean =
    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

/// The single WebView of the study browser (§a2.3–a2.5), owned by
/// StudyBrowserScreen for the duration of one browser presentation.
class StudyWebViewController(
    private val context: Context,
    private val model: AppViewModel,
) : BrowserProxy {
    private var webView: WebView? = null
    private val scriptHandlers = mutableListOf<ScriptHandler>()

    /// Mirrored from the study config so the (synchronous) navigation policy
    /// never re-reads storage. Refreshed on attach + script apply.
    private var origins: List<String> = model.studyOrigins ?: emptyList()

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        val webView = WebView(context)
        this.webView = webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // One WebView, v1: target=_blank loads in place (same as iOS
            // returning nil from createWebViewWith after loading).
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        // Persistent cookies: participants log in to the study site once per
        // study, not once per launch (cleared on leave — a2.6).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        installUserScripts(webView, model.currentUserScriptSource())
        installBridge(webView)

        webView.webViewClient = PolicyClient()
        webView.webChromeClient = PermissionDenyingChromeClient()
        // Downloads: ignore/deny in v1 (record if a study ever needs it).
        webView.setDownloadListener { _, _, _, _, _ -> }

        model.browser = this

        model.browserStartUrl?.let { webView.loadUrl(it) }

        // Scripted QA: navigate somewhere after launch (debug builds only —
        // release DebugHooks never sets qaAutoNav).
        model.qaAutoNav?.let { auto ->
            webView.postDelayed({ webView.loadUrl(auto) }, 4_000)
        }
        return webView
    }

    fun destroy() {
        if (model.browser === this) model.browser = null
        webView?.destroy()
        webView = null
        scriptHandlers.clear()
    }

    /// The full injection set for the CURRENT state, in order: DEBUG ingest
    /// redirect (must wrap fetch before the tag runs) then the production
    /// user script. The redirect comes from the debug source set and does
    /// not exist in release builds. Document-start, all matching frames,
    /// origin-scoped by the platform (§a0.3 layer 1).
    private fun installUserScripts(webView: WebView, source: String?) {
        val rules = allowedOriginRules() ?: return
        DebugHooks.ingestRedirectScript(model)?.let { redirect ->
            scriptHandlers += WebViewCompat.addDocumentStartJavaScript(webView, redirect, rules)
        }
        source?.let {
            scriptHandlers += WebViewCompat.addDocumentStartJavaScript(webView, it, rules)
        }
    }

    /// `allowedOriginRules` derived in ONE place (Origins, §a0.3): each
    /// origin O → https://O and https://*.O. Null when there is no study —
    /// nothing gets injected and no bridge exists.
    private fun allowedOriginRules(): Set<String>? {
        val origins = model.studyOrigins?.takeIf { it.isNotEmpty() } ?: return null
        this.origins = origins
        return Origins.allowedOriginRules(origins).toSet()
    }

    /// JS↔native bridge (§a2.4): same origin rules as injection, so the
    /// bridge object doesn't even exist off-study. Stricter than the iOS
    /// WKScriptMessageHandler, which is page-global.
    private fun installBridge(webView: WebView) {
        val rules = allowedOriginRules() ?: return
        WebViewCompat.addWebMessageListener(webView, "moveoNative", rules) { _, message, _, _, _ ->
            val data = message.data ?: return@addWebMessageListener
            val body = try {
                Json.parseToJsonElement(data).jsonObject.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.content
                }
            } catch (_: Exception) {
                return@addWebMessageListener
            }
            val type = body["type"] ?: return@addWebMessageListener
            model.handleBridgeMessage(type = type, body = body)
        }
    }

    // MARK: - BrowserProxy

    override fun goBackIfPossible(): Boolean {
        val webView = webView ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    override fun reload() {
        webView?.reload()
    }

    override fun load(url: String) {
        webView?.loadUrl(url)
    }

    /// Re-apply = remove all + add again (config refresh, own-tag yield,
    /// target fired, study left) — takes effect from the next navigation,
    /// mirroring iOS applyUserScript and the extension's re-registration.
    /// The whole set is rebuilt so a refresh never silently drops the DEBUG
    /// redirect script.
    override fun applyUserScript(source: String?) {
        val webView = webView ?: return
        scriptHandlers.forEach { it.remove() }
        scriptHandlers.clear()
        installUserScripts(webView, source)
    }

    override fun clearBrowsingData() {
        webView?.clearCache(true)
        webView?.clearHistory()
    }

    // MARK: - Navigation policy (§a2.5 — the scope-keeper, a0.3 layer 3)

    private inner class PolicyClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase() ?: ""

            // Non-web schemes (mailto:, tel:, market:, bankid:, …) go to the
            // OS — never render as WebView errors.
            if (scheme != "http" && scheme != "https") {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    // No handler installed — swallow rather than error.
                }
                return true
            }

            // Tapped external links leave the WebView — it must not become a
            // general browser. `hasGesture && !isRedirect` is the closest
            // Android signal to the iOS `.linkActivated` (plan §6). Off-origin
            // *redirect chains* (3-D Secure, PayPal, SSO) are NOT link taps
            // and pass through, untracked — blocking them would break the
            // checkout the study observes; origin-scoped injection keeps them
            // silent.
            if (request.isForMainFrame && request.hasGesture() && !request.isRedirect) {
                val host = url.host
                if (host != null && origins.isNotEmpty() &&
                    !Origins.hostnameMatches(host, origins)
                ) {
                    LeadSurveyLauncher.launch(context, url.toString())
                    return true
                }
            }
            return false
        }

        /// URL-change feed: fires for full loads AND SPA pushState/
        /// replaceState — the analogue of the iOS KVO on webView.url. Drives
        /// lead-in and native url_match evaluation.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            url?.let { model.handleBrowserUrlChange(it) }
        }
    }

    /// Camera/mic: deny (no study needs them in v1; keeps the Data safety
    /// form clean — §a2.3).
    private class PermissionDenyingChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }
}

