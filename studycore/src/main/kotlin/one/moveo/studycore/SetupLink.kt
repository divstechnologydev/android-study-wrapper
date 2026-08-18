package one.moveo.studycore

import java.net.URI

/// Extracts a study code from the app's two entry-point URL shapes:
///
///   - App Link: `https://app.moveo.one/extension/config/<code>`
///     (host is NOT checked here — the intent filter limits which hosts open
///     the app, and a scheme-delivered code is untrusted either way: it gets
///     the exact same fetch → validate → confirm path as a typed one)
///   - Custom scheme:  `moveoone://config/<code>` (QA, landing-page button,
///     link-wrapping mail clients)
object SetupLink {
    fun code(from: String): String? {
        val uri = try {
            URI(from)
        } catch (_: Exception) {
            return null
        }
        val path = uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
        return when (uri.scheme?.lowercase()) {
            "moveoone" -> {
                // moveoone://config/<code> — "config" is the URL host; also
                // accept the schemeless-authority form moveoone:/config/<code>.
                if (uri.host?.lowercase() == "config" && path.size == 1) {
                    return Codes.normalize(path[0])
                }
                if (path.size == 2 && path[0].lowercase() == "config") {
                    return Codes.normalize(path[1])
                }
                null
            }
            "https", "http" -> {
                if (path.size >= 3 && path[0] == "extension" && path[1] == "config") {
                    Codes.normalize(path[2])
                } else null
            }
            else -> null
        }
    }
}
