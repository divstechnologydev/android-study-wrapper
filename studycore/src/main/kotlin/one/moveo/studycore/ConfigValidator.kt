package one.moveo.studycore

import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/// Study-config validator — the Android implementation of the contract in the
/// extension repo's `docs/config-schema.md`, ported branch-for-branch from
/// `extension/src/validate-config.js` (via iOS `ConfigValidator.swift`). The
/// vendored fixture suite is the acceptance test: this validator must give
/// the same verdict on every fixture (asserted in `ConfigValidatorTests`).
///
/// Works on a parsed `JsonElement` tree (not data-class decoding) so the
/// semantics match the JS validator exactly: unknown fields are ignored at
/// every level, and "wrong type" is a validation error, never a decode crash.
object ConfigValidator {
    const val SUPPORTED_SCHEMA_VERSION = 1

    /// Validation outcome. `needsAppUpdate` is set when the config's
    /// schemaVersion is newer than this build supports — the UI shows
    /// "please update the app" instead of generic `invalid_config`.
    sealed class ValidationResult {
        data class Valid(val config: StudyConfig) : ValidationResult()
        data class Invalid(val errors: List<String>, val needsAppUpdate: Boolean) : ValidationResult()

        val configOrNull: StudyConfig? get() = (this as? Valid)?.config
    }

