package one.moveo.studycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/// Port of the extension's target-matching case table (run-tests.mjs §6, via
/// iOS `TargetMatchTests.swift`). The same table runs there against both the
/// JS module and the copy inlined in the bootstrap; keeping this Kotlin port
/// verdict-identical is what makes native url_match and page-side event_match
/// agree across platforms.
class TargetMatchTests {
    private val url = "https://shop.example.com/checkout/a1b2/success?ref=x"

    private val click: Map<String, Any?> = mapOf(
        "sg" to "checkout", "eID" to "btn-4f2a-add-to-cart-9c", "eA" to "click",
        "eT" to "button", "eV" to "Buy now", "sc" to "/product/123",
    )
    private val bareClick: Map<String, Any?> = mapOf("eA" to "click")
    private val emptyProp: Map<String, Any?> = emptyMap()

    @Test
    fun urlCases() {
        val cases = listOf(
            Case4("substring mid-path", url, "checkout", true),
            Case4("substring case-insensitive pattern", url, "/CheckOut", true),
            Case4("substring vs uppercase URL", "HTTPS://SHOP.EXAMPLE.COM/SALE", "example.com/sale", true),
            Case4("substring no match", url, "/basket", false),
            Case4("dot is literal (a.b vs axb)", "https://x.example/axb", "a.b", false),
            Case4("dot is literal (a.b vs a.b)", "https://x.example/a.b", "a.b", true),
            Case4("question mark literal", "https://x.example/price?x=1", "price?x", true),
            Case4("question mark literal no match", "https://x.example/pricex", "price?x", false),
            Case4("plus literal", "https://x.example/c++", "c++", true),
            Case4("bracket/paren literal compile", "https://x.example/a(b)[c]", "a(b)[c]", true),
            Case4("$& replacement-string safety", "https://x.example/lit\$&end", "\$&", true),
            Case4("unanchored mid-query", url, "ref=x", true),
            Case4("wildcard spans slash (spec example)", url, "/checkout/*/success", true),
            Case4("wildcard spans multiple segments", "https://s.e/checkout/a/b/c/success", "/checkout/*/success", true),
            Case4("wildcard no match", "https://s.e/checkout/abc", "/checkout/*/success", false),
            Case4("multiple wildcards", "https://s.e/c/1/s/2/end", "/c/*/s/*/end", true),
            Case4("wildcard across host+path", url, "shop.example.*/checkout", true),
            Case4("leading/trailing * ≡ bare substring", url, "*success*", true),
            Case4("pure wildcard matches everything", url, "*", true),
        )
        for ((label, testUrl, pattern, expected) in cases) {
            assertEquals("url: $label", expected, TargetMatch.matchesUrl(testUrl, pattern))
        }
    }

    /// No-wildcard patterns must equal the original substring semantics exactly.
    @Test
    fun substringEquivalence() {
        val cases = listOf(
            url to "checkout", url to "/CheckOut", url to "basket", url to "ref=x",
            "https://x.example/a.b" to "a.b", "https://x.example/price?x=1" to "price?x",
            "https://x.example/c++" to "c++", url to "SHOP.EXAMPLE",
        )
        for ((testUrl, pattern) in cases) {
            assertEquals(
                "substring-equivalence \"$pattern\"",
                testUrl.lowercase().contains(pattern.lowercase()),
                TargetMatch.matchesUrl(testUrl, pattern),
            )
        }
    }

    @Test
    fun eventCases() {
        val cases = listOf(
            EventCase("single key match", click, mapOf("eA" to "click"), true),
            EventCase("single key mismatch", click, mapOf("eA" to "hover"), false),
            EventCase("AND across keys — all match", click, mapOf("eA" to "click", "eT" to "button"), true),
            EventCase("AND across keys — one mismatch", click, mapOf("eA" to "click", "eT" to "link"), false),
            EventCase("non-wildcard key, prop absent → false", bareClick, mapOf("eA" to "click", "eT" to "button"), false),
            EventCase("omitted ≡ * (constrained side)", click, mapOf("eA" to "click", "sc" to "*", "eID" to "*"), true),
            EventCase("* matches even when prop absent", bareClick, mapOf("eA" to "click", "eID" to "*", "sc" to "*"), true),
            EventCase("multi-star still pure wildcard", bareClick, mapOf("eA" to "click", "eV" to "**"), true),
            EventCase("five-star pure wildcard", bareClick, mapOf("eA" to "click", "sc" to "*****"), true),
            EventCase("unknown key fail-closed", click, mapOf("eA" to "click", "bogus" to "x"), false),
            EventCase("unknown key fail-closed even with *", click, mapOf("eA" to "click", "bogus" to "*"), false),
            EventCase("empty prop bag, constrained spec", emptyProp, mapOf("eA" to "page_view"), false),
            EventCase("empty prop bag, wildcard-only spec", emptyProp, mapOf("sc" to "*"), true),
            EventCase("value wildcard on sc", click, mapOf("sc" to "/product/*"), true),
            EventCase("value substring case-insensitive", click, mapOf("eV" to "buy"), true),
            EventCase("value wildcard inside", mapOf("eV" to "Buy it now"), mapOf("eV" to "Buy*now"), true),
            EventCase("eID wildcard contains", click, mapOf("eID" to "*add-to-cart*"), true),
            EventCase("nil props never throws", null, mapOf("eA" to "click"), false),
            EventCase("non-string prop value → false", mapOf("eV" to 42), mapOf("eV" to "4"), false),
            EventCase("extra unknown props on EVENT ignored", click + mapOf("zz" to "y"), mapOf("eA" to "click"), true),
            EventCase(
                "synthetic page_view shape",
                mapOf("sg" to "global", "eA" to "page_view", "eT" to "page", "eV" to "/checkout/a", "sc" to "/checkout/a"),
                mapOf("eA" to "page_view", "sc" to "/checkout/*"), true,
            ),
        )
        for ((label, props, spec, expected) in cases) {
            assertEquals("event: $label", expected, TargetMatch.matchesEvent(props, spec))
        }
    }

    /// Omitted ≡ "*" — verdict-identical over an event set that includes
    /// events LACKING the wildcarded props entirely.
    @Test
    fun omittedEquivalentToWildcard() {
        val events = listOf(click, bareClick, emptyProp, mapOf("eA" to "hover"))
        for (ev in events) {
            assertEquals(
                "omitted≡* parity on $ev",
                TargetMatch.matchesEvent(ev, mapOf("eA" to "click")),
                TargetMatch.matchesEvent(ev, mapOf(
                    "eA" to "click", "sc" to "*", "eID" to "*", "eV" to "*", "eT" to "*", "sg" to "*",
                )),
            )
        }
    }

    @Test
    fun nonStringSpecValueFailsClosed() {
        assertFalse(TargetMatch.matchesEvent(click, mapOf("eA" to 42)))
        assertFalse(TargetMatch.matchesEvent(click, mapOf("eA" to "")))
        assertFalse(TargetMatch.matchesEvent(click, null))
    }

    private data class Case4(val label: String, val url: String, val pattern: String, val expected: Boolean)
    private data class EventCase(
        val label: String,
        val props: Map<String, Any?>?,
        val spec: Map<String, Any?>,
        val expected: Boolean,
    )
}
