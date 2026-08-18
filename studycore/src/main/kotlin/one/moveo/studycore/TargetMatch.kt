package one.moveo.studycore

import java.util.regex.Pattern

/// Target-action matching — port of the extension's `src/target-match.js`
/// (via iOS `TargetMatch.swift`). The same algorithm also lives inline in the
/// injected bootstrap (JS); the test suite pins both to the extension's case
/// table so semantics can't drift between the native side (url_match,
/// evaluated on every WebView URL change) and the page side (event_match,
/// evaluated on the tag's seams).
object TargetMatch {
    /// The closed set of matchable event properties (schema §4.2).
    val matchableKeys = listOf("eA", "eT", "eID", "eV", "sg", "sc")

    private val metas = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\')

    /// §4.1: escape every regex metacharacter, then turn the escaped `*` into
    /// `.*`. Case-insensitive and UNANCHORED — a pattern without `*` behaves
    /// exactly like a case-insensitive substring match.
    ///
    /// This intentionally reproduces the JS algorithm character for character
    /// (single left-to-right escape pass, then one `\*` → `.*` replacement)
    /// rather than using `Pattern.quote`, so quirky inputs get identical
    /// verdicts on both platforms.
    internal fun compilePattern(pattern: String): Pattern? {
        val escaped = buildString(pattern.length * 2) {
            for (ch in pattern) {
                if (ch in metas) append('\\')
                append(ch)
            }
        }
        val source = escaped.replace("\\*", ".*")
        return try {
            Pattern.compile(source, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        } catch (_: Exception) {
            null
        }
    }

    private fun test(regex: Pattern, value: String): Boolean = regex.matcher(value).find()

    /// A value of only `*`s is "unconstrained" — the prop may even be absent.
    fun isPureWildcard(value: String): Boolean = value.isNotEmpty() && value.all { it == '*' }

    /// Full-URL match for `url_match` targets.
    fun matchesUrl(url: String, pattern: String): Boolean {
        val re = compilePattern(pattern) ?: return false
        return test(re, url)
    }

    /// `event_match` (§4.2): AND across spec keys; omitted key ≡ value `"*"` ≡
    /// unconstrained (matches even when the prop is absent); a non-wildcard
    /// key requires the prop to exist as a string and match; any spec key
    /// outside `matchableKeys` ⇒ the whole spec never matches (fail closed —
    /// checked before the wildcard skip, so a typo'd key can't hide behind a
    /// `"*"` value). Never throws: events like `start_session` carry an empty
    /// prop bag and must fall through safely.
    fun matchesEvent(props: Map<String, Any?>?, spec: Map<String, Any?>?): Boolean {
        if (spec == null) return false
        for ((key, rawPattern) in spec) {
            if (key !in matchableKeys) return false // fail closed, first
            val pattern = rawPattern as? String
            if (pattern.isNullOrEmpty()) return false
            if (isPureWildcard(pattern)) continue // unconstrained — prop may be absent
            val value = props?.get(key) as? String ?: return false
            val re = compilePattern(pattern) ?: return false
            if (!test(re, value)) return false
        }
        return true
    }
}
