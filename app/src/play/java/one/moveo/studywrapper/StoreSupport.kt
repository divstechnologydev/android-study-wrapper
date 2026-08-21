package one.moveo.studywrapper

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
}
