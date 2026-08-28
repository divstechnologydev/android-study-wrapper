package one.moveo.studywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studycore.EndedStudy

/// Terminal state after completion (target reached / Finish study), the
/// kill switch, or a revoked code: friendly message; dismissing clears the
/// record (← iOS EndedStudyView in RootView.swift).
@Composable
fun EndedStudyScreen(model: AppViewModel, ended: EndedStudy) {
    val revoked = ended.revoked == true
    val completed = !revoked && ended.completed == true
    val title = when {
        revoked -> "Study no longer available"
        completed -> "Study complete"
        else -> "This study has ended"
    }
    val message = when {
        revoked ->
            "“${ended.name}” is no longer available. If you think this is a mistake, contact study support."
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
            BrandPrimaryButton(
                text = "OK",
                onClick = { model.dismissEndedStudy() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
