package one.moveo.studywrapper

/// HUAWEI flavor: the only store-specific facts in the binary (§h1). Must
/// not reference Google Play — AppGallery review rejects apps that direct
/// users there, and on GMS-less devices the engine is not the Play-updated
/// `com.google.android.webview` package anyway; it updates via system
/// updates or AppGallery. No deep link is reliable across EMUI versions,
/// so the gate gives guidance without a button.
object StoreSupport {
    const val store = "huawei"

    const val webViewUpdateTitle = "Update your web engine"
    const val webViewUpdateNotice =
        "This device's web engine is too old for the study browser. " +
            "Please install available system updates (or update your web " +
            "engine from AppGallery) and try again."
    val webViewUpdateActionLabel: String? = null
    val webViewUpdateActionUrl: String? = null
}
