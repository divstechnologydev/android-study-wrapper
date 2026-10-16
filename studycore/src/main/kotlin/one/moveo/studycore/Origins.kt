package one.moveo.studycore

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Origin matching — the single source of truth mandated by the config schema
/// and phase-a0 §a0.3: the injected JS guard, the navigation policy, the
/// consent-screen wording, AND the `allowedOriginRules` derivation must ALL
/// derive from this one place so they can never disagree (mid-journey
/// subdomain hops like `login.` / `checkout.` are the case this protects).
/// Port of the extension's `src/origins.js` (via iOS `Origins.swift`);
/// `originsToMatchPatterns` is Chrome-specific — its Android analogue is
/// `allowedOriginRules` below.
object Origins {
    /// Schema §5 match rule: hostname `H` matches origin `O` iff `H == O` or
    /// `H` ends with `"." + O`. So `account.sainsburys.co.uk` matches
    /// `sainsburys.co.uk`; `notsainsburys.co.uk` does not.
    fun hostnameMatches(hostname: String, origins: List<String>): Boolean {
        val h = hostname.lowercase()
        return origins.any { h == it || h.endsWith(".$it") }
    }

    /// §a0.3: the `allowedOriginRules` passed to
    /// `addDocumentStartJavaScript` / `addWebMessageListener`. Each origin `O`
    /// becomes `https://O` AND `https://*.O` — the wildcard rule does not
    /// cover the bare domain, so both are required to equal the §5 match rule
    /// above.
    fun allowedOriginRules(origins: List<String>): List<String> =
        origins.flatMap { origin ->
            val o = origin.lowercase()
            listOf("https://$o", "https://*.$o")
        }

    /// Schema §5 origin shape: bare lowercase hostname, ≥2 labels — the
    /// extension's ORIGIN_RE. Lives here rather than in the validator because
    /// the demo's site picker must accept exactly what a config may declare.
    fun isValidOrigin(s: String): Boolean = ORIGIN_REGEX.matches(s)

    private val ORIGIN_REGEX = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
}

/// Turns what a person types into a site box ("example.com",
/// "example.com/pricing", "https://www.example.com/shop?x=1") into the two
/// config fields that describe it: a bare origin and an optional starting
/// path.
///
/// Only used by the demo, where the participant picks the site themselves.
/// A real study's origins come from the backend and are validated there.
/// Port of iOS `SiteInput` (Origins.swift).
object SiteInput {
    data class Parsed(
        /// Bare lowercase hostname, ready for `tracking.origins`.
        val origin: String,
        /// `flow.prepositionPath`, or null for the site root. Query and
        /// fragment are preserved — plenty of sites need them to land on the
        /// right page.
        val path: String?,
    )

    /// null when there is no usable hostname in the input.
    fun parse(raw: String): Parsed? {
        var text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        // Whitespace inside is never a site; OkHttp would strip it and
        // silently parse something the participant did not type.
        if (text.any { it.isWhitespace() }) return null

        // HttpUrl needs a scheme to see a host rather than a path. Anything
        // that is not http/https (ftp:, javascript:) fails to parse here.
        if (!text.contains("://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: return null

        var host = url.host
        if (host.isEmpty()) return null
        // "www." is dropped so the study also covers the bare domain: origins
        // match subdomains downward, never upward, and a participant typing
        // "www.shop.com" means the site, not that one hostname.
        if (host.startsWith("www.") && host.length > 4) {
            val stripped = host.removePrefix("www.")
            if (isValidHost(stripped)) host = stripped
        }
        if (!isValidHost(host)) return null

        var path = url.encodedPath
        url.encodedQuery?.takeIf { it.isNotEmpty() }?.let { path += "?$it" }
        url.encodedFragment?.takeIf { it.isNotEmpty() }?.let { path += "#$it" }

        return Parsed(
            origin = host,
            path = if (path.isEmpty() || path == "/") null else path,
        )
    }

    private fun isValidHost(host: String): Boolean =
        // A trailing dot is a legal FQDN but not a legal config origin.
        Origins.isValidOrigin(host.removeSuffix("."))
}
