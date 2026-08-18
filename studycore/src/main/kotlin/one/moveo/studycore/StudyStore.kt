package one.moveo.studycore

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/// Minimal string key-value SPI — keeps `:studycore` JVM-pure. The `:app`
/// module provides the SharedPreferences implementation
/// (`PrefsKeyValueStore`); tests use `InMemoryKeyValueStore`.
interface KeyValueStore {
    fun getString(key: String): String?

    /// `null` removes the key.
    fun putString(key: String, value: String?)
}

/// Test double / preview store.
class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/// Stable participant identity. Interface so tests stay in memory; the app
/// uses the Auto-Backup-included SharedPreferences implementation (§a0.4 —
/// best-effort reinstall survival, the Android analogue of the iOS keychain).
interface ParticipantIdStore {
    /// Always returns an id; generates and persists `p_<uuid>` on first call.
    fun getOrCreate(): String
}

/// Test double — also usable for Compose previews.
class InMemoryParticipantIdStore(private var id: String? = null) : ParticipantIdStore {
    override fun getOrCreate(): String {
        id?.let { return it }
        val fresh = "p_" + UUID.randomUUID().toString().lowercase()
        id = fresh
        return fresh
    }
}

/// Typed accessors over the key-value store — the single source of truth for
/// all app state, mirroring the extension's `src/storage.js` layout:
///
///   participant store: participantId — backed-up prefs, best-effort
///                      reinstall survival (a fully fresh device is a new
///                      participant, accepted in the billing definition same
///                      as desktop and the iOS keychain-unavailable fallback)
///   key-value store:   activeStudy     — ActiveStudy record
///                      consent         — ConsentRecord
///                      endedStudy      — EndedStudy
///                      ownTagHosts     — {hostname: detectedAt} (yield map)
///                      apiBaseOverride — String (DEBUG/mock only)
class StudyStore(
    private val store: KeyValueStore,
    private val participantIdStore: ParticipantIdStore,
) {
    private object Key {
        const val ACTIVE_STUDY = "activeStudy"
        const val CONSENT = "consent"
        const val ENDED_STUDY = "endedStudy"
        const val OWN_TAG_HOSTS = "ownTagHosts"
        const val API_BASE_OVERRIDE = "apiBaseOverride"
    }

    private val ownTagHostsSerializer = MapSerializer(String.serializer(), InstantIso8601Serializer)

    // MARK: - Records (getters tolerate empty/corrupt storage by returning null)

    var activeStudy: ActiveStudy?
        get() = read(Key.ACTIVE_STUDY, ActiveStudy.serializer())
        set(value) = write(Key.ACTIVE_STUDY, value, ActiveStudy.serializer())

    var consent: ConsentRecord?
        get() = read(Key.CONSENT, ConsentRecord.serializer())
        set(value) = write(Key.CONSENT, value, ConsentRecord.serializer())

    var endedStudy: EndedStudy?
        get() = read(Key.ENDED_STUDY, EndedStudy.serializer())
        set(value) = write(Key.ENDED_STUDY, value, EndedStudy.serializer())

    /// Hosts where a site-own Moveo tag was detected — the app yields there
    /// from the next page load on (double-injection rule).
    var ownTagHosts: Map<String, Instant>
        get() = read(Key.OWN_TAG_HOSTS, ownTagHostsSerializer) ?: emptyMap()
        set(value) = write(Key.OWN_TAG_HOSTS, value, ownTagHostsSerializer)

    /// Dev/mock-backend override for the config API base. Read by DEBUG
    /// builds only; release builds never look at it.
    var apiBaseOverride: String?
        get() = store.getString(Key.API_BASE_OVERRIDE)
        set(value) = store.putString(Key.API_BASE_OVERRIDE, value)

    fun participantId(): String = participantIdStore.getOrCreate()

    private fun <T : Any> read(key: String, serializer: KSerializer<T>): T? {
        val raw = store.getString(key) ?: return null
        return try {
            StudyCoreJson.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun <T : Any> write(key: String, value: T?, serializer: KSerializer<T>) {
        if (value != null) {
            store.putString(key, StudyCoreJson.encodeToString(serializer, value))
        } else {
            store.putString(key, null)
        }
    }
}
