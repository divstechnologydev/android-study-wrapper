package one.moveo.studywrapper

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import one.moveo.studycore.ConfigValidator
import one.moveo.studycore.Origins
import one.moveo.studycore.ScriptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/// Instrumented smoke test for the CSP relay — the Android analogue of the
/// iOS `Scripts/relay-smoke.swift` (an offscreen WKWebView). A real Android
/// System WebView navigates to `https://shop.example.com/products/1`, served
/// from `shouldInterceptRequest` with a `Content-Security-Policy:
/// connect-src 'none'` header (a `loadDataWithBaseURL` document does not get
/// its CSP enforced, and a real site's policy is a header anyway), with the
/// production injection (ScriptBuilder: payload → guard → vendored tag →
/// bootstrap) and a `moveoFetch` listener standing in for the app. Nothing
/// else leaves the device: every other request is answered 404 by the
/// interceptor. Passes when the direct page-world fetch is refused by the
/// CSP while the tag's events and IP lookup still arrive through the relay,
/// the flush bridge resolves, and the bridge posts (initialized / session /
/// target) keep working.
///
///   scripts/relay-smoke.sh     (= ./gradlew :app:connectedPlayDebugAndroidTest
///                               filtered to this class; needs a device/AVD)
///
/// Re-run after every scripts/vendor-tag.sh — the seam pins in studycore's
/// TagRelayTests catch renames; this catches behaviour.
@RunWith(AndroidJUnit4::class)
class RelaySmokeTest {
    private class Relayed(val kind: String, val body: String, val host: String)

    private val relayed = java.util.Collections.synchronizedList(mutableListOf<Relayed>())
    private val bridge = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val probes = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val probeArrived = java.util.Collections.synchronizedMap(mutableMapOf<String, CountDownLatch>())

    /// The tag's own "I am up" signals: its `initialized` bridge post and the
    /// IP lookup it relays at init. The probes wait for these instead of a
    /// fixed sleep — a cold WebView (first run on a fresh AVD) needs several
    /// seconds before the page even commits.
    private val initialized = CountDownLatch(1)
    private val ipRelayed = CountDownLatch(1)

    private val pageUrl = "https://shop.example.com/products/1"
    private val csp = "default-src 'self' 'unsafe-inline'; connect-src 'none'"
    private val html = """
        <!doctype html><html><head>
        <meta http-equiv="Content-Security-Policy" content="$csp">
        <title>CSP strict</title></head>
        <body><h1>Strict CSP page</h1><button id="buy">Buy now</button>
        <button id="plus" aria-label="add 1 Bananas"><svg width="10" height="10"></svg></button></body></html>
    """.trimIndent()

    /// Serves the strict page for the study URL and refuses everything else,
    /// so the test is hermetic — a request that reaches here was NOT blocked
    /// by the CSP (the renderer enforces the policy before the network side).
    private inner class StrictSite : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            return if (request.url.toString() == pageUrl) {
                WebResourceResponse(
                    "text/html", "utf-8", 200, "OK",
                    mapOf("Content-Security-Policy" to csp),
                    html.byteInputStream(),
                )
            } else {
                WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    private fun probe(name: String): CountDownLatch = probeArrived.getOrPut(name) { CountDownLatch(1) }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    @Test
    fun eventsSurviveAStrictConnectSrcThroughTheRelay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tag = context.assets.open("moveo-one.js").bufferedReader().use { it.readText() }
        val bootstrap = context.assets.open("moveo-bootstrap.android.js").bufferedReader().use { it.readText() }
        val config = checkNotNull(
            ConfigValidator.validate(
                """
                {"schemaVersion":1,
                 "study":{"id":"st_smoke","name":"Smoke","status":"active"},
                 "tracking":{"token":"tok-test","apiUrl":"https://api.moveo.one/api/analytic/event/tag","origins":["example.com"]},
                 "flow":{"targetAction":{"type":"event_match","props":{"eA":"click","eV":"Buy now"}}}}
                """.trimIndent(),
            ).configOrNull,
        )
        val script = ScriptBuilder.userScriptSource(
            config = config, tagSource = tag, bootstrapSource = bootstrap,
            yieldHosts = emptyList(), includeEventTarget = true,
        )
        val rules = Origins.allowedOriginRules(config.tracking.origins).toSet()
        val main = Handler(Looper.getMainLooper())
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.layout(0, 0, 800, 600)
            WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
            WebViewCompat.addWebMessageListener(webView, "moveoNative", rules) { _, message, _, _, _ ->
                val data = message.data ?: return@addWebMessageListener
                val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@addWebMessageListener
                val type = json["type"]?.jsonPrimitive?.content ?: return@addWebMessageListener
                if (type == "probe") {
                    val name = json["name"]?.jsonPrimitive?.content ?: return@addWebMessageListener
                    probes[name] = json["value"]?.jsonPrimitive?.content ?: ""
                    probe(name).countDown()
                } else {
                    bridge += type
                    if (type == "initialized") initialized.countDown()
                }
            }
            // The app's stand-in: records the relay traffic and replies
            // asynchronously, like the real OkHttp path.
            WebViewCompat.addWebMessageListener(webView, "moveoFetch", rules) { _, message, sourceOrigin, _, replyProxy ->
                val data = message.data ?: return@addWebMessageListener
                val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@addWebMessageListener
                val id = json["id"]?.jsonPrimitive?.content ?: return@addWebMessageListener
                val kind = json["kind"]?.jsonPrimitive?.content ?: "?"
                relayed += Relayed(kind, json["body"]?.jsonPrimitive?.content ?: "", sourceOrigin.host.orEmpty())
                if (kind == "ip") ipRelayed.countDown()
                val body = if (kind == "ip") """{"ip":"203.0.113.9"}""" else "{}"
                main.postDelayed({
                    replyProxy.postMessage(one.moveo.studycore.TagRelay.Reply(ok = true, status = 200, body = body).toMessage(id))
                }, 50)
            }
            webView.webViewClient = StrictSite()
            webView.loadUrl(pageUrl)
        }

