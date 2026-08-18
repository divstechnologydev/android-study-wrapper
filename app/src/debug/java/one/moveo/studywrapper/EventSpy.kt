package one.moveo.studywrapper

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/// DEBUG-only parity capture (phase-a3 §a3.1 / M5 — ← iOS
/// AppModel+EventSpy.swift): the event spy mirrors every tag POST into
/// files/moveo-events.jsonl, exported from the debug settings screen and
/// diffed against a desktop capture by the iOS repo's Scripts/diff-events.mjs.
///
/// NOTE: capture lines contain whatever the tag sends — Authorization is not
/// captured, but bodies are study data. QA artifact only; the whole file
/// lives in the debug source set and does not exist in release builds.
object EventSpy {
    private const val PREFS = "debug_settings"
    private const val KEY_ENABLED = "eventSpyEnabled"
    private const val LOG_TAG = "moveo-events"

    /// Transient scripted-QA switch (MOVEO_EVENT_SPY=1 intent extra — the
    /// iOS MOVEO_EVENT_SPY env var analogue); not persisted across launches.
    var extraEnabled = false

    var enabled: Boolean
        get() = extraEnabled || prefs()?.getBoolean(KEY_ENABLED, false) == true
        set(value) {
            prefs()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    private fun prefs() =
        DebugHooks.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _capturedPostCount = MutableStateFlow(0)
    val capturedPostCount: StateFlow<Int> = _capturedPostCount

    val captureFile: File?
        get() = DebugHooks.appContext?.let { File(it.filesDir, "moveo-events.jsonl") }

    /// The spy user script, appended AFTER the production injection so the
    /// spy's fetch wrapper is outermost — it sees the URL the tag asked for,
    /// before the DEBUG ingest redirect rewrites it.
    fun script(): String? {
        if (!enabled) return null
        return try {
            DebugHooks.appContext?.assets?.open("moveo-event-spy.android.js")
                ?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /// Second, separate bridge listener ("moveoDebug", same origin rules as
    /// the production bridge) so the production injection stays untouched.
    /// Bound at WebView creation — like iOS, the toggle applies the next
    /// time the browser is opened.
    fun installBridge(webView: WebView, rules: Set<String>) {
        if (!enabled) return
        WebViewCompat.addWebMessageListener(webView, "moveoDebug", rules) { _, message, _, _, _ ->
            message.data?.let { handleMessage(it) }
        }
    }

    /// One JSONL line per tag POST: capture metadata + the parsed body (kept
    /// as parsed JSON so the diff tool and humans can read it).
    private fun handleMessage(raw: String) {
        val msg = try {
            Json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return
        }
        if ((msg["type"] as? JsonPrimitive)?.content != "event-post") return
        val rawBody = (msg["body"] as? JsonPrimitive)?.content ?: return
        val parsedBody = try {
            Json.parseToJsonElement(rawBody)
        } catch (_: Exception) {
            JsonPrimitive(rawBody)
        }

        // Live console feed: one compact line per event
        // (`adb logcat -s moveo-events`).
        val events = (parsedBody as? JsonObject)?.get("events") as? JsonArray ?: JsonArray(emptyList())
        for (element in events) {
            val event = element as? JsonObject ?: continue
            val kind = (event["eventType"] as? JsonPrimitive)?.content
                ?: (event["type"] as? JsonPrimitive)?.content
                ?: "?"
            val prop = event["prop"] as? JsonObject ?: JsonObject(emptyMap())
            val props = listOf("eA", "eT", "eID", "eV", "sg", "sc").mapNotNull { key ->
                prop[key]?.let { value ->
                    val text = ((value as? JsonPrimitive)?.content ?: value.toString())
                        .replace(Regex("\\s+"), " ").take(40)
                    "$key=$text"
                }
            }.joinToString(" ")
            Log.i(LOG_TAG, "⇢ $kind $props")
        }

        val line = buildJsonObject {
            put("body", parsedBody)
            put("capturedAt", Instant.now().toString())
            put("host", (msg["host"] as? JsonPrimitive)?.content ?: "")
            put("url", (msg["url"] as? JsonPrimitive)?.content ?: "")
        }
        val file = captureFile ?: return
        try {
            file.appendText(line.toString() + "\n")
            _capturedPostCount.value += 1
        } catch (_: Exception) {
        }
    }

    fun loadCapturedPostCount() {
        val file = captureFile
        _capturedPostCount.value =
            if (file != null && file.exists()) file.readLines().count { it.isNotBlank() } else 0
    }

    fun clearCapture() {
        captureFile?.delete()
        _capturedPostCount.value = 0
    }

    /// Share-sheet export (← iOS ShareLink). The FileProvider is declared in
    /// the debug manifest overlay only.
    fun exportCapture(context: Context) {
        val file = captureFile?.takeIf { it.exists() } ?: return
        val uri = FileProvider.getUriForFile(context, context.packageName + ".debugfiles", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export capture (JSONL)"))
    }
}
