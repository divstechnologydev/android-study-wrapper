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

/// Terminal state after the kill switch (or a revoked code): friendly
/// message; dismissing clears the record (← iOS EndedStudyView in
/// RootView.swift).
@Composable
fun EndedStudyScreen(model: AppViewModel, ended: EndedStudy) {
    val revoked = ended.revoked == true
    AuthPage {
        Column(
            modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (revoked) "Study no longer available" else "This study has ended",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Brand.text,
            )
            // The extension popup's ended/attention banner colors.
            BrandNotice(
                text = if (revoked) {
                    "“${ended.name}” is no longer available. If you think this is a mistake, contact study support."
                } else {
                    "“${ended.name}” has ended — thank you for participating!"
                },
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
