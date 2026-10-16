package one.moveo.studycore

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Where the participant is sent after enrolling (the "start here" page):
/// the first tracked origin, at `flow.prepositionPath` when the study
/// declares one, else the origin root — the pre-existing behavior and still
/// the default. Port of the extension's `src/start-url.js` (commit
/// "preposition fix", via iOS `StartURL.swift`), so all clients open the
/// same page for the same config.
object StartUrl {
    /// Same shape the validator admits for an origin (§6: bare hostname only).
    val ORIGIN_HOST_REGEX = Regex("[a-z0-9.-]+")

    /// Same shapes the validator rejects for a path (§4): a scheme, a
    /// protocol-relative "//host", or a leading "\" (URL parsers read it as "/").
    val NOT_A_PATH_REGEX = Regex("^([a-z][a-z0-9+.-]*:|//|\\\\)", RegexOption.IGNORE_CASE)

    /// Absolute https URL on the FIRST origin, or null when there is no usable
    /// origin. Whatever the stored path, the result can only ever be https on
    /// that host: a URL-shaped value (rejected by the validator; a stored
    /// config could predate that check), an unparsable path, or anything that
    /// resolves off the origin falls back to the root rather than a nonsense
    /// or off-site page.
    fun build(origins: List<String>, prepositionPath: String?): String? {
        val host = origins.firstOrNull()?.takeIf { ORIGIN_HOST_REGEX.matches(it) } ?: return null
        val root = "https://$host/"
        val rootUrl = root.toHttpUrlOrNull() ?: return null

        var path = prepositionPath?.trimJs().orEmpty()
        if (path.isEmpty()) return root // absent / empty ⇒ root, exactly as before
        if (NOT_A_PATH_REGEX.containsMatchIn(path)) return root
        // Authors forget the leading "/" — tolerate it.
        if (!path.startsWith("/")) path = "/$path"

        // `new URL(path, root)`: OkHttp resolves like a browser — keeps query +
        // fragment, resolves "./" and "../" without leaving the origin,
        // percent-encodes what JS would (spaces). Unparsable ⇒ root.
        val resolved = rootUrl.resolve(path) ?: return root
        if (resolved.scheme != "https" || resolved.host != host) return root
        return resolved.toString()
    }
}

/// JS `String.prototype.trim()`: Unicode whitespace plus the BOM, which
/// Kotlin's `trim()` leaves in place. Used wherever the contract says
/// "trimmed" so the three clients collapse the same values to "absent".
internal fun String.trimJs(): String = trim { it.isWhitespace() || it == '﻿' }
