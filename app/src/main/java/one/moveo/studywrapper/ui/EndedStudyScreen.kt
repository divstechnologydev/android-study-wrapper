package one.moveo.studywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DemoStudy
import one.moveo.studycore.EndedStudy

/// Terminal state after completion (target reached / Finish study), the
/// kill switch, or a revoked code: friendly message; dismissing clears the
/// record (← iOS EndedStudyView in RootView.swift).
@Composable
fun EndedStudyScreen(model: AppViewModel, ended: EndedStudy) {
    var showResults by remember { mutableStateOf(false) }
    if (showResults) {
        DemoResultsScreen(onDismiss = { showResults = false })
        return
    }

    val isDemo = ended.code == DemoStudy.CODE
    val revoked = ended.revoked == true
    val completed = !revoked && ended.completed == true
    val title = when {
        revoked -> "Study no longer available"
        isDemo -> "Demo complete"
        completed -> "Study complete"
        else -> "This study has ended"
    }
    val message = when {
        revoked ->
            "“${ended.name}” is no longer available. If you think this is a mistake, contact study support."
        isDemo ->
            "That's the whole flow — exactly what a real study looks like, except that nothing was recorded. " +
                "When a research team invites you to a real study, your invitation link picks up from here."
        completed ->
            "You've completed “${ended.name}” — thank you for participating! " +
                "Tracking has stopped and the study has been removed from this device."
        else -> "“${ended.name}” has ended — thank you for participating!"
    }
    AuthPage {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.text,
            )
            // The extension popup's ended/attention banner colors.
            BrandNotice(
                text = message,
                background = if (revoked) Brand.infoBg else Brand.successBg,
                foreground = if (revoked) Brand.infoText else Brand.successText,
            )
            // The demo's payoff: having walked the participant flow, see what
            // the flow produces.
            if (isDemo) {
                BrandPrimaryButton(
                    text = "See sample results",
                    onClick = { showResults = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                BrandGhostButton(
                    text = "OK",
                    onClick = { model.dismissEndedStudy() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BrandPrimaryButton(
                    text = "OK",
                    onClick = { model.dismissEndedStudy() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
