package one.moveo.studycore

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
}
