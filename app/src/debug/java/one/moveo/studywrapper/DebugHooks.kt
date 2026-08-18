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
    /// Scripted QA via intent extras (replaces the iOS MOVEO_* env vars):
    ///   adb shell am start -n one.moveo.studywrapper/.MainActivity \
    ///     -e MOVEO_API_BASE http://10.0.2.2:8787/api/v1/extension-config \
    ///     -e MOVEO_AUTO_CODE TESTCODE1234 -e MOVEO_AUTO_FLOW consent
    fun applyLaunchExtras(intent: Intent?, model: AppViewModel) {
        intent ?: return
        intent.getStringExtra("MOVEO_API_BASE")?.takeIf { it.isNotEmpty() }?.let {
            model.store.apiBaseOverride = it
        }
        intent.getStringExtra("MOVEO_AUTO_FLOW")?.takeIf { it.isNotEmpty() }?.let {
            model.qaAutoFlow = it
        }
        intent.getStringExtra("MOVEO_AUTO_CODE")?.takeIf { it.isNotEmpty() }?.let { code ->
            model.codeInput.value = code
            model.activate()
        }
    }

    /// Gear icon in the top-right (the iOS nav-bar toolbar item) opening the
    /// debug settings screen.
    @Composable
    fun DebugGearOverlay(model: AppViewModel) {
        var showSettings by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp),
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
