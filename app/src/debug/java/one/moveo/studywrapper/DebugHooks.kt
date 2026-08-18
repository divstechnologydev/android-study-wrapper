package one.moveo.studywrapper

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import one.moveo.studywrapper.ui.Brand

/// DEBUG variant of the QA hooks (§a2.7). The release source set replaces
/// this object with no-ops, so none of it exists in the release binary —
/// the Android analogue of the iOS `#if DEBUG` invariant.
object DebugHooks {
    const val isDebugBuild = true

    /// Scripted QA via intent extras (replaces the iOS MOVEO_* env vars):
    ///   adb shell am start -n one.moveo.studywrapper/.MainActivity \
    ///     -e MOVEO_API_BASE http://10.0.2.2:8787/api/v1/extension-config \
    ///     -e MOVEO_AUTO_CODE TESTCODE1234 -e MOVEO_AUTO_FLOW consent \
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
        intent.getStringExtra("MOVEO_AUTO_CODE")?.takeIf { it.isNotEmpty() }?.let { code ->
            model.codeInput.value = code
            model.activate()
        }
    }

    /// DEBUG ingest redirect (§a2.7): rewrites the tag's fetch URLs so dev
    /// studies' events reach the dev ingestion host. Where events should be
    /// rerouted: an explicit override wins; otherwise a config that declares
    /// a different endpoint than the tag's baked one (dev studies) is
    /// honored automatically. Null = no redirect (prod behavior). The
    /// release variant of this object always returns null AND the template
    /// asset only exists in the debug source set.
    fun ingestRedirectScript(model: AppViewModel): String? {
        val target = model.ingestOverride?.takeIf { it.isNotEmpty() }
            ?: model.store.activeStudy?.config?.tracking?.apiUrl
                ?.takeIf { it != one.moveo.studycore.generated.TagEndpoint.apiUrl }
            ?: return null
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

    /// Gear icon in the bottom-left (kept clear of the home header's study
    /// menu AND the consent screen's Accept button) opening the debug
    /// settings screen — the iOS nav-bar toolbar item's analogue.
    @Composable
    fun DebugGearOverlay(model: AppViewModel) {
        var showSettings by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp, start = 8.dp),
            ) {
                Text("⚙", fontSize = 20.sp, color = Brand.textMuted)
            }
        }
        if (showSettings) {
            Dialog(
                onDismissRequest = { showSettings = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                DebugSettingsScreen(model = model, onDone = { showSettings = false })
            }
        }
    }
}
