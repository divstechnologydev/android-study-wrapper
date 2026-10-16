package one.moveo.studycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports of run-tests.mjs §4 (hostname match rule) and §5 (code helpers), via
/// iOS `OriginsAndCodesTests.swift`.
class OriginsTests {
    @Test
    fun matchRule() {
        assertTrue("exact hostname", Origins.hostnameMatches("sainsburys.co.uk", listOf("sainsburys.co.uk")))
        assertTrue("subdomain", Origins.hostnameMatches("www.sainsburys.co.uk", listOf("sainsburys.co.uk")))
        assertFalse("suffix lookalike", Origins.hostnameMatches("notsainsburys.co.uk", listOf("sainsburys.co.uk")))
        assertFalse("unrelated host", Origins.hostnameMatches("example.com", listOf("sainsburys.co.uk")))
        assertTrue("case-insensitive", Origins.hostnameMatches("WWW.Sainsburys.CO.UK", listOf("sainsburys.co.uk")))
        assertTrue("any origin in list", Origins.hostnameMatches("account.sainsburys.co.uk", listOf("other.com", "sainsburys.co.uk")))
        assertFalse("empty origins", Origins.hostnameMatches("sainsburys.co.uk", emptyList()))
    }

    /// §a0.3: bare + wildcard rule per origin — the wildcard alone would NOT
    /// cover the bare domain, so both are required to equal the §5 match rule.
    @Test
    fun allowedOriginRulesDerivation() {
        assertEquals(
            listOf("https://sainsburys.co.uk", "https://*.sainsburys.co.uk"),
            Origins.allowedOriginRules(listOf("sainsburys.co.uk")),
        )
        assertEquals(
            listOf("https://a.com", "https://*.a.com", "https://b.dev", "https://*.b.dev"),
            Origins.allowedOriginRules(listOf("a.com", "b.dev")),
        )
        assertEquals(emptyList<String>(), Origins.allowedOriginRules(emptyList()))
    }
}

class CodesTests {
    @Test
    fun normalize() {
        assertEquals("grouped", "7TQ2M4K9XW3FZ", Codes.normalize("7tq2-m4k9-xw3fz"))
        assertEquals("spaces", "7TQ2M4K9XW3FZ", Codes.normalize(" 7TQ2 M4K9 XW3FZ "))
        assertEquals("plain", "7TQ2M4K9XW3FZ", Codes.normalize("7TQ2M4K9XW3FZ"))
        assertNull("junk charset", Codes.normalize("abc\$%^"))
        assertEquals("4-char code", "4831", Codes.normalize("48-31"))
        assertNull("too short", Codes.normalize("AB1"))
        assertNull("null", Codes.normalize(null))
        assertNull("too long", Codes.normalize("A".repeat(33)))
        assertEquals("max length", "A".repeat(32), Codes.normalize("A".repeat(32)))
    }

    @Test
    fun group() {
        assertEquals("13 chars → 4-4-5", "7TQ2-M4K9-XW3FZ", Codes.group("7TQ2M4K9XW3FZ"))
        assertEquals("4831", Codes.group("4831"))
        assertEquals("TEST-CODE-1234", Codes.group("TESTCODE1234"))
        assertEquals("6 chars: trailing 2-char group merges into the previous one", "ABCDEF", Codes.group("ABCDEF"))
    }
}

/// The demo's site picker — what a participant types becomes `origins[0]`
/// plus `flow.prepositionPath`, so anything this accepts must also pass the
/// config validator's origin rule.
class SiteInputTests {
    @Test
    fun bareHostname() {
        assertEquals(SiteInput.Parsed("example.com", null), SiteInput.parse("example.com"))
        assertEquals(SiteInput.Parsed("example.com", null), SiteInput.parse("  Example.COM  "))
        assertEquals(SiteInput.Parsed("sainsburys.co.uk", null), SiteInput.parse("sainsburys.co.uk"))
    }

    @Test
    fun schemeAndPath() {
        assertEquals(SiteInput.Parsed("example.com", null), SiteInput.parse("https://example.com"))
        assertEquals(SiteInput.Parsed("example.com", null), SiteInput.parse("http://example.com/"))
        assertEquals(SiteInput.Parsed("example.com", "/pricing"), SiteInput.parse("example.com/pricing"))
        assertEquals(
            SiteInput.Parsed("shop.example.com", "/c/shoes?sort=new#top"),
            SiteInput.parse("https://shop.example.com/c/shoes?sort=new#top"),
        )
    }

    @Test
    fun wwwIsDropped() {
        // Origins match subdomains downward, so the bare domain covers www —
        // the reverse is not true.
        assertEquals(SiteInput.Parsed("example.com", null), SiteInput.parse("www.example.com"))
        assertEquals(SiteInput.Parsed("example.com", "/x"), SiteInput.parse("https://www.example.com/x"))
        assertEquals(SiteInput.Parsed("www.com", null), SiteInput.parse("www.com"))
    }

    @Test
    fun rejected() {
        assertNull(SiteInput.parse(""))
        assertNull(SiteInput.parse("   "))
        assertNull(SiteInput.parse("localhost"))
        assertNull(SiteInput.parse("not a url"))
        assertNull(SiteInput.parse("ftp://example.com"))
        assertNull(SiteInput.parse("javascript:alert(1)"))
        assertNull(SiteInput.parse("https://"))
        assertNull(SiteInput.parse("exa_mple.com"))
    }

    @Test
    fun everythingAcceptedIsAValidConfigOrigin() {
        for (input in listOf("example.com", "https://www.example.com/pricing", "shop.example.co.uk/x?y=1")) {
            val parsed = SiteInput.parse(input)
            assertNotNull(input, parsed)
            assertTrue("$input -> ${parsed!!.origin}", Origins.isValidOrigin(parsed.origin))
        }
    }
}