    fun validate(text: String): ValidationResult {
        val json = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return ValidationResult.Invalid(listOf("config must be valid JSON"), needsAppUpdate = false)
        }
        return validate(json)
    }

    fun validate(input: JsonElement): ValidationResult {
        val errors = mutableListOf<String>()
        var needsAppUpdate = false

        val obj = asObject(input)
            ?: return ValidationResult.Invalid(listOf("config must be an object"), needsAppUpdate = false)

        // schemaVersion — refuse versions newer than we know (§5)
        val schemaVersion = asInteger(obj["schemaVersion"])
        if (schemaVersion != null) {
            if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
                errors += "schemaVersion: $schemaVersion is newer than supported ($SUPPORTED_SCHEMA_VERSION) — update the app"
                needsAppUpdate = true
            }
        } else {
            errors += "schemaVersion: required integer"
        }

        // study
        val study = asObject(obj["study"])
        if (study != null) {
            if (!isNonEmptyStr(study["id"])) errors += "study.id: required non-empty string"
            val name = asString(study["name"])
            if (name == null || name.isEmpty() || name.length > 120) {
                errors += "study.name: required string (1–120 chars)"
            }
            val status = asString(study["status"])
            if (status !in listOf("active", "ended")) {
                errors += "study.status: must be 'active' | 'ended'"
            }
        } else {
            errors += "study: required object"
        }

        // tracking
        val tracking = asObject(obj["tracking"])
        if (tracking != null) {
            if (!isNonEmptyStr(tracking["token"])) errors += "tracking.token: required non-empty string"
            if (!isHttpsUrl(tracking["apiUrl"])) errors += "tracking.apiUrl: required https URL"
            val origins = tracking["origins"] as? JsonArray
            if (origins != null && origins.isNotEmpty()) {
                for (o in origins) {
                    val s = asString(o)
                    if (s == null || s.isEmpty() || !isValidOrigin(s)) {
                        errors += "tracking.origins: invalid origin ${jsonQuote(o)} (bare lowercase hostname, ≥2 labels)"
                    }
                }
            } else {
                errors += "tracking.origins: required non-empty array"
            }
            present(tracking["deploymentType"])?.let { dt ->
                if (asString(dt) !in listOf("STATIC_WEBSITE", "WEB_APP")) {
                    errors += "tracking.deploymentType: must be 'STATIC_WEBSITE' | 'WEB_APP'"
                }
            }
            present(tracking["storageSource"])?.let { ss ->
                if (asString(ss) !in listOf("local", "session")) {
                    errors += "tracking.storageSource: must be 'local' | 'session'"
                }
            }
            present(tracking["userDataKeys"])?.let { keys ->
                val arr = keys as? JsonArray
                if (arr == null || !arr.all { isNonEmptyStr(it) }) {
                    errors += "tracking.userDataKeys: must be an array of non-empty strings"
                }
            }
            present(tracking["excludeDetailedTracking"])?.let { ex ->
                if (!isBool(ex)) errors += "tracking.excludeDetailedTracking: must be boolean"
            }
            validateSemanticGroupRules(tracking["semanticGroupRules"], errors)
            validateAdditionalTrackedElements(tracking["additionalTrackedElements"], errors)
        } else {
            errors += "tracking: required object"
        }

        // flow (optional)
        var flowObj: JsonObject? = null
        val rawFlow = present(obj["flow"])
        if (rawFlow != null) {
            val f = asObject(rawFlow)
            if (f != null) {
                flowObj = f
                present(f["leadInUrl"])?.let {
                    if (!isHttpsUrl(it)) errors += "flow.leadInUrl: must be an https URL"
                }
                present(f["leadOutUrl"])?.let {
                    if (!isHttpsUrl(it)) errors += "flow.leadOutUrl: must be an https URL"
                }
                present(f["prepositionPath"])?.let {
                    if (asString(it) == null) errors += "flow.prepositionPath: must be a string"
                }
                present(f["appendParticipantId"])?.let {
                    if (!isBool(it)) errors += "flow.appendParticipantId: must be boolean"
                }
                present(f["targetAction"])?.let { validateTargetAction(it, errors) }
            } else {
                errors += "flow: must be an object when present"
            }
        }

        if (errors.isNotEmpty()) {
            return ValidationResult.Invalid(errors, needsAppUpdate)
        }

        // All checks passed — build the typed config with documented defaults
        // applied (the validator is the one place defaults live in code).
        val s = study ?: return internalError()
        val t = tracking ?: return internalError()
        val id = asString(s["id"]) ?: return internalError()
        val name = asString(s["name"]) ?: return internalError()
        val status = asString(s["status"])?.let { StudyConfig.Status.fromRaw(it) } ?: return internalError()
        val token = asString(t["token"]) ?: return internalError()
        val apiUrl = asString(t["apiUrl"]) ?: return internalError()
        val originsList = (t["origins"] as? JsonArray)?.mapNotNull { asString(it) } ?: return internalError()
        val version = schemaVersion ?: return internalError()

        val config = StudyConfig(
            schemaVersion = version,
            study = StudyConfig.Study(id = id, name = name, status = status),
            tracking = StudyConfig.Tracking(
                token = token,
                apiUrl = apiUrl,
                origins = originsList,
                deploymentType = asString(t["deploymentType"]) ?: "STATIC_WEBSITE",
                // Untyped in the contract's validator too: a non-string
                // appVersion passes JS validation but is ignored by the tag's
                // own type check, so dropping it here is verdict-identical.
                appVersion = asString(t["appVersion"]),
                storageSource = asString(t["storageSource"]) ?: "local",
                userDataKeys = (t["userDataKeys"] as? JsonArray)?.mapNotNull { asString(it) } ?: emptyList(),
                excludeDetailedTracking = t["excludeDetailedTracking"]
                    ?.takeIf { isBool(it) }?.jsonPrimitive?.content?.toBoolean(),
                semanticGroupRules = buildSgRules(t["semanticGroupRules"]),
                additionalTrackedElements = buildTrackedElements(t["additionalTrackedElements"]),
            ),
            flow = StudyConfig.Flow(
                leadInUrl = flowObj?.let { asString(it["leadInUrl"]) },
                leadOutUrl = flowObj?.let { asString(it["leadOutUrl"]) },
                // Empty/whitespace collapses to null ("ignore it, keep the
                // current behavior") so consumers never re-check emptiness.
                prepositionPath = flowObj?.let { asString(it["prepositionPath"]) }
                    ?.trim()?.takeIf { it.isNotEmpty() },
                appendParticipantId = flowObj?.get("appendParticipantId")
                    ?.takeIf { isBool(it) }?.jsonPrimitive?.content?.toBoolean() ?: false,
                targetAction = buildTargetAction(flowObj?.get("targetAction")), // unknown type ⇒ null (treated as absent)
            ),
        )
        return ValidationResult.Valid(config)
    }

    private fun internalError() =
        ValidationResult.Invalid(listOf("internal: validated fields missing"), needsAppUpdate = false)

    // MARK: - Section validators (structural checks only — selector syntax
    // needs a DOM and is enforced at use time by the tag, fail-open)

    private fun validateSemanticGroupRules(raw: JsonElement?, errors: MutableList<String>) {
        val rawPresent = present(raw) ?: return
        val rules = rawPresent as? JsonArray ?: run {
            errors += "tracking.semanticGroupRules: must be an array"
            return
        }
        if (rules.size > 20) errors += "tracking.semanticGroupRules: max 20 rules"
        for ((i, r) in rules.withIndex()) {
            val at = "tracking.semanticGroupRules[$i]"
            val rule = asObject(r) ?: run {
                errors += "$at: must be an object"
                null
            } ?: continue
            if (!(isNonEmptyStr(rule["container"]) && strLen(rule["container"]) <= 250)) {
                errors += "$at.container: required string (1–250 chars)"
            }
            val nameArr = rule["name"] as? JsonArray
            if (nameArr != null && nameArr.isNotEmpty()) {
                if (nameArr.size > 5) {
                    errors += "$at.name: max 5 sources"
                } else {
                    for ((j, src) in nameArr.withIndex()) {
                        val sat = "$at.name[$j]"
                        val source = asObject(src) ?: run {
                            errors += "$sat: must be an object"
                            null
                        } ?: continue
                        if (!(isNonEmptyStr(source["source"]) && strLen(source["source"]) <= 250)) {
                            errors += "$sat.source: required string (1–250 chars; \"self\" = the container itself)"
                        }
                        present(source["attribute"])?.let { attr ->
                            if (!(isNonEmptyStr(attr) && strLen(attr) <= 100)) {
                                errors += "$sat.attribute: must be a non-empty string (≤100 chars) when present"
                            }
                        }
                    }
                }
            } else {
                errors += "$at.name: required non-empty array"
            }
            present(rule["mode"])?.let { mode ->
                if (asString(mode) !in listOf("first", "join")) {
                    errors += "$at.mode: must be 'first' | 'join'"
                }
            }
        }
    }

    private fun validateAdditionalTrackedElements(raw: JsonElement?, errors: MutableList<String>) {
        val rawPresent = present(raw) ?: return
        val entries = rawPresent as? JsonArray ?: run {
            errors += "tracking.additionalTrackedElements: must be an array"
            return
        }
        if (entries.size > 20) errors += "tracking.additionalTrackedElements: max 20 entries"
        for ((i, e) in entries.withIndex()) {
            val at = "tracking.additionalTrackedElements[$i]"
            val entry = asObject(e) ?: run {
                errors += "$at: must be an object"
                null
            } ?: continue
            if (!(isNonEmptyStr(entry["selector"]) && strLen(entry["selector"]) <= 250)) {
                errors += "$at.selector: required string (1–250 chars)"
            }
            present(entry["type"])?.let { type ->
                if (!(isNonEmptyStr(type) && strLen(type) <= 50)) {
                    errors += "$at.type: must be a non-empty string (≤50 chars) when present"
                }
            }
        }
    }

    private fun validateTargetAction(raw: JsonElement, errors: MutableList<String>) {
        val ta = asObject(raw) ?: run {
            errors += "flow.targetAction: must be an object or null"
            return
        }
        when (asString(ta["type"])) {
            "url_match" -> {
                val pattern = asString(ta["pattern"])
                if (pattern == null || pattern.isEmpty() || pattern.length > 500) {
                    errors += "flow.targetAction.pattern: required string (1–500 chars)"
                    return
                }
                if (TargetMatch.isPureWildcard(pattern)) {
                    errors += "flow.targetAction.pattern: must not be wildcards only (§4.1 — would fire on the first page visited)"
                }
            }
            "event_match" -> {
                val props = asObject(ta["props"]) ?: run {
                    errors += "flow.targetAction.props: required object for event_match"
                    return
                }
                val keys = props.keys.sorted()
                if (keys.size < 1 || keys.size > 10) {
                    errors += "flow.targetAction.props: must have 1–10 keys"
                }
                var constraining = 0
                for (k in keys) {
                    if (k !in TargetMatch.matchableKeys) {
                        errors += "flow.targetAction.props: unknown key ${jsonQuote(JsonPrimitive(k))} (allowed: ${TargetMatch.matchableKeys.joinToString(", ")})"
                    } else {
                        val v = asString(props[k])
                        if (v != null && v.isNotEmpty() && v.length <= 200) {
                            if (!TargetMatch.isPureWildcard(v)) constraining += 1
                        } else {
                            errors += "flow.targetAction.props.$k: must be a non-empty string (≤200 chars)"
                        }
                    }
                }
                if (constraining == 0) {
                    errors += "flow.targetAction.props: at least one value must not be a pure wildcard (§4.2 — an all-wildcard rule fires on the first event)"
                }
            }
            // Unknown targetAction.type: ignored (forward compatibility, §4) —
            // consumers proceed as if targetAction were absent.
        }
    }

    // MARK: - Typed-config builders (inputs already validated)

    private fun buildSgRules(raw: JsonElement?): List<StudyConfig.SemanticGroupRule> {
        val rules = raw as? JsonArray ?: return emptyList()
        return rules.mapNotNull { r ->
            val rule = asObject(r) ?: return@mapNotNull null
            val container = asString(rule["container"]) ?: return@mapNotNull null
            val name = rule["name"] as? JsonArray ?: return@mapNotNull null
            StudyConfig.SemanticGroupRule(
                container = container,
                name = name.mapNotNull { src ->
                    val s = asObject(src) ?: return@mapNotNull null
                    val source = asString(s["source"]) ?: return@mapNotNull null
                    StudyConfig.SemanticGroupRule.NameSource(source = source, attribute = asString(s["attribute"]))
                },
                mode = asString(rule["mode"]),
            )
        }
    }

    private fun buildTrackedElements(raw: JsonElement?): List<StudyConfig.TrackedElement> {
        val entries = raw as? JsonArray ?: return emptyList()
        return entries.mapNotNull { e ->
            val entry = asObject(e) ?: return@mapNotNull null
            val selector = asString(entry["selector"]) ?: return@mapNotNull null
            StudyConfig.TrackedElement(selector = selector, type = asString(entry["type"]))
        }
    }

    private fun buildTargetAction(raw: JsonElement?): TargetAction? {
        val ta = asObject(raw) ?: return null
        return when (asString(ta["type"])) {
            "url_match" -> asString(ta["pattern"])?.let { TargetAction.UrlMatch(pattern = it) }
            "event_match" -> {
                val props = asObject(ta["props"]) ?: return null
                val typed = mutableMapOf<String, String>()
                for ((k, v) in props) {
                    typed[k] = asString(v) ?: return null
                }
                TargetAction.EventMatch(props = typed)
            }
            else -> null
        }
    }

    // MARK: - JS-semantics primitives

    private fun asObject(v: JsonElement?): JsonObject? = v as? JsonObject

    /// Present and not JSON null — the `!(x is NSNull)` pattern.
    private fun present(v: JsonElement?): JsonElement? = v?.takeIf { it !is JsonNull }

    private fun asString(v: JsonElement?): String? =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    /// `Number.isInteger` semantics: any integral number, but never a boolean.
    private fun asInteger(v: JsonElement?): Int? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || isBool(p)) return null
        p.content.toLongOrNull()?.let { l ->
            return if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else null
        }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d == floor(d) && !d.isInfinite() && abs(d) <= Int.MAX_VALUE.toDouble()) d.toInt() else null
    }

    private fun isBool(v: JsonElement): Boolean {
        val p = v as? JsonPrimitive ?: return false
        return !p.isString && (p.content == "true" || p.content == "false")
    }

    private fun isNonEmptyStr(v: JsonElement?): Boolean = asString(v)?.isNotEmpty() == true

    /// String length in UTF-16 code units, matching JS `.length` (Kotlin's
    /// String.length already counts UTF-16 units).
    private fun strLen(v: JsonElement?): Int = asString(v)?.length ?: 0

    private fun isHttpsUrl(v: JsonElement?): Boolean = asString(v)?.startsWith("https://") == true

    /// Bare lowercase hostname, ≥2 labels — the extension's ORIGIN_RE.
    /// `Regex.matches` is whole-string (the `\A`/`\z` anchoring), so a
    /// trailing newline can't sneak past `$`.
    private val originRegex = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    private fun isValidOrigin(s: String): Boolean = originRegex.matches(s)

    /// `JSON.stringify`-style rendering for error messages (kotlinx renders
    /// string primitives quoted+escaped already).
    private fun jsonQuote(v: JsonElement): String = v.toString()
}
