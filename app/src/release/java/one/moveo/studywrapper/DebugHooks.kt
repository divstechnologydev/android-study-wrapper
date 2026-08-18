package one.moveo.studywrapper

import android.content.Intent
import androidx.compose.runtime.Composable

/// RELEASE variant: the QA surface does not exist in the release binary
/// (§a2.7) — intent extras are ignored, no gear icon, no settings screen.
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun applyLaunchExtras(intent: Intent?, model: AppViewModel) = Unit

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun DebugGearOverlay(model: AppViewModel) = Unit
}
