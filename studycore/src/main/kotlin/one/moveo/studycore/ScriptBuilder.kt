package one.moveo.studycore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/// Assembles the single document-start script injected into every frame:
///
///     (IIFE) payload → origin guard → vendored tag → Android bootstrap
///
/// The guard is the a0.3 layer-2 defense and deliberately wraps EVERYTHING —
/// off-study frames return before `window.MoveoOne` is even defined. On
/// Android the platform's `allowedOriginRules` already scopes injection
/// (layer 1 comes for free — the opposite of iOS, where WKUserScript can't
/// filter); the guard is kept anyway: it protects against rule-derivation
/// bugs and keeps script assembly byte-comparable across platforms. The
/// payload mirrors the extension's config bridge exactly (`token` +
/// `options` mapping), plus the pieces the bridge computed per page load and
/// we must inline instead: the origin list for the guard and the own-tag
/// yield map.
object ScriptBuilder {
    /// Build the injected-script source. `yieldHosts` are hostnames where a
    /// site-own Moveo tag was detected (the app yields there from the next
    /// load). `includeEventTarget` is true only while an event_match target
    /// exists and has not fired — matching the bridge's "omitted once fired"
    /// rule so post-completion loads carry zero matching overhead. url_match
    /// targets never reach the page: they are evaluated natively.
    fun userScriptSource(
        config: StudyConfig,
        tagSource: String,
        bootstrapSource: String,
        yieldHosts: List<String>,
        includeEventTarget: Boolean,
    ): String {
        val payloadJson = payloadJson(
            config = config, yieldHosts = yieldHosts, includeEventTarget = includeEventTarget,
        )

        return """(function () {
"use strict";
var __MOVEO_PAYLOAD__ = $payloadJson;
// a0.3 layer 2 — the privacy guarantee. Must be the first executable
// statement: no match ⇒ no MoveoOne global, no listeners, no network.
// Same §5 match rule as the native navigation policy and the consent
// wording (all three derive from Origins.hostnameMatches).
var __moveoHost = String(location.hostname || "").toLowerCase();
var __moveoOk = false;
for (var __i = 0; __i < __MOVEO_PAYLOAD__.origins.length; __i++) {
    var __o = __MOVEO_PAYLOAD__.origins[__i];
    if (__moveoHost === __o || __moveoHost.slice(-(__o.length + 1)) === "." + __o) {
        __moveoOk = true;
        break;
    }
}
if (!__moveoOk) { return; }
if (__MOVEO_PAYLOAD__.yieldHosts && __MOVEO_PAYLOAD__.yieldHosts[__moveoHost]) { return; }
$tagSource
;
$bootstrapSource
})();"""
    }

    // MARK: - Payload

    /// Mirrors moveo-config-bridge.js `detail` (minus `yield`, which became
    /// the yieldHosts map checked in the guard). Built as a JSON tree so
    /// absent optionals stay ABSENT in JSON — `exclude_detailed_tracking`
    /// omitted lets the tag apply its per-type default, `targetAction`
    /// omitted means nothing to match.
    private fun payloadJson(
        config: StudyConfig,
        yieldHosts: List<String>,
        includeEventTarget: Boolean,
    ): String {
        val t = config.tracking
        val target = (config.flow.targetAction as? TargetAction.EventMatch)
            ?.takeIf { includeEventTarget }

        val payload = buildJsonObject {
            put("token", t.token)
            putJsonObject("options") {
                put("type", t.deploymentType)
                t.appVersion?.let { put("appVersion", it) }
                put("storageSource", t.storageSource)
                put("userDataKeys", JsonArray(t.userDataKeys.map { JsonPrimitive(it) }))
                t.excludeDetailedTracking?.let { put("exclude_detailed_tracking", it) }
                put("semanticGroupRules", JsonArray(t.semanticGroupRules.map { rule ->
                    buildJsonObject {
                        put("container", rule.container)
                        put("name", JsonArray(rule.name.map { src ->
                            buildJsonObject {
                                put("source", src.source)
                                src.attribute?.let { put("attribute", it) }
                            }
                        }))
                        rule.mode?.let { put("mode", it) }
                    }
                }))
                put("additionalTrackedElements", JsonArray(t.additionalTrackedElements.map { e ->
                    buildJsonObject {
                        put("selector", e.selector)
                        e.type?.let { put("type", it) }
                    }
                }))
            }
            put("origins", JsonArray(t.origins.map { JsonPrimitive(it.lowercase()) }))
            putJsonObject("yieldHosts") {
                yieldHosts.map { it.lowercase() }.toSortedSet().forEach { put(it, true) }
            }
            target?.let { tgt ->
                putJsonObject("targetAction") {
                    put("type", "event_match")
                    put("props", JsonObject(tgt.props.mapValues { JsonPrimitive(it.value) }))
                }
            }
        }

        // Sorted keys: deterministic output (testable, diffable builds), same
        // as the iOS encoder's .sortedKeys. The "/" → "\/" escape mirrors
        // Foundation's default, which neutralizes "</script>" and friends
        // inside study-authored strings — do NOT remove it.
        return Json.encodeToString(JsonElement.serializer(), sortKeys(payload))
            .replace("/", "\\/")
    }

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) }
        )
        is JsonArray -> JsonArray(element.map { sortKeys(it) })
        else -> element
    }
}
