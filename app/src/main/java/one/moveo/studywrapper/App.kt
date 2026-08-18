package one.moveo.studywrapper

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.util.Log
import one.moveo.studycore.StudyStore
import one.moveo.studywrapper.store.PrefsKeyValueStore
import one.moveo.studywrapper.store.PrefsParticipantIdStore

/// Application entry point. Owns the app-scoped state machine (the iOS
/// `@StateObject` in MoveoStudyWrapperApp) and hosts the
/// ProcessLifecycleOwner hook that drives study re-validation on every
/// app-foreground (docs/plan.md §a2.6) — refresh config, honor the kill
/// switch, recover pending lead-outs. Also fires once at launch.
class App : Application() {

    val model: AppViewModel by lazy {
        AppViewModel(
            store = StudyStore(
                store = PrefsKeyValueStore(this),
                participantIdStore = PrefsParticipantIdStore(this),
            ),
            appVersion = BuildConfig.VERSION_NAME,
            isDebugBuild = BuildConfig.DEBUG,
            // Method/path/status only — never bodies or tokens; and only in
            // debug builds (the iOS #if DEBUG logger).
            debugLog = if (BuildConfig.DEBUG) {
                { line -> Log.i("moveo-backend", line) }
            } else null,
        )
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                model.revalidate()
            }
        })
    }
}
