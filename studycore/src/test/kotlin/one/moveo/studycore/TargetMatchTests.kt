package one.moveo.studycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // MARK: - Composite targets (run-tests.mjs §6b: flatten / evaluate / findLeaf)
    //
    // The extension's "unknown node type" cases (flatten contributes nothing,
    // evaluate ⇒ false, or-with-unknown-sibling) are unrepresentable here:
    // the typed TargetAction has no unknown case because the validator nulls
    // the whole tree (ConfigValidatorTests pins that side of the contract).

    private val tUrl = TargetAction.UrlMatch(pattern = "/checkout/*/success")
    private val tBuy = TargetAction.EventMatch(props = mapOf("eA" to "click", "eV" to "Buy now"))
    private val tPay = TargetAction.EventMatch(props = mapOf("eA" to "click", "eV" to "Pay now"))
    private val tNested: TargetAction get() = TargetAction.And(listOf(tUrl, TargetAction.Or(listOf(tBuy, tPay))))
    private val tFlatAnd: TargetAction get() = TargetAction.And(listOf(tUrl, tBuy))
    private val tFlatOr: TargetAction get() = TargetAction.Or(listOf(tUrl, tBuy))

    private fun ids(node: TargetAction?): List<String> = TargetMatch.flatten(node).map { it.id }
    private fun ev(node: TargetAction?, fired: List<String>): Boolean = TargetMatch.evaluate(node, fired.toSet())

    @Test
    fun flattenPositionalIds() {
        assertEquals("leaf root has id t", listOf(TargetMatch.TargetLeaf.Url(id = "t", pattern = "/checkout/*/success")), TargetMatch.flatten(tUrl))
        assertEquals("flat and ids in order", listOf("t.0", "t.1"), ids(tFlatAnd))
        assertEquals("nested ids", listOf("t.0", "t.1.0", "t.1.1"), ids(tNested))
        assertEquals(
            "event leaf carries props",
            TargetMatch.TargetLeaf.Event(id = "t.1.0", props = mapOf("eA" to "click", "eV" to "Buy now")),
            TargetMatch.flatten(tNested)[1],
        )
        assertEquals("null → []", emptyList<String>(), ids(null))
        assertEquals("empty composite → []", emptyList<String>(), ids(TargetAction.And(emptyList())))
    }

    @Test
    fun evaluateCaseTable() {
        assertTrue("leaf root fires on t", ev(tUrl, listOf("t")))
        assertFalse("leaf root, nothing fired", ev(tUrl, emptyList()))
        assertFalse("leaf root ignores child-shaped ids", ev(tUrl, listOf("t.0")))
        assertTrue("and — all", ev(tFlatAnd, listOf("t.0", "t.1")))
        assertFalse("and — partial", ev(tFlatAnd, listOf("t.0")))
        assertFalse("and — none", ev(tFlatAnd, emptyList()))
        assertTrue("or — one", ev(tFlatOr, listOf("t.1")))
        assertFalse("or — none", ev(tFlatOr, emptyList()))
        assertTrue("nested — url + buy", ev(tNested, listOf("t.0", "t.1.0")))
        assertTrue("nested — url + pay", ev(tNested, listOf("t.0", "t.1.1")))
        assertFalse("nested — url only", ev(tNested, listOf("t.0")))
        assertFalse("nested — both clicks, no url", ev(tNested, listOf("t.1.0", "t.1.1")))
        assertTrue("extra/stale ids ignored", ev(tFlatAnd, listOf("t.0", "t.1", "t.9", "t")))
        assertFalse("empty and is NOT vacuously true", ev(TargetAction.And(emptyList()), emptyList()))
        assertFalse("empty or false", ev(TargetAction.Or(emptyList()), emptyList()))
        assertFalse("null tree → false", ev(null, listOf("t")))
        assertTrue("single-child composite is identity (or)", ev(TargetAction.Or(listOf(tUrl)), listOf("t.0")))
        assertFalse("single-child composite is identity (and)", ev(TargetAction.And(listOf(tUrl)), emptyList()))
    }

    @Test
    fun findLeaf() {
        assertEquals(
            "resolves nested id",
            TargetMatch.TargetLeaf.Event(id = "t.1.1", props = mapOf("eA" to "click", "eV" to "Pay now")),
            TargetMatch.findLeaf(tNested, "t.1.1"),
        )
        assertNull("root id on composite → null (legacy report dropped)", TargetMatch.findLeaf(tNested, "t"))
        assertEquals(
            "root id on leaf tree",
            TargetMatch.TargetLeaf.Event(id = "t", props = mapOf("eA" to "click", "eV" to "Buy now")),
            TargetMatch.findLeaf(tBuy, "t"),
        )
        for (id in listOf("__proto__", "constructor", "prototype", "", "t.", "t..0")) {
            assertNull("prototype-ish id $id → null", TargetMatch.findLeaf(tNested, id))
        }
        assertNull(TargetMatch.findLeaf(null, "t"))
    }

    /// The bridge's leaf-id shape check must accept every id the validator
    /// can produce (depth ≤ 3 ⇒ at most 3 segments, ≤ 10 children ⇒ ≤ 2
    /// digits) and reject junk (run-tests.mjs §9b).
    @Test
    fun leafIdShapeCheck() {
        for (id in listOf("t", "t.0", "t.9", "t.1.0", "t.0.9.3")) {
            assertTrue("accepts $id", TargetMatch.isValidLeafId(id))
        }
        for (id in listOf("", "x", "t.", "t..0", "t.0.0.0.0", "__proto__", "t.a", "t.100", " t.0", "t.0\n")) {
            assertFalse("rejects ${id.replace("\n", "\\n")}", TargetMatch.isValidLeafId(id))
        }
    }

    /// What the page receives: the bridge's collectEventLeaves (event leaves
    /// only, positional ids) minus the latched ones.
    @Test
    fun unfiredEventLeaves() {
        assertEquals(
            "url leaves never reach the page",
            listOf(
                TargetMatch.TargetLeaf.Event(id = "t.1.0", props = mapOf("eA" to "click", "eV" to "Buy now")),
                TargetMatch.TargetLeaf.Event(id = "t.1.1", props = mapOf("eA" to "click", "eV" to "Pay now")),
            ),
            TargetMatch.unfiredEventLeaves(tNested, emptySet()),
        )
        assertEquals(
            "latched leaves are filtered out",
            listOf("t.1.1"),
            TargetMatch.unfiredEventLeaves(tNested, setOf("t.1.0", "t.0")).map { it.id },
        )
        assertEquals("leaf url root → nothing for the page", emptyList<TargetMatch.TargetLeaf>(), TargetMatch.unfiredEventLeaves(tUrl, emptySet()))
        assertEquals("leaf event root keeps id t", listOf("t"), TargetMatch.unfiredEventLeaves(tBuy, emptySet()).map { it.id })
        assertEquals(emptyList<TargetMatch.TargetLeaf>(), TargetMatch.unfiredEventLeaves(null, emptySet()))
        // Parity with the module definition the extension's CI pins.
        val expected = TargetMatch.flatten(tNested).filter { it.isEvent && it.id != "t.1.0" }
        assertEquals(expected, TargetMatch.unfiredEventLeaves(tNested, setOf("t.1.0")))
    }

    private data class Case4(val label: String, val url: String, val pattern: String, val expected: Boolean)
    private data class EventCase(
        val label: String,
        val props: Map<String, Any?>?,
        val spec: Map<String, Any?>,
        val expected: Boolean,
    )
}
