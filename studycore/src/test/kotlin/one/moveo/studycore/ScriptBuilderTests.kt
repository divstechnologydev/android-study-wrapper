package one.moveo.studycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptBuilderTests {
    private fun config(name: String = "valid-full.json"): StudyConfig {
        val result = ConfigValidator.validate(Fixtures.text(name))
        return checkNotNull(result.configOrNull) { "fixture $name must validate" }
    }

    private fun build(
        config: StudyConfig,
        tag: String = "/*TAG*/",
        bootstrap: String = "/*BOOTSTRAP*/",
        yieldHosts: List<String> = emptyList(),
        eventLeaves: List<TargetMatch.TargetLeaf>? = null,
    ): String = ScriptBuilder.userScriptSource(
        config = config, tagSource = tag, bootstrapSource = bootstrap,
        yieldHosts = yieldHosts,
        // Default: everything unfired — what a fresh enrollment injects.
        eventLeaves = eventLeaves ?: TargetMatch.unfiredEventLeaves(config.flow.targetAction, emptySet()),
    )

    @Test
    fun guardPrecedesTagAndBootstrap() {
        val source = build(config())
        val guardIndex = source.indexOf("if (!__moveoOk) { return; }")
        val tagIndex = source.indexOf("/*TAG*/")
        val bootstrapIndex = source.indexOf("/*BOOTSTRAP*/")
        assertTrue(guardIndex >= 0 && tagIndex >= 0 && bootstrapIndex >= 0)
        assertTrue("guard must run before the tag is even defined", guardIndex < tagIndex)
        assertTrue("tag defines MoveoOne before the bootstrap uses it", tagIndex < bootstrapIndex)
    }

    @Test
    fun payloadCarriesBridgeMapping() {
        val source = build(config())
        // Same token/options mapping as the extension's config bridge.
        assertTrue(source.contains("\"token\":\"MOVEO_PROJECT_TOKEN_EXAMPLE\""))
        assertTrue(source.contains("\"type\":\"STATIC_WEBSITE\""))
        assertTrue(source.contains("\"appVersion\":\"1.0.0\""))
        assertTrue(source.contains("\"exclude_detailed_tracking\":false"))
        assertTrue(source.contains("\"semanticGroupRules\""))
        assertTrue(source.contains("\"additionalTrackedElements\""))
        assertTrue(source.contains("\"origins\":[\"sainsburys.co.uk\"]"))
    }

    @Test
    fun absentExcludeDetailedTrackingStaysAbsent() {
        val source = build(config(name = "valid-minimal.json"))
        assertFalse(
            "absent must stay absent so the tag applies its per-type default",
            source.contains("exclude_detailed_tracking"),
        )
    }

    /// a3.2 matrix: config variants the mock backend can't serve — the
    /// wrapper's whole job for these fields is verbatim pass-through into
    /// the payload (behavior is the tag's, normative in the extension repo).
    @Test
    fun storageSourceAndExcludeVariantsPassThrough() {
        val sessionText = Fixtures.text("valid-full.json")
            .replace("\"storageSource\": \"local\"", "\"storageSource\": \"session\"")
        val session = checkNotNull(ConfigValidator.validate(sessionText).configOrNull)
        assertTrue(build(session).contains("\"storageSource\":\"session\""))

        val excludeTrueText = Fixtures.text("valid-full.json")
            .replace("\"excludeDetailedTracking\": false", "\"excludeDetailedTracking\": true")
        val excludeTrue = checkNotNull(ConfigValidator.validate(excludeTrueText).configOrNull)
        assertTrue(build(excludeTrue).contains("\"exclude_detailed_tracking\":true"))

        // false pinned by payloadCarriesBridgeMapping; absent by the test above.
    }

    @Test
    fun eventLeavesIncludedOnlyWhileUnfired() {
        // Single event leaf at the root (the pre-composite form) keeps id "t".
        val eventConfig = config(name = "valid-event-match.json")
        val unfired = build(eventConfig)
        assertTrue(unfired, unfired.contains("\"targetAction\":{\"leaves\":[{\"id\":\"t\",\"props\":{"))
        assertFalse("the page gets leaves, never node types", unfired.contains("event_match"))

        val fired = build(eventConfig, eventLeaves = emptyList())
        assertFalse("omitted once fired — zero matching overhead", fired.contains("targetAction"))
    }

    @Test
    fun compositeTreeSendsOnlyUnfiredEventLeavesWithPositionalIds() {
        // and[url_match, or[event_match, event_match]] — the bridge's
        // collectEventLeaves shape: flat { leaves: [{ id, props }] }.
        val nested = config(name = "valid-composite-nested.json")
        val source = build(nested)
        assertTrue(source, source.contains("\"targetAction\":{\"leaves\":[{\"id\":\"t.1.0\",\"props\":{"))
        assertTrue(source.contains("{\"id\":\"t.1.1\",\"props\":{"))
        assertFalse("the url leaf never reaches the page", source.contains("t.0\""))
        assertFalse(source.contains("url_match"))
        assertFalse("the tree never reaches the page", source.contains("conditions"))

        // One leaf latched ⇒ only the other one is sent.
        val partial = build(nested, eventLeaves = TargetMatch.unfiredEventLeaves(nested.flow.targetAction, setOf("t.1.0")))
        assertFalse(partial.contains("\"id\":\"t.1.0\""))
        assertTrue(partial.contains("\"id\":\"t.1.1\""))

        // Every event leaf latched (url leaf still pending) ⇒ nothing for the page.
        val drained = build(nested, eventLeaves = TargetMatch.unfiredEventLeaves(nested.flow.targetAction, setOf("t.1.0", "t.1.1")))
        assertFalse(drained.contains("targetAction"))
    }

    @Test
    fun urlMatchTargetNeverReachesThePage() {
        // valid-full has a url_match target — evaluated natively, not in JS.
        val source = build(config())
        assertFalse(source.contains("targetAction"))
        // Even a url leaf passed in by mistake is dropped by the encoder.
        val forced = build(config(), eventLeaves = listOf(TargetMatch.TargetLeaf.Url(id = "t", pattern = "/checkout")))
        assertFalse(forced.contains("targetAction"))
    }

    @Test
    fun yieldHostsInlinedLowercasedAndDeduped() {
        val source = build(config(), yieldHosts = listOf("Shop.Example.COM", "shop.example.com"))
        assertTrue(source.contains("\"yieldHosts\":{\"shop.example.com\":true}"))
    }

    @Test
    fun studyAuthoredStringsCannotBreakOutOfTheScript() {
        val base = config(name = "valid-minimal.json")
        val cfg = base.copy(tracking = base.tracking.copy(semanticGroupRules = listOf(
            StudyConfig.SemanticGroupRule(
                container = "</script><script>alert(1)</script>",
                name = listOf(StudyConfig.SemanticGroupRule.NameSource(source = "self", attribute = null)),
                mode = null,
            )
        )))
        val source = build(cfg)
        assertFalse("must be neutralized by \\/ escaping", source.contains("</script><script>"))
        assertTrue(source.contains("<\\/script>"))
    }

    @Test
    fun deterministicOutput() {
        val a = build(config(), yieldHosts = listOf("b.com", "a.com"))
        val b = build(config(), yieldHosts = listOf("a.com", "b.com"))
        assertEquals("sorted keys + set-normalized yieldHosts ⇒ reproducible script", a, b)
    }
}
