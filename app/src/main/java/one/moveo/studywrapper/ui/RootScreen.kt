package one.moveo.studywrapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DebugHooks
import one.moveo.studywrapper.browser.LeadSurveyLauncher

/// Root routing (← iOS RootView.swift): activeStudy+idle → StudyHome;
/// endedStudy → EndedStudy (revoked vs ended wording); else Activation.
/// Home shows only when nothing is in flight: a deep-link replacement (new
/// code while a study is active) must be able to run its confirm → consent
/// flow, which ActivationScreen owns.
@Composable
fun RootScreen(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    val pending by model.pendingConfirmation.collectAsState()
    val activeStudy by model.activeStudy.collectAsState()
    val endedStudy by model.endedStudy.collectAsState()
    val browserPresented by model.browserPresented.collectAsState()
    val leadSheet by model.leadSheet.collectAsState()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        val study = activeStudy
        val ended = endedStudy
        when {
            // Full-screen browser cover (the iOS fullScreenCover).
            browserPresented && study != null ->
                StudyBrowserScreen(model, study)
            study != null && phase is AppViewModel.Phase.Idle && pending == null ->
                StudyHomeScreen(model, study)
            study == null && ended != null && phase is AppViewModel.Phase.Idle && pending == null ->
                EndedStudyScreen(model, ended)
            else ->
                ActivationScreen(model)
        }
        // Debug builds overlay a gear (the iOS nav-bar toolbar item); the
        // release source set makes this a no-op — the QA surface does not
        // exist in the release binary (§a2.7).
        if (!browserPresented) {
            DebugHooks.DebugGearOverlay(model)
        }
    }

    // Lead surveys launch in a Custom Tab wherever the app is (browser up or
    // a crash-recovered lead-out on foreground) — the iOS root/browser sheet
    // pair collapses to one launcher on Android.
    LaunchedEffect(leadSheet) {
        leadSheet?.let { sheet ->
            val launched = LeadSurveyLauncher.launch(context, sheet.url)
            model.leadSheetLaunched(sheet, launched)
        }
    }
}
