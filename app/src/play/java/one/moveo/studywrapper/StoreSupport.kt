package one.moveo.studywrapper

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/// PLAY flavor: the only store-specific facts in the binary (§h1). The
/// huawei flavor ships different values; nothing else may branch on store.
object StoreSupport {
    const val store = "play"

    /// WebView-too-old gate (§a0.3 refuses to inject on old engines): on
    /// Play devices the engine is the updatable "Android System WebView"
    /// package, so the gate deep-links to its Play page.
    const val webViewUpdateTitle = "Update Android System WebView"
    const val webViewUpdateNotice =
        "This device's web engine is too old for the study browser. " +
            "Please update “Android System WebView” from Google Play and try again."
    val webViewUpdateActionLabel: String? = "Open Google Play"
    val webViewUpdateActionUrl: String? =
        "https://play.google.com/store/apps/details?id=com.google.android.webview"

    private const val INSTALL_REFERRER_TIMEOUT_MS = 5_000L

    /// Deferred setup link (§a1.5): the raw Play Install Referrer of this
    /// install, or null. Every failure — Play Store missing or too old,
    /// service unavailable, disconnect, timeout, exception — is null: the
    /// app then simply starts empty, as it did before. Call on the main
    /// thread (the library delivers its callbacks there).
    suspend fun fetchInstallReferrer(context: Context): String? {
        val client = try {
            InstallReferrerClient.newBuilder(context.applicationContext).build()
        } catch (_: Exception) {
            return null
        }
        return try {
            withTimeoutOrNull(INSTALL_REFERRER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) {
                            if (!cont.isActive) return
                            val referrer = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                                try {
                                    client.installReferrer.installReferrer
                                } catch (_: Exception) {
                                    null
                                }
                            } else null
                            cont.resume(referrer)
                        }

                        override fun onInstallReferrerServiceDisconnected() {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    try {
                        client.startConnection(listener)
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            try {
                client.endConnection()
            } catch (_: Exception) {
                // already disconnected
            }
        }
    }
}
