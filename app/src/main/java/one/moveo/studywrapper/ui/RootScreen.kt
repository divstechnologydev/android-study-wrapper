package one.moveo.studywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DebugHooks

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

    Box(modifier = Modifier.fillMaxSize()) {
        val study = activeStudy
        val ended = endedStudy
        when {
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
        DebugHooks.DebugGearOverlay(model)
    }
}

/// Interim home surface — the full StudyHomeScreen (live-tracking status,
/// open-browser, leave-study) lands in M3. Reaching it requires a completed
/// enroll, which the M3 consent screen gates.
@Composable
private fun StudyHomeScreen(model: AppViewModel, study: one.moveo.studycore.ActiveStudy) {
    AuthPage {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandEyebrow("Active study")
            Text(
                study.config.study.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.text,
            )
            BrandNotice(
                text = "The study home screen arrives in the next milestone (M3).",
                background = Brand.infoBg,
                foreground = Brand.infoText,
            )
            BrandGhostButton(
                text = "Leave study",
                role = GhostRole.DANGER,
                onClick = { model.leaveStudy() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