        /// Runs page-world JS whose (async) result is posted back as a
        /// `probe` bridge message — evaluateJavascript cannot await a Promise.
        fun runProbe(name: String, expression: String): String {
            val latch = probe(name)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    """
                    (function () {
                      var done = function (v) {
                        try { window.moveoNative.postMessage(JSON.stringify({ type: "probe", name: "$name", value: String(v) })); } catch (e) {}
                      };
                      try { Promise.resolve($expression).then(done, function (e) { done("ERROR: " + (e && e.message)); }); }
                      catch (e) { done("ERROR: " + (e && e.message)); }
                    })();
                    """.trimIndent(),
                    null,
                )
            }
            assertTrue("probe $name never answered", latch.await(15, TimeUnit.SECONDS))
            return probes.getValue(name)
        }

        // Let the tag initialise (IP lookup + start_session go out at init):
        // wait for its signals, not the clock — WebView start-up on a cold
        // device is slower than any fixed sleep that is still a smoke test.
        assertTrue("tag never posted `initialized`", initialized.await(30, TimeUnit.SECONDS))
        assertTrue("ip lookup never relayed", ipRelayed.await(30, TimeUnit.SECONDS))
        // The stand-in answers the lookup 50 ms later; let the tag record the
        // IP before the clicks whose events must carry it.
        Thread.sleep(500)

        val direct = runProbe("direct", "fetch('https://api.moveo.one/api/my-ip').then(function (r) { return 'reached ' + r.status; }, function (e) { return 'blocked: ' + e.message; })")
        assertTrue("direct page-world fetch is refused by the page CSP: $direct", direct.startsWith("blocked"))

        runProbe("click", "(document.getElementById('buy').click(), document.getElementById('plus').click(), typeof window.__moveoFlush)")
        val flush = runProbe("flush", "window.__moveoFlush(3000).then(function (r) { return JSON.stringify(r); })")
        val flushJson = Json.parseToJsonElement(flush).jsonObject
        assertEquals("__moveoFlush resolved without timing out: $flush", "false", flushJson["timedOut"]?.jsonPrimitive?.content)
        assertTrue("flush tracked at least one relayed request: $flush", (flushJson["sent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) >= 1)

        val snapshot = synchronized(relayed) { relayed.toList() }
        val kinds = snapshot.map { it.kind }
        assertTrue("ip lookup relayed: $kinds", kinds.contains("ip"))
        assertTrue("events relayed: $kinds", kinds.contains("events"))
        assertTrue("sender host is the page's origin", snapshot.all { it.host == "shop.example.com" })
        val bodies = snapshot.filter { it.kind == "events" }.map { it.body }
        val all = bodies.joinToString()
        assertTrue("events body is an {events:[...]} document", bodies.all { it.contains("\"events\":[") })
        assertTrue("libVersion reported as 1.0.22-ext", all.contains("1.0.22-ext"))
        assertTrue("click on text button relayed with eV", all.contains("Buy now"))
        assertTrue("icon-only button falls back to aria-label", all.contains("add 1 Bananas"))
        assertTrue("ipAddress from the relayed lookup", all.contains("203.0.113.9"))
        val posts = synchronized(bridge) { bridge.toList() }
        assertTrue("bridge: initialized + session + target reported: $posts", listOf("initialized", "session", "target").all { it in posts })

        instrumentation.runOnMainSync { webView.destroy() }
    }
}
