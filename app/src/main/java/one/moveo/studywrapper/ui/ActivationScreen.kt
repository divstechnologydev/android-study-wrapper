package one.moveo.studywrapper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.R

/// Screen 1 of 3: setup link → fetch → validate → consent (no intermediate
/// summary sheet — the consent page carries the study name, tracked
/// websites and the replacement notice, exactly like the extension's link
/// flow; removed 2026-08-28 with iOS). (← iOS ActivationView.swift.)
/// Links only for now: the extension popup's "Link not working? Enter the
/// code manually" fallback is deliberately NOT shown (product decision
/// 2026-08-27) — a typed code cannot carry the panel provider's transaction
/// id, so participants are steered to the link. The model still accepts a
/// code/link in `codeInput` (deep links, DEBUG MOVEO_AUTO_CODE), so
/// re-adding the field later is UI-only. Composition mirrors the
/// extension's auth pages (brand kit v3): faint constellation background,
/// centered wordmark, one elevated card.
@Composable
fun ActivationScreen(model: AppViewModel) {
    val phase by model.phase.collectAsState()

    when (val p = phase) {
        is AppViewModel.Phase.Idle, is AppViewModel.Phase.Fetching ->
            AuthPage { EntryCard(model) }
        is AppViewModel.Phase.Failed ->
            AuthPage {
                StatusCard(
                    title = p.title, message = p.message,
                    noticeBg = Brand.dangerBg, noticeFg = Brand.dangerText,
                    buttonLabel = "Try again",
                ) { model.backToEntry() }
            }
        is AppViewModel.Phase.StudyEnded ->
            AuthPage {
                StatusCard(
                    title = "This study has finished",
                    message = "“${p.name}” is no longer running. Thank you for your interest.",
                    noticeBg = Brand.successBg, noticeFg = Brand.successText,
                    buttonLabel = "OK",
                ) { model.backToEntry() }
            }
        is AppViewModel.Phase.Consent ->
            ConsentScreen(model, p.pending)
    }
}

/// Constellation background + wordmark + card — the extension's
/// consent/landing page scaffold.
@Composable
fun AuthPage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AuthBackground()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_wordmark),
                contentDescription = "Moveo One",
                modifier = Modifier.padding(top = 48.dp).height(24.dp),
            )
            Spacer(Modifier.height(28.dp))
            Box(modifier = Modifier.widthIn(max = 560.dp)) {
                content()
            }
        }
    }
}

/// Link-only entry card (popup.html `.explainer`, without the manual
/// disclosure). While a link is being resolved it shows progress so a tap
/// on a slow network doesn't look like nothing happened.
@Composable
private fun EntryCard(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    val fetching = phase is AppViewModel.Phase.Fetching

    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandEyebrow("Join research study")
        Text(
            text = buildAnnotatedString {
                append("Taking part in a Moveo One research study? Open the ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Brand.text)) {
                    append("setup link")
                }
                append(" from your study invitation — it sets everything up automatically.")
            },
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Brand.textSecondary,
        )

        if (fetching) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Brand.text, strokeWidth = 2.dp)
                Text("Loading your study…", fontSize = 13.sp, color = Brand.textSecondary)
            }
        } else {
            Text(
                "Don't have a link? Ask the study team for your invitation.",
                fontSize = 12.sp,
                color = Brand.textMuted,
            )
        }
    }
}

@Composable
fun StatusCard(
    title: String,
    message: String,
    noticeBg: Color,
    noticeFg: Color,
    buttonLabel: String,
    action: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
        BrandNotice(text = message, background = noticeBg, foreground = noticeFg)
        BrandPrimaryButton(text = buttonLabel, onClick = action, modifier = Modifier.fillMaxWidth())
    }
}
