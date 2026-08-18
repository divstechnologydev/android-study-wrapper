package one.moveo.studycore

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// M1 smoke test (← iOS FixtureVendoringTests, extended per plan §0.1): the
/// extension repo's schema fixture suite is vendored and reachable as a test
/// resource, and the on-disk vendored tag + fixture count match VENDOR.json's
/// provenance record. The validator tests iterate the fixture directory — if
/// it goes missing the suite must fail loudly, not pass vacuously.
class FixtureVendoringTests {
    @Test
    fun fixtureSuiteVendored() {
        val files = Fixtures.dir().listFiles { f -> f.extension == "json" }!!.toList()
        assertTrue("expected the full fixture suite", files.size >= 20)
        assertTrue(files.any { it.name == "valid-full.json" })
        assertTrue(files.any { it.name == "invalid-missing-token.json" })
    }

    @Test
    fun vendorProvenanceMatches() {
        val root = Fixtures.repoRoot()
        val vendorFile = File(root, "app/src/main/assets/VENDOR.json")
        assertTrue("VENDOR.json missing — run scripts/vendor-tag.sh", vendorFile.isFile)
        val vendor = Json.parseToJsonElement(vendorFile.readText()).jsonObject
        val files = vendor["files"]!!.jsonObject

        val tagFile = File(root, "app/src/main/assets/moveo-one.js")
        assertTrue("vendored tag missing — run scripts/vendor-tag.sh", tagFile.isFile)
        val sha = MessageDigest.getInstance("SHA-256").digest(tagFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "vendored tag sha256 must match VENDOR.json — never edit assets/moveo-one.js",
            files["moveo-one.js"]!!.jsonObject["sha256"]!!.jsonPrimitive.content,
            sha,
        )

        val recordedCount = files["fixtures"]!!.jsonObject["count"]!!.jsonPrimitive.int
        val actualCount = Fixtures.dir().listFiles { f -> f.extension == "json" }!!.size
        assertEquals("fixture count must match VENDOR.json", recordedCount, actualCount)
    }

    @Test
    fun generatedTagEndpointMatchesVendoredTag() {
        val root = Fixtures.repoRoot()
        val tag = File(root, "app/src/main/assets/moveo-one.js").readText()
        val match = Regex("const API_URL = \"([^\"]*)\"").find(tag)
        assertEquals(
            "TagEndpoint.kt out of date — re-run scripts/vendor-tag.sh",
            match?.groupValues?.get(1),
            one.moveo.studycore.generated.TagEndpoint.apiUrl,
        )
    }
}
