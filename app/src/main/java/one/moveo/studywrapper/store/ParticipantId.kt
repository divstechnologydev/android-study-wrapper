package one.moveo.studywrapper.store

import android.content.Context
import java.util.UUID
import one.moveo.studycore.ParticipantIdStore

/// Participant identity, §a0.4: `p_<uuid>` in a dedicated SharedPreferences
/// file that is INCLUDED in Auto Backup / device-transfer rules
/// (res/xml/backup_rules.xml, data_extraction_rules.xml) — best-effort
/// reinstall survival, the Android analogue of the iOS Keychain. A fresh
/// device (or backup off) mints a new participant; that is accepted in the
/// billing definition (same posture as desktop and the iOS
/// keychain-unavailable fallback). No Google sign-in, no server-side
/// identity.
class PrefsParticipantIdStore(context: Context) : ParticipantIdStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("participant", Context.MODE_PRIVATE)

    override fun getOrCreate(): String {
        prefs.getString(KEY, null)?.takeIf { it.startsWith("p_") }?.let { return it }
        val fresh = "p_" + UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }

    private companion object {
        const val KEY = "participantId"
    }
}
