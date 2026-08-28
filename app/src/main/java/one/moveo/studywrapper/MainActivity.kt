package one.moveo.studywrapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import one.moveo.studywrapper.ui.Brand
import one.moveo.studywrapper.ui.RootScreen

/// Single activity (docs/plan.md §a0.6), deep-link entry. Setup links —
/// App Link `https://app.moveo.one/extension/config/<code>` and the
/// `moveoone://config/<code>` scheme (§a1, manifest intent filters) — arrive
/// as the launch intent or, with `singleTask`, via `onNewIntent`; either way
/// they take the exact fetch → validate → confirm path of a typed code
/// (`AppViewModel.handleOpenUrl`, incl. the `?transaction_id=` hand-off).
class MainActivity : ComponentActivity() {

    private val model: AppViewModel get() = (application as App).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is enforced from target API 35/36; the theme is
        // light-only, so both bars get dark icons over a transparent scrim
        // and the content keeps clear of them via safeDrawing below
        // (docs/a4-play-release.md R1).
        val scrim = Brand.bg.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(scrim, scrim),
            navigationBarStyle = SystemBarStyle.light(scrim, scrim),
        )
        // A recreated activity (process death, configuration change) gets
        // the original launch intent again — the model already consumed it,
        // so only a fresh instance handles it.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            // Brand kit v3 is light-only (near-black on warm neutrals) — same
            // as the extension pages and the platform; pinning the scheme
            // keeps the clients reading as one product.
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Brand.text,
                    background = Brand.bg,
                    surface = Brand.bg,
                    onPrimary = Brand.bgElevated,
                    onBackground = Brand.text,
                    onSurface = Brand.text,
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Brand.bg) {
                    // Status bar, gesture nav bar, display cutouts and the
                    // IME are kept out of every screen's layout.
                    RootScreen(
                        model,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() current so a later recreation sees the newest
        // intent (and skips it via the savedInstanceState rule above).
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Returning from a lead survey Custom Tab (or the system browser on
        // GMS-less devices) is the Android "lead sheet dismissed" signal (the
        // iOS leadSheetDismissed, §a2.6/§a2.8): apply a completion that
        // waited for the closing page, else retry a queued lead-out.
        model.activityResumed()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Relaunch from Recents re-delivers the ORIGINAL intent — the setup
        // link (or QA extras) that opened the app. Consuming it again would
        // re-run an activation the participant may have cancelled.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // Debug-only QA hooks (intent extras); the release source set makes
        // this a no-op (§a2.7).
        DebugHooks.applyLaunchExtras(intent, model)
        // App Links / custom scheme (a1): same fetch → validate → confirm
        // path as a typed code.
        intent.dataString?.let { model.handleOpenUrl(it) }
    }
}
