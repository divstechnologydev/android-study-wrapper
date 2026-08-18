package one.moveo.studywrapper.store

import android.content.Context
import one.moveo.studycore.KeyValueStore

/// SharedPreferences implementation of studycore's `KeyValueStore` SPI —
/// activeStudy / consent / endedStudy / ownTagHosts / apiBaseOverride all
/// live here (the UserDefaults analogue). Included in Auto Backup rules
/// (res/xml/backup_rules.xml) like iOS defaults are in iCloud backups.
class PrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("studywrapper", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
