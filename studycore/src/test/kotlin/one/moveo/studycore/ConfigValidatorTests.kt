package one.moveo.studycore

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// Validator acceptance tests — the Kotlin counterpart of the extension's
/// `test/run-tests.mjs` validator sections (ported from iOS
/// `ConfigValidatorTests.swift`). The vendored fixture suite is normative:
/// this validator must give the same verdict on every fixture.
class ConfigValidatorTests {

    private fun loadFixture(name: String): JsonObject = Fixtures.json(name)

    private fun validConfig(json: JsonElement): StudyConfig {
        val result = ConfigValidator.validate(json)
        return checkNotNull(result.configOrNull) { "expected valid config, got $result" }
    }

    private fun isValid(json: JsonElement): Boolean =
        ConfigValidator.validate(json) is ConfigValidator.ValidationResult.Valid

    // MARK: - Fixture suite

    @Test
    fun fixtureVerdicts() {
        val files = Fixtures.dir().listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 20)

        for (file in files) {
            val name = file.name
            val expectValid = name.startsWith("valid")
            when (val result = ConfigValidator.validate(file.readText())) {
                is ConfigValidator.ValidationResult.Valid ->
                    assertTrue("fixture $name should be invalid but validated", expectValid)
                is ConfigValidator.ValidationResult.Invalid ->
                    assertFalse("fixture $name should be valid but got: ${result.errors}", expectValid)
            }
        }
    }

    @Test
    fun futureVersionSetsNeedsAppUpdate() {
        val result = ConfigValidator.validate(loadFixture("invalid-future-version.json"))
        val invalid = result as? ConfigValidator.ValidationResult.Invalid ?: return fail("expected failure")
        assertTrue(invalid.needsAppUpdate)
    }

    @Test
    fun otherInvalidFixturesDoNotSetNeedsAppUpdate() {
        val result = ConfigValidator.validate(loadFixture("invalid-missing-token.json"))
        val invalid = result as? ConfigValidator.ValidationResult.Invalid ?: return fail("expected failure")
        assertFalse(invalid.needsAppUpdate)
    }

    // MARK: - Defaults (run-tests.mjs "defaults applied for minimal config")

    @Test
    fun minimalDefaults() {
        val config = validConfig(loadFixture("valid-minimal.json"))
        assertEquals("STATIC_WEBSITE", config.tracking.deploymentType)
        assertEquals("local", config.tracking.storageSource)
        assertEquals(emptyList<String>(), config.tracking.userDataKeys)
        assertNull(
            "absent must stay absent — the tag applies its per-type default",
            config.tracking.excludeDetailedTracking,
        )
        assertEquals(emptyList<StudyConfig.SemanticGroupRule>(), config.tracking.semanticGroupRules)
        assertEquals(emptyList<StudyConfig.TrackedElement>(), config.tracking.additionalTrackedElements)
        assertEquals(
            StudyConfig.Study(id = "st_min001", name = "Minimal study", status = StudyConfig.Status.ACTIVE),
            config.study,
        )
        assertEquals(
            StudyConfig.Flow(
                leadInUrl = null, leadOutUrl = null, prepositionPath = null,
                appendParticipantId = false, targetAction = null,
            ),
            config.flow,
        )
    }

    @Test
    fun validFullNormalization() {
        val config = validConfig(loadFixture("valid-full.json"))
        assertEquals(listOf("sainsburys.co.uk"), config.tracking.origins)
        assertEquals(false, config.tracking.excludeDetailedTracking)
        assertEquals(1, config.tracking.semanticGroupRules.size)
        assertEquals(2, config.tracking.semanticGroupRules[0].name.size)
        assertEquals(2, config.tracking.additionalTrackedElements.size)
        assertEquals(TargetAction.UrlMatch(pattern = "/checkout/success"), config.flow.targetAction)
        assertEquals("https://forms.example.com/intro", config.flow.leadInUrl)
    }

    @Test
    fun eventMatchNormalization() {
        val config = validConfig(loadFixture("valid-event-match.json"))
        assertEquals(
            TargetAction.EventMatch(props = mapOf(
                "eA" to "click", "eT" to "button", "eV" to "Buy now", "sc" to "/product/*", "eID" to "*",
            )),
            config.flow.targetAction,
        )
        assertTrue(config.flow.appendParticipantId)
    }

    // MARK: - prepositionPath (browser start page)

    @Test
    fun prepositionPathDefaultsToOriginRoot() {
        val config = validConfig(loadFixture("valid-minimal.json"))
        assertNull(config.flow.prepositionPath)
        assertEquals("https://shop.example.com/", config.startUrl)
    }

    private fun flowWith(value: JsonElement?): StudyConfig {
        val minimal = loadFixture("valid-minimal.json")
        val flow = if (value != null) {
            buildJsonObject { put("prepositionPath", value) }
        } else {
            buildJsonObject {}
        }
        return validConfig(JsonObject(minimal + ("flow" to flow)))
    }

    @Test
    fun prepositionPathNormalization() {
        assertEquals("/nectar/all-products", flowWith(JsonPrimitive("/nectar/all-products")).flow.prepositionPath)
        assertEquals(
            "https://shop.example.com/nectar/all-products",
            flowWith(JsonPrimitive("/nectar/all-products")).startUrl,
        )
        // Missing leading slash tolerated; empty/null/absent ⇒ root.
        assertEquals("https://shop.example.com/nectar", flowWith(JsonPrimitive("nectar")).startUrl)
        assertNull(flowWith(JsonPrimitive("")).flow.prepositionPath)
        assertNull(flowWith(JsonPrimitive("  ")).flow.prepositionPath)
        assertNull(flowWith(JsonNull).flow.prepositionPath)
        assertNull(flowWith(null).flow.prepositionPath)
    }

    @Test
    fun prepositionPathWrongTypeRejected() {
        val minimal = loadFixture("valid-minimal.json")
        val json = JsonObject(minimal + ("flow" to buildJsonObject { put("prepositionPath", 42) }))
        val result = ConfigValidator.validate(json)
        val invalid = result as? ConfigValidator.ValidationResult.Invalid ?: return fail("expected rejection")
        assertTrue(invalid.errors.contains("flow.prepositionPath: must be a string"))
    }

    // MARK: - Extra validator cases beyond the shared fixtures

    private fun minimalWithTracking(extra: Map<String, JsonElement>): JsonObject {
        val minimal = loadFixture("valid-minimal.json")
        val tracking = minimal["tracking"]!!.jsonObject
        return JsonObject(minimal + ("tracking" to JsonObject(tracking + extra)))
    }

    private fun sgRule(container: String = ".p", sources: Int = 1): JsonObject = buildJsonObject {
        put("container", container)
        put("name", kotlinx.serialization.json.JsonArray(List(sources) {
            buildJsonObject { put("source", "self") }
        }))
    }

    @Test
    fun sgRulesPassThroughIntact() {
        val rules = kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("container", ".p[data-id]")
            put("name", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
                put("source", "self")
                put("attribute", "data-name")
            })))
            put("mode", "join")
        }))
        val config = validConfig(minimalWithTracking(mapOf("semanticGroupRules" to rules)))
        assertEquals(
            listOf(StudyConfig.SemanticGroupRule(
                container = ".p[data-id]",
                name = listOf(StudyConfig.SemanticGroupRule.NameSource(source = "self", attribute = "data-name")),
                mode = "join",
            )),
            config.tracking.semanticGroupRules,
        )
    }

    @Test
    fun sgRules21Rejected() {
        val rules = kotlinx.serialization.json.JsonArray(List(21) { sgRule() })
        assertFalse(isValid(minimalWithTracking(mapOf("semanticGroupRules" to rules))))
    }

    @Test
    fun sgRules6SourcesRejected() {
        val rules = kotlinx.serialization.json.JsonArray(listOf(sgRule(sources = 6)))
        assertFalse(isValid(minimalWithTracking(mapOf("semanticGroupRules" to rules))))
    }

    @Test
    fun trackedElementsPassThroughIntact() {
        val entries = kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("selector", "span[data-testid='contextual-price-text']")
            put("type", "price_nectar")
        }))
        val config = validConfig(minimalWithTracking(mapOf("additionalTrackedElements" to entries)))
        assertEquals(
            listOf(StudyConfig.TrackedElement(
                selector = "span[data-testid='contextual-price-text']", type = "price_nectar",
            )),
            config.tracking.additionalTrackedElements,
        )
    }

    @Test
    fun trackedElementsEmptyTypeRejected() {
        val entries = kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("selector", ".price")
            put("type", "")
        }))
        assertFalse(isValid(minimalWithTracking(mapOf("additionalTrackedElements" to entries))))
    }

    @Test
    fun trackedElements51CharTypeRejected() {
        val entries = kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("selector", ".price")
            put("type", "x".repeat(51))
        }))
        assertFalse(isValid(minimalWithTracking(mapOf("additionalTrackedElements" to entries))))
    }

    @Test
    fun trackedElements21EntriesRejected() {
        val entries = kotlinx.serialization.json.JsonArray(List(21) {
            buildJsonObject { put("selector", ".price") }
        })
        assertFalse(isValid(minimalWithTracking(mapOf("additionalTrackedElements" to entries))))
    }

    @Test
    fun trackedElementsMissingSelectorRejected() {
        val entries = kotlinx.serialization.json.JsonArray(listOf(buildJsonObject {
            put("label", "price")
        }))
        assertFalse(isValid(minimalWithTracking(mapOf("additionalTrackedElements" to entries))))
    }

    @Test
    fun trackedElementsNonArrayRejected() {
        assertFalse(isValid(minimalWithTracking(mapOf("additionalTrackedElements" to JsonPrimitive(".price")))))
    }

    @Test
    fun unknownTopLevelFieldsIgnored() {
        val minimal = loadFixture("valid-minimal.json")
        val json = JsonObject(minimal + ("futureFeature" to buildJsonObject { put("x", 1) }))
        assertTrue(isValid(json))
    }

    @Test
    fun unknownTargetActionTypeTreatedAsAbsent() {
        val minimal = loadFixture("valid-minimal.json")
        val json = JsonObject(minimal + ("flow" to buildJsonObject {
            putJsonObject("targetAction") {
                put("type", "element_click")
                put("selector", "#buy")
            }
        }))
        val config = validConfig(json)
        assertNull(config.flow.targetAction)
    }

    @Test
    fun nonObjectConfigRejected() {
        assertFalse(isValid(JsonPrimitive("nope")))
    }

    @Test
    fun endedStudyStillSchemaValid() {
        val minimal = loadFixture("valid-minimal.json")
        val study = minimal["study"]!!.jsonObject
        val json = JsonObject(minimal + ("study" to JsonObject(study + ("status" to JsonPrimitive("ended")))))
        val config = validConfig(json)
        assertEquals(StudyConfig.Status.ENDED, config.study.status)
    }

    @Test
    fun booleanSchemaVersionRejected() {
        val minimal = loadFixture("valid-minimal.json")
        val json = JsonObject(minimal + ("schemaVersion" to JsonPrimitive(true)))
        assertFalse(
            "Number.isInteger(true) is false in JS — booleans are not integers here either",
            isValid(json),
        )
    }

    // MARK: - Round-trip (StudyStore persists the typed config in M2)

    @Test
    fun configCodableRoundTrip() {
        val config = validConfig(loadFixture("valid-full.json"))
        val encoded = StudyCoreJson.encodeToString(StudyConfig.serializer(), config)
        assertEquals(config, StudyCoreJson.decodeFromString(StudyConfig.serializer(), encoded))

        // excludeDetailedTracking null must round-trip as ABSENT, not false —
        // the injected payload relies on this to let the tag pick defaults.
        val minimal = validConfig(loadFixture("valid-minimal.json"))
        val encodedMinimal = StudyCoreJson.encodeToString(StudyConfig.serializer(), minimal)
        val tree = kotlinx.serialization.json.Json.parseToJsonElement(encodedMinimal).jsonObject
        val tracking = tree["tracking"]!!.jsonObject
        assertNull(tracking["excludeDetailedTracking"])
        assertNotNull(tracking["token"])
    }
}
