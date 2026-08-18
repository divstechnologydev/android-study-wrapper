package one.moveo.studycore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/// The shared JSON codec for studycore records. `explicitNulls = false` keeps
/// absent optionals ABSENT in output (the `excludeDetailedTracking` contract);
/// `ignoreUnknownKeys` makes stored records tolerant of future fields.
val StudyCoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/// A study config as it exists AFTER validation: required fields present,
/// documented defaults applied, unknown fields dropped. Produced only by
/// `ConfigValidator`; persisted by the study store; consumed by the script
/// builder and the browser policy.
///
/// Unknown *inner* keys (e.g. extra fields inside an sg rule) are dropped by
/// the typed model. The tag sanitizes rules down to the known keys at init
/// anyway, and app + tag ship together, so this loses nothing.
@Serializable
data class StudyConfig(
    val schemaVersion: Int,
    val study: Study,
    val tracking: Tracking,
    val flow: Flow,
) {
    @Serializable
    data class Study(
        val id: String,
        val name: String,
        val status: Status,
    )

    /// Backend computes effective status at request time; anything non-active
    /// means deactivate. The validator only admits these two values.
    @Serializable
    enum class Status {
        @SerialName("active") ACTIVE,
        @SerialName("ended") ENDED;

        companion object {
            fun fromRaw(raw: String): Status? = when (raw) {
                "active" -> ACTIVE
                "ended" -> ENDED
                else -> null
            }
        }
    }

    @Serializable
    data class Tracking(
        val token: String,
        val apiUrl: String,
        val origins: List<String>,
        val deploymentType: String, // "STATIC_WEBSITE" (default) | "WEB_APP"
        val appVersion: String? = null,
        val storageSource: String, // "local" (default) | "session"
        val userDataKeys: List<String>,
        /// Absent means "let the tag pick its per-deployment-type default" —
        /// null must stay absent in the injected payload, never become false.
        val excludeDetailedTracking: Boolean? = null,
        val semanticGroupRules: List<SemanticGroupRule>,
        val additionalTrackedElements: List<TrackedElement>,
    )

    @Serializable
    data class SemanticGroupRule(
        val container: String,
        val name: List<NameSource>,
        val mode: String? = null, // "first" | "join" when present
    ) {
        @Serializable
        data class NameSource(
            val source: String, // "self" = the container itself
            val attribute: String? = null,
        )
    }

    @Serializable
    data class TrackedElement(
        val selector: String,
        val type: String? = null,
    )

    @Serializable
    data class Flow(
        val leadInUrl: String? = null,
        val leadOutUrl: String? = null,
        /// Optional path on the study origin the browser opens on instead of
        /// the origin root (e.g. "/nectar/all-products"). Absent/empty ⇒
        /// open the root, the pre-existing behavior.
        val prepositionPath: String? = null,
        val appendParticipantId: Boolean = false, // default false
        val targetAction: TargetAction? = null,
    )

    /// Where the study browser starts: the first tracked origin, at
    /// `flow.prepositionPath` when the study declares one, else the root.
    /// A missing leading "/" is tolerated (config authors will forget it).
    val startUrl: String?
        get() {
            val origin = tracking.origins.firstOrNull() ?: return null
            var path = flow.prepositionPath ?: "/"
            if (path.isEmpty()) path = "/"
            if (!path.startsWith("/")) path = "/$path"
            return "https://$origin$path"
        }
}

/// A validated target action. Unknown types never reach here — the validator
/// normalizes them to null ("proceed as if absent", forward compatibility).
@Serializable(with = TargetActionSerializer::class)
sealed class TargetAction {
    /// Substring/`*`-wildcard pattern matched against the full URL.
    data class UrlMatch(val pattern: String) : TargetAction()

    /// AND-matched property bag over the closed key set eA/eT/eID/eV/sg/sc.
    data class EventMatch(val props: Map<String, String>) : TargetAction()
}

/// Encodes as `{"type":"url_match","pattern":…}` / `{"type":"event_match",
/// "props":{…}}` — the same wire shape as the config JSON and iOS Codable.
object TargetActionSerializer : KSerializer<TargetAction> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("one.moveo.studycore.TargetAction")

    override fun serialize(encoder: Encoder, value: TargetAction) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("TargetAction supports JSON only")
        val obj = when (value) {
            is TargetAction.UrlMatch -> buildJsonObject {
                put("type", "url_match")
                put("pattern", value.pattern)
            }
            is TargetAction.EventMatch -> buildJsonObject {
                put("type", "event_match")
                put("props", JsonObject(value.props.mapValues { JsonPrimitive(it.value) }))
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): TargetAction {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("TargetAction supports JSON only")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return when (val type = obj["type"]?.jsonPrimitive?.content) {
            "url_match" -> TargetAction.UrlMatch(
                pattern = obj["pattern"]?.jsonPrimitive?.content
                    ?: throw SerializationException("url_match without pattern"),
            )
            "event_match" -> TargetAction.EventMatch(
                props = obj["props"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
                    ?: throw SerializationException("event_match without props"),
            )
            else -> throw SerializationException(
                "unknown targetAction type $type — the validator should have dropped it"
            )
        }
    }
}
