package one.moveo.studywrapper.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/// Lead-in / lead-out surveys open in Chrome Custom Tabs (← iOS
/// Browser/SafariView.swift) — the SFSafariViewController analogue: in-app
/// feel, separate cookie store, so survey cookies never touch the study
/// WebView (§a2.6). Also used for tapped off-origin links (§a2.5). Fallback
/// to a plain ACTION_VIEW when no Custom Tabs provider exists. Returns
/// whether the launch succeeded — "presented" = successful launch (the OS
/// gives no render callback; plan §6).
object LeadSurveyLauncher {
    fun launch(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, uri)
            true
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
