package one.moveo.studycore

import java.util.regex.Pattern

/// Target-action matching — port of the extension's `src/target-match.js`
/// (via iOS `TargetMatch.swift`; config-schema §4.1–4.3). The leaf matchers
/// also live inline in the injected bootstrap (JS); the test suite pins both
/// to the extension's case table so semantics can't drift between the native
/// side (url_match, evaluated on every WebView URL change) and the page side
/// (event_match, evaluated on the tag's seams). Tree evaluation (`evaluate`)
/// runs ONLY natively — the page sees a flat list of unfired event leaves,
/// never the tree (extension: background vs. bridge `collectEventLeaves`).
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

    // MARK: - Composite targets (§4.3)
    //
    // A targetAction is a tree: leaves are url_match / event_match, composites
    // are and / or over `conditions`. A single leaf at the root is the
    // degenerate tree every pre-composite config is. Leaves LATCH — once
    // matched during the enrollment they stay true (ActiveStudy
    // .targetProgress) — and the target fires when the root evaluates true
    // over the latched set. Leaf ids are positional paths from the root ("t",
    // "t.0", "t.1.0"): deterministic from the config, never derived from page
    // input — the native side resolves a reported id against `findLeaf`
    // before it is ever used as a key.

    const val rootLeafId = "t"

    /// One leaf of the tree with its positional id (extension
    /// `flattenTarget` entries).
    sealed class TargetLeaf {
        abstract val id: String

        data class Url(override val id: String, val pattern: String) : TargetLeaf()
        data class Event(override val id: String, val props: Map<String, String>) : TargetLeaf()

        val isEvent: Boolean get() = this is Event
    }

    /// Depth-first, in-order list of leaves with their positional ids.
    fun flatten(node: TargetAction?, path: String = rootLeafId): List<TargetLeaf> {
        val out = mutableListOf<TargetLeaf>()
        flatten(node, path, out)
        return out
    }

    private fun flatten(node: TargetAction?, path: String, out: MutableList<TargetLeaf>) {
        when (node) {
            null -> return
            is TargetAction.UrlMatch -> out += TargetLeaf.Url(id = path, pattern = node.pattern)
            is TargetAction.EventMatch -> out += TargetLeaf.Event(id = path, props = node.props)
            is TargetAction.And -> node.conditions.forEachIndexed { i, child -> flatten(child, "$path.$i", out) }
            is TargetAction.Or -> node.conditions.forEachIndexed { i, child -> flatten(child, "$path.$i", out) }
        }
    }

    /// Evaluate the tree over the set of latched leaf ids. Fails closed: null
    /// or an EMPTY conditions list (an empty "and" would otherwise be
    /// vacuously true) ⇒ false. Unknown node types cannot be represented
    /// here — the validator nulls the whole tree (extension parity).
    fun evaluate(node: TargetAction?, fired: Set<String>, path: String = rootLeafId): Boolean = when (node) {
        null -> false
        is TargetAction.UrlMatch, is TargetAction.EventMatch -> path in fired
        is TargetAction.And ->
            node.conditions.isNotEmpty() &&
                node.conditions.withIndex().all { (i, child) -> evaluate(child, fired, "$path.$i") }
        is TargetAction.Or ->
            node.conditions.isNotEmpty() &&
                node.conditions.withIndex().any { (i, child) -> evaluate(child, fired, "$path.$i") }
    }

    /// The leaf with this id, or null — the only way an untrusted id becomes a
    /// key. A legacy report without an id maps to the root ("t"), which on a
    /// composite tree is not a leaf and is therefore dropped.
    fun findLeaf(node: TargetAction?, id: String): TargetLeaf? = flatten(node).firstOrNull { it.id == id }

    /// The event leaves still to be latched — what the page receives (the
    /// extension bridge's `collectEventLeaves` filtered by `targetProgress`).
    /// url leaves are evaluated natively and never reach the page.
    fun unfiredEventLeaves(node: TargetAction?, progress: Set<String>): List<TargetLeaf> =
        flatten(node).filter { it.isEvent && it.id !in progress }

    /// The bridge's leaf-id shape check: `^t(\.\d{1,2}){0,3}$` accepts every
    /// id the validator can produce (depth ≤ 3 ⇒ at most 3 segments, ≤ 10
    /// children ⇒ ≤ 2 digits) and nothing else. Applied to the page-reported
    /// id BEFORE `findLeaf` resolves it.
    fun isValidLeafId(id: String): Boolean = LEAF_ID_REGEX.matches(id)

    // `\z`, not `$`: Java's `$` also matches before a trailing newline, JS's
    // does not — `\z` gives the JS verdict.
    private val LEAF_ID_REGEX = Regex("""^t(\.\d{1,2}){0,3}\z""")
}
