package one.moveo.studywrapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import one.moveo.studywrapper.ui.Brand
import one.moveo.studywrapper.ui.RootScreen

/// Single activity (docs/plan.md §a0.6), deep-link entry. App Link +
/// moveoone:// intent filters land in phase a1; the handling below already
/// treats any arriving link exactly like a typed code.
class MainActivity : ComponentActivity() {

    private val model: AppViewModel get() = (application as App).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
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
                    RootScreen(model)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Debug-only QA hooks (intent extras); the release source set makes
        // this a no-op (§a2.7).
        DebugHooks.applyLaunchExtras(intent, model)
        // App Links / custom scheme (a1): same fetch → validate → confirm
        // path as a typed code.
        intent?.dataString?.let { model.handleOpenUrl(it) }
    }
}
