package one.moveo.studycore

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/// Extracts a study code (and the optional panel-provider transaction id)
/// from the app's two entry-point URL shapes:
///
///   - App Link: `https://app.moveo.one/extension/config/<code>[?transaction_id=…]`
///     (host is NOT checked here — the intent filter limits which hosts open
///     the app, and a link-delivered code is untrusted either way: it gets
///     the exact same fetch → validate → confirm path as a typed one)
///   - Custom scheme:  `moveoone://config/<code>[?transaction_id=…]` (QA, the
///     landing page's "Open in app" button, link-wrapping mail clients)
///
/// Counterpart of the extension's `content/setup-link.js` (via iOS
/// `SetupLink.swift`).
object SetupLink {
    /// Query parameter carrying the panel-provider transaction id — the same
    /// name on the way in (setup link) and on the way out (lead-out URL).
    const val TRANSACTION_ID_PARAM = "transaction_id"

    data class Parsed(
        val code: String,
        /// null when the link carries no transaction id OR an invalid one —
        /// an invalid value degrades to "no transaction id" rather than
        /// blocking activation (setup-link.js rule).
        val transactionId: String?,
    )

    fun parse(url: String): Parsed? {
        val code = code(from = url) ?: return null
        return Parsed(code = code, transactionId = transactionId(url))
    }

    fun code(from: String): String? {
        // The query and fragment are stripped BEFORE URI parsing: the query
        // is untrusted input read separately below, and a query java.net.URI
        // rejects (literal spaces, `|`, `{`…) must never swallow an otherwise
        // valid code — setup-link.js reads location.pathname and
        // location.search independently the same way.
        val base = from.substringBefore('#').substringBefore('?')
        val uri = try {
            URI(base)
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

    /// First `transaction_id` occurrence (URLSearchParams.get semantics —
    /// an empty first value wins over a later non-empty one and yields
    /// null), percent-decoded, validated by `TransactionId`.
    internal fun transactionId(url: String): String? {
        val beforeFragment = url.substringBefore('#')
        val q = beforeFragment.indexOf('?')
        if (q < 0) return null
        return TransactionId.normalize(queryValue(beforeFragment.substring(q + 1), TRANSACTION_ID_PARAM))
    }

    /// Install-referrer parameter carrying the study code (deferred setup,
    /// docs/plan.md §a1.5). The landing page puts
    /// `moveo_code=<CODE>[&transaction_id=<ID>]` in the Play Store link's
    /// `referrer`.
    const val REFERRER_CODE_PARAM = "moveo_code"

    /// Rebuilds the setup link a store install dropped: the install referrer
    /// read as a query string → `moveoone://config/<CODE>[?transaction_id=<ID>]`,
    /// or null when it carries no `moveo_code` (organic installs report
    /// `utm_source=google-play&utm_medium=organic`). Presence only — the
    /// values are untrusted and get validated by `parse` when the link is
    /// handled, like any tapped link. An undecodable transaction id is
    /// dropped rather than losing the code.
    fun linkFromInstallReferrer(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        linkFromReferrerQuery(referrer)?.let { return it }
        // A store that hands the value over still percent-encoded has no
        // literal `=` at all — decode once and read it again.
        if ('=' in referrer) return null
        return decode(referrer)?.let { linkFromReferrerQuery(it) }
    }

    private fun linkFromReferrerQuery(query: String): String? {
        val code = queryValue(query, REFERRER_CODE_PARAM)?.takeIf { it.isNotBlank() } ?: return null
        val transactionId = queryValue(query, TRANSACTION_ID_PARAM)?.takeIf { it.isNotEmpty() }
        return buildString {
            append("moveoone://config/").append(encode(code))
            if (transactionId != null) {
                append('?').append(TRANSACTION_ID_PARAM).append('=').append(encode(transactionId))
            }
        }
    }

    /// First occurrence of `name` in a query string, percent-decoded; null
    /// when absent or undecodable.
    private fun queryValue(query: String, name: String): String? {
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            if (decode(key) != name) continue
            return decode(if (eq < 0) "" else pair.substring(eq + 1))
        }
        return null
    }

    private fun encode(component: String): String =
        URLEncoder.encode(component, "UTF-8").replace("+", "%20")

    private fun decode(component: String): String? = try {
        URLDecoder.decode(component, "UTF-8")
    } catch (_: Exception) {
        null // malformed percent escape → treated as absent/invalid
    }
}

/// Panel-provider transaction id validation — port of the setup-link.js
/// check `^[A-Za-z0-9_-]{1,256}$`. Untrusted URL input: anything outside
/// that charset/length is dropped (null), never sent anywhere.
object TransactionId {
    const val MAX_LENGTH = 256

    fun normalize(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.length > MAX_LENGTH) return null
        val valid = raw.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
        }
        return if (valid) raw else null
    }
}
