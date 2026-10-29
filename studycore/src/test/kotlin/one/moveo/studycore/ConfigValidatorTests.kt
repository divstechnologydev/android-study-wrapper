package one.moveo.studycore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        assertTrue(files.size >= 30)

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

    // MARK: - study.instructions (config-schema §2.1; run-tests.mjs §10)

    @Test
    fun instructionsCarriedThroughFromValidFull() {
        val config = validConfig(loadFixture("valid-full.json"))
        val instructions = checkNotNull(config.study.instructions)
        assertTrue(instructions.startsWith("Thanks for taking part!"))
        assertTrue(Instructions.parse(instructions).isNotEmpty())
    }

    @Test
    fun instructionsAbsentStaysNull() {
        assertNull(validConfig(loadFixture("valid-minimal.json")).study.instructions)
    }

    @Test
    fun instructionsValidationMatchesExtension() {
        fun verdict(value: JsonElement?): List<String> {
            val json = loadFixture("valid-minimal.json")
            val study = json["study"]!!.jsonObject
            val patchedStudy = if (value != null) JsonObject(study + ("instructions" to value)) else study
            return when (val result = ConfigValidator.validate(JsonObject(json + ("study" to patchedStudy)))) {
                is ConfigValidator.ValidationResult.Valid -> emptyList()
                is ConfigValidator.ValidationResult.Invalid -> result.errors
            }
        }
        val expected = "study.instructions: must be a string (max 2000 chars)"
        assertEquals("exactly the cap is fine", emptyList<String>(), verdict(JsonPrimitive("x".repeat(2000))))
        assertEquals(listOf(expected), verdict(JsonPrimitive("x".repeat(2001))))
        assertEquals("non-string", listOf(expected), verdict(JsonPrimitive(42)))
        assertEquals("JS: null !== undefined → fails the string check", listOf(expected), verdict(JsonNull))
        assertEquals("blank is valid — the renderer hides the section", emptyList<String>(), verdict(JsonPrimitive("   ")))
        assertEquals(emptyList<String>(), verdict(null))
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

    /// run-tests.mjs "flow.prepositionPath (§4)": present + non-empty ⇒ kept
    /// (trimmed); absent / null / blank ⇒ null; URL-shaped values rejected.
    @Test
    fun prepositionPathCaseTableMatchesExtension() {
        fun verdict(value: JsonElement): ConfigValidator.ValidationResult {
            val minimal = loadFixture("valid-minimal.json")
            return ConfigValidator.validate(JsonObject(minimal + ("flow" to buildJsonObject { put("prepositionPath", value) })))
        }
        fun kept(value: String): String? = checkNotNull(verdict(JsonPrimitive(value)).configOrNull).flow.prepositionPath
        assertEquals("kept", "/shop/en-GB/landing/groceries", kept("/shop/en-GB/landing/groceries"))
        assertEquals("trimmed", "/shop/landing", kept("  /shop/landing  "))
        assertEquals("query+fragment kept", "/landing?ref=study#top", kept("/landing?ref=study#top"))
        assertEquals("missing leading slash kept as-is", "shop/landing", kept("shop/landing"))
        assertNull("null ⇒ absent", checkNotNull(verdict(JsonNull).configOrNull).flow.prepositionPath)
        assertNull("empty ⇒ absent", kept(""))
        assertNull("blank ⇒ absent", kept("   "))
        assertEquals("JS trim strips the BOM too", "/x", kept("﻿/x "))

        val rejected = listOf(
            "number" to JsonPrimitive(123),
            "https URL" to JsonPrimitive("https://evil.example.net/x"),
            "scheme javascript" to JsonPrimitive("javascript:alert(1)"),
            "protocol-relative" to JsonPrimitive("//evil.example.net/x"),
            "backslash" to JsonPrimitive("\\\\evil.example.net"),
            "inner whitespace" to JsonPrimitive("/shop/my landing"),
            "inner no-break space" to JsonPrimitive("/shop/my landing"),
            "control character" to JsonPrimitive("/shop/\u0001"),
            "too long" to JsonPrimitive("/" + "a".repeat(500)),
        )
        for ((name, value) in rejected) {
            val invalid = verdict(value) as? ConfigValidator.ValidationResult.Invalid
                ?: return fail("prepositionPath should be rejected: $name")
            assertTrue("$name: ${invalid.errors}", invalid.errors.all { it.startsWith("flow.prepositionPath") })
        }
        assertEquals(
            listOf("flow.prepositionPath: must be a string (≤500 chars) when present"),
            (verdict(JsonPrimitive(42)) as ConfigValidator.ValidationResult.Invalid).errors,
        )
        assertEquals(
            listOf("flow.prepositionPath: must be a path on the study origin (e.g. \"/shop/landing\"), not a URL"),
            (verdict(JsonPrimitive("https://evil.example.net/x")) as ConfigValidator.ValidationResult.Invalid).errors,
        )
    }

    /// run-tests.mjs §4b buildStartUrl: absent/blank ⇒ the origin root;
    /// present ⇒ that path on the FIRST origin; whatever the stored value,
    /// the result is always https on that host.
    @Test
    fun startUrlCaseTableMatchesExtension() {
        val origins = listOf("www.tesco.com", "tesco.ie")
        fun start(path: String?): String? = StartUrl.build(origins, path)
        assertEquals("no path ⇒ root", "https://www.tesco.com/", start(null))
        assertEquals("empty ⇒ root", "https://www.tesco.com/", start(""))
        assertEquals("blank ⇒ root", "https://www.tesco.com/", start("   "))
        assertEquals("https://www.tesco.com/shop/en-GB/landing/groceries", start("/shop/en-GB/landing/groceries"))
        assertEquals("missing leading slash tolerated", "https://www.tesco.com/shop/landing", start("shop/landing"))
        assertEquals("query + fragment preserved", "https://www.tesco.com/landing?ref=study#top", start("/landing?ref=study#top"))
        assertEquals("surrounding whitespace trimmed", "https://www.tesco.com/landing", start("  /landing "))
        assertEquals("'..' cannot leave the origin", "https://www.tesco.com/x", start("/../../x"))
        assertEquals("protocol-relative host falls back to root", "https://www.tesco.com/", start("//evil.example.net/x"))
        assertEquals("backslash host falls back to root", "https://www.tesco.com/", start("\\\\evil.example.net"))
        assertEquals("absolute URL falls back to root", "https://www.tesco.com/", start("https://evil.example.net/x"))
        assertEquals("scheme falls back to root", "https://www.tesco.com/", start("javascript:alert(1)"))
        assertEquals("space percent-encoded like `new URL`", "https://www.tesco.com/my%20landing", start("/my landing"))
        assertNull("no origins ⇒ null", StartUrl.build(emptyList(), "/x"))
        assertNull("malformed origin ⇒ null", StartUrl.build(listOf("bad host"), "/x"))
        for (path in listOf("/a", "b", "//c.com", "\\\\d.com", "https://e.com", "/../f", "?q=1", "#h", "/my landing")) {
            val url = checkNotNull(start(path)).toHttpUrl()
            assertEquals(path, "https", url.scheme)
            assertEquals(path, "www.tesco.com", url.host)
        }
    }

    @Test
    fun prepositionPathFixtures() {
        // Vendored with the extension's "preposition fix": the URL fixture is
        // invalid, the empty-path fixture valid, and valid-full now carries one.
        assertFalse(isValid(loadFixture("invalid-preposition-path-url.json")))
        assertTrue(isValid(loadFixture("valid-preposition-path-empty.json")))
        assertNull(validConfig(loadFixture("valid-preposition-path-empty.json")).flow.prepositionPath)
        val full = validConfig(loadFixture("valid-full.json"))
        assertEquals("/gol-ui/groceries/meat-and-fish?ref=study", full.flow.prepositionPath)
        assertEquals("https://sainsburys.co.uk/gol-ui/groceries/meat-and-fish?ref=study", full.startUrl)
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
    fun sgRulesNoCapOnRuleCount() {
        // run-tests.mjs: "semanticGroupRules: no cap on rule count (40 accepted)"
        val rules = kotlinx.serialization.json.JsonArray(List(40) { sgRule() })
        val config = validConfig(minimalWithTracking(mapOf("semanticGroupRules" to rules)))
        assertEquals(40, config.tracking.semanticGroupRules.size)
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
    fun trackedElementsNoCapOnEntryCount() {
        // run-tests.mjs: "additionalTrackedElements: no cap on entry count (40 accepted)"
        val entries = kotlinx.serialization.json.JsonArray(List(40) {
            buildJsonObject { put("selector", ".price") }
        })
        val config = validConfig(minimalWithTracking(mapOf("additionalTrackedElements" to entries)))
        assertEquals(40, config.tracking.additionalTrackedElements.size)
    }

    /// The vendored Tesco example (docs/examples/tesco-sg-rules.json) is the
    /// extension's CSP-strict reference config; its tracking fragments carry
    /// more than 20 force-tracked entries, so it doubles as the regression
    /// fixture for the cap removal — a reintroduced cap fails here.
    @Test
    fun tescoExampleTrackingFragmentsValidate() {
        val example = java.io.File(Fixtures.repoRoot(), "docs/examples/tesco-sg-rules.json")
        assertTrue("tesco example missing — run scripts/vendor-tag.sh", example.isFile)
        val tracking = Json.parseToJsonElement(example.readText()).jsonObject["tracking"]!!.jsonObject
        val config = validConfig(minimalWithTracking(mapOf(
            "semanticGroupRules" to tracking["semanticGroupRules"]!!,
            "additionalTrackedElements" to tracking["additionalTrackedElements"]!!,
        )))
        assertTrue(config.tracking.semanticGroupRules.isNotEmpty())
        assertTrue("the example exceeds the old 20-entry cap", config.tracking.additionalTrackedElements.size > 20)
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

    // MARK: - Composite targets (config-schema §4.3; run-tests.mjs §6b
    // "Validator extras for composites" — fixtures cover the main shapes,
    // these pin the boundaries)

    private val tUrl: JsonObject = buildJsonObject {
        put("type", "url_match")
        put("pattern", "/checkout/*/success")
    }
    private val tBuy: JsonObject = buildJsonObject {
        put("type", "event_match")
        putJsonObject("props") { put("eA", "click"); put("eV", "Buy now") }
    }
    private val tPay: JsonObject = buildJsonObject {
        put("type", "event_match")
        putJsonObject("props") { put("eA", "click"); put("eV", "Pay now") }
    }

    private fun composite(type: String, vararg children: JsonElement): JsonObject = buildJsonObject {
        put("type", type)
        put("conditions", JsonArray(children.toList()))
    }

    private fun withTarget(targetAction: JsonElement): JsonObject {
        val minimal = loadFixture("valid-minimal.json")
        return JsonObject(minimal + ("flow" to buildJsonObject { put("targetAction", targetAction) }))
    }

    /// The typed target of a config that must validate (null ≡ treated as absent).
    private fun validTarget(targetAction: JsonElement): TargetAction? =
        validConfig(withTarget(targetAction)).flow.targetAction

    /// The error list of a config that must NOT validate.
    private fun targetErrors(targetAction: JsonElement): List<String> =
        when (val result = ConfigValidator.validate(withTarget(targetAction))) {
            is ConfigValidator.ValidationResult.Valid ->
                throw AssertionError("expected invalid, got ${result.config.flow.targetAction}")
            is ConfigValidator.ValidationResult.Invalid -> result.errors
        }

    private fun leaves(n: Int): Array<JsonElement> = Array(n) { tBuy }

    private fun nest(depth: Int, leaf: JsonObject): JsonObject =
        if (depth == 0) leaf else composite(if (depth % 2 == 1) "and" else "or", nest(depth - 1, leaf))

    private val urlLeaf = TargetAction.UrlMatch(pattern = "/checkout/*/success")
    private val buyLeaf = TargetAction.EventMatch(props = mapOf("eA" to "click", "eV" to "Buy now"))
    private val payLeaf = TargetAction.EventMatch(props = mapOf("eA" to "click", "eV" to "Pay now"))

    @Test
    fun compositeNestedTreeAcceptedVerbatim() {
        val nested = composite("and", tUrl, composite("or", tBuy, tPay))
        assertEquals(TargetAction.And(listOf(urlLeaf, TargetAction.Or(listOf(buyLeaf, payLeaf)))), validTarget(nested))

        val fixture = validConfig(loadFixture("valid-composite-nested.json"))
        assertEquals(
            TargetAction.And(listOf(
                urlLeaf,
                TargetAction.Or(listOf(
                    TargetAction.EventMatch(props = mapOf("eA" to "click", "eT" to "button", "eV" to "Buy now")),
                    TargetAction.EventMatch(props = mapOf("eA" to "click", "eT" to "button", "eV" to "Pay now")),
                )),
            )),
            fixture.flow.targetAction,
        )
    }

    @Test
    fun compositeChildAndLeafCaps() {
        assertEquals("single child ok", TargetAction.Or(listOf(urlLeaf)), validTarget(composite("or", tUrl)))
        assertNotNull("10 children ok", validTarget(composite("or", *leaves(10))))
        assertEquals(
            "11 children rejected",
            listOf("flow.targetAction.conditions: required non-empty array (1–10) for and/or"),
            targetErrors(composite("or", *leaves(11))),
        )
        assertNotNull(
            "10 leaves across tree ok",
            validTarget(composite("and", composite("or", *leaves(5)), composite("or", *leaves(5)))),
        )
        assertEquals(
            "11 leaves across tree rejected",
            listOf("flow.targetAction: more than 10 leaf conditions (§4.3)"),
            targetErrors(composite("and", composite("or", *leaves(6)), composite("or", *leaves(5)))),
        )
    }

    @Test
    fun compositeDepthCap() {
        assertNotNull("depth 3 ok", validTarget(nest(3, tUrl)))
        assertEquals(
            "depth 4 rejected",
            listOf("flow.targetAction.conditions[0].conditions[0].conditions[0]: nesting deeper than 3 composite levels (§4.3)"),
            targetErrors(nest(4, tUrl)),
        )
    }

    @Test
    fun compositeUnknownNestedTypeNullsWholeTarget() {
        val future = buildJsonObject { put("type", "future"); put("anything", 1) }
        assertNull("nested unknown type → whole target null, config ok", validTarget(composite("and", tUrl, future)))
        val fixture = validConfig(loadFixture("valid-composite-unknown-nested-type.json"))
        assertNull(fixture.flow.targetAction)
        val elementClick = buildJsonObject { put("type", "element_click"); put("selector", "#buy") }
        assertNull("unknown root type still → null (pre-existing rule)", validTarget(elementClick))
    }

    @Test
    fun compositeStructuralErrorsCarryThePath() {
        val bogus = buildJsonObject { put("type", "event_match"); putJsonObject("props") { put("bogus", "x") } }
        val errors = targetErrors(composite("and", tUrl, composite("or", bogus)))
        assertTrue(
            "$errors",
            errors[0].startsWith("flow.targetAction.conditions[1].conditions[0].props: unknown key"),
        )
        assertEquals(
            listOf("flow.targetAction.conditions[1]: must be an object"),
            targetErrors(composite("and", tUrl, JsonPrimitive("click"))),
        )
        assertEquals(
            listOf("flow.targetAction.conditions: required non-empty array (1–10) for and/or"),
            targetErrors(composite("and")),
        )
        assertEquals(
            "conditions must be an array",
            listOf("flow.targetAction.conditions: required non-empty array (1–10) for and/or"),
            targetErrors(buildJsonObject { put("type", "or"); put("conditions", tUrl) }),
        )
        val stars = buildJsonObject { put("type", "url_match"); put("pattern", "***") }
        assertEquals(
            "leaf rules apply at any depth",
            listOf("flow.targetAction.conditions[0].pattern: must not be wildcards only (§4.1 — would fire on the first page visited)"),
            targetErrors(composite("or", stars)),
        )
        assertEquals(
            "root leaf error strings unchanged",
            listOf("flow.targetAction.pattern: must not be wildcards only (§4.1 — would fire on the first page visited)"),
            targetErrors(stars),
        )
        assertEquals(listOf("flow.targetAction: must be an object or null"), targetErrors(JsonPrimitive("nope")))
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

        // Composite trees persist as { type, conditions } and decode back —
        // the shape stored on the active study (ActiveStudy.config).
        val nested = validConfig(loadFixture("valid-composite-nested.json"))
        val encodedNested = StudyCoreJson.encodeToString(StudyConfig.serializer(), nested)
        assertEquals(nested, StudyCoreJson.decodeFromString(StudyConfig.serializer(), encodedNested))
        val nestedTree = kotlinx.serialization.json.Json.parseToJsonElement(encodedNested).jsonObject
        val ta = nestedTree["flow"]!!.jsonObject["targetAction"]!!.jsonObject
        assertEquals("and", ta["type"]!!.jsonPrimitive.content)
        assertEquals(2, (ta["conditions"] as JsonArray).size)

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
