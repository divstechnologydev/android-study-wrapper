package one.moveo.studywrapper.store

import android.content.Context
import one.moveo.studycore.KeyValueStore

/// SharedPreferences implementation of studycore's `KeyValueStore` SPI —
/// activeStudy / consent / endedStudy / ownTagHosts / apiBaseOverride all
/// live here (the UserDefaults analogue). Included in Auto Backup rules
/// (res/xml/backup_rules.xml) like iOS defaults are in iCloud backups.
///
/// The backup rules are include-lists, so any other `name` (the per-install
/// file, `INSTALL_FILE`) is NOT backed up or transferred.
class PrefsKeyValueStore(context: Context, name: String = STUDY_FILE) : KeyValueStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    companion object {
        const val STUDY_FILE = "studywrapper"

        /// Per-install facts (the deferred-link flag): must start empty on
        /// every install, including one restored from a backup.
        const val INSTALL_FILE = "install"
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
