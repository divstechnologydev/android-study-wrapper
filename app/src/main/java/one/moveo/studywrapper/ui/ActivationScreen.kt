package one.moveo.studywrapper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.R
import one.moveo.studycore.Codes
import one.moveo.studycore.SetupLink

/// Screen 1 of 3: setup link → fetch → validate → consent (no intermediate
/// summary sheet — the consent page carries the study name, tracked websites
/// and the replacement notice, exactly like the extension's link flow;
/// removed 2026-08-28 with iOS). (← iOS ActivationView.swift.)
///
/// The link is still the steered path (only a link carries the panel
/// provider's transaction id), but the extension popup's "Link not working?
/// Enter the code manually" fallback is shown again for testing: a typed code
/// takes the same route as a link, so `3VQW` activates exactly like
/// https://app.moveo.one/extension/config/3VQW. A pasted full link is accepted
/// here too and keeps its transaction id (`AppViewModel.resolveInput`).
/// Everything reachable without an invitation — the demo and the standing
/// explainer — sits in a second card beside it.
///
/// Composition mirrors the extension's auth pages (brand kit v3): faint
/// constellation background, centered wordmark, elevated cards.
@Composable
fun ActivationScreen(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    var overlay by remember { mutableStateOf<Overlay?>(null) }

    // Scripted QA / store-screenshot capture (debug builds only — release
    // never sets this): land straight on one of the destinations below.
    LaunchedEffect(Unit) {
        overlay = when (model.qaAutoSheet) {
            "howitworks" -> Overlay.HOW_IT_WORKS
            "demo" -> Overlay.DEMO_SETUP
            "results" -> Overlay.DEMO_RESULTS
            else -> null
        }
    }

    overlay?.let { shown ->
        when (shown) {
            Overlay.HOW_IT_WORKS -> HowItWorksScreen(
                onDismiss = { overlay = null },
                onStartDemo = { overlay = Overlay.DEMO_SETUP },
            )
            Overlay.DEMO_SETUP -> DemoSetupScreen(
                model = model,
                onDismiss = { overlay = null },
                onStart = { site -> model.startDemo(site) },
            )
            Overlay.DEMO_RESULTS -> DemoResultsScreen(onDismiss = { overlay = null })
        }
        return
    }

    when (val p = phase) {
        is AppViewModel.Phase.Idle, is AppViewModel.Phase.Fetching ->
            AuthPage {
                // The second card is hidden mid-activation: another "start
                // something" affordance while a study is loading is a trap.
                if (p is AppViewModel.Phase.Fetching) {
                    EntryCard(model)
                } else {
                    AdaptiveColumns(
                        left = { EntryCard(model) },
                        right = { NoInvitationCard(onOverlay = { overlay = it }) },
                    )
                }
            }
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

/// The full-screen destinations reachable from the activation and study
/// screens (the iOS sheets / full-screen covers).
enum class Overlay { HOW_IT_WORKS, DEMO_SETUP, DEMO_RESULTS }

/// Constellation background + wordmark + card — the extension's
/// consent/landing page scaffold.
@Composable
fun AuthPage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        AuthBackground()
        CenteringScrollColumn(horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .readingColumn(regular = 900.dp)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_wordmark),
                    contentDescription = "Moveo One",
                    modifier = Modifier.padding(top = 48.dp).height(24.dp),
                )
                Spacer(Modifier.height(28.dp))
                content()
            }
        }
    }
}

/// Entry card (popup.html `.explainer` + the manual-code disclosure). While a
/// link or code is being resolved it shows progress so a tap on a slow network
/// doesn't look like nothing happened.
@Composable
private fun EntryCard(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    val codeInput by model.codeInput.collectAsState()
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
            ManualCodeEntry(model, codeInput)
        }
    }
}

/// Manual fallback: a bare invitation code (or a pasted setup link) runs the
/// identical activation path a link takes.
@Composable
private fun ManualCodeEntry(model: AppViewModel, codeInput: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    // Mirrors `AppViewModel.resolveInput()`: a normalizable code or a setup
    // link.
    val canSubmit = remember(codeInput) {
        val trimmed = codeInput.trim()
        SetupLink.parse(trimmed) != null || Codes.normalize(trimmed) != null
    }

    fun submit() {
        if (!canSubmit) return
        keyboard?.hide()
        model.activate()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Brand.border, thickness = 1.dp, modifier = Modifier.padding(vertical = 2.dp))

        Text(
            "Link not working? Enter the code from your invitation.",
            fontSize = 12.sp,
            color = Brand.textMuted,
        )

        BasicTextField(
            value = codeInput,
            onValueChange = { model.codeInput.value = it },
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = Brand.text,
            ),
            cursorBrush = SolidColor(Brand.text),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(Brand.radiusMD))
                        .background(Brand.bgSunken)
                        .border(1.dp, if (focused) Brand.text else Brand.border, RoundedCornerShape(Brand.radiusMD))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (codeInput.isEmpty()) {
                        Text(
                            "Study code",
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Brand.textMuted,
                        )
                    }
                    inner()
                }
            },
        )

        BrandPrimaryButton(
            text = "Continue",
            onClick = { submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/// Everything reachable without an invitation. A card of its own so it reads
/// as a second, optional path rather than part of joining a study.
@Composable
private fun NoInvitationCard(onOverlay: (Overlay) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandEyebrow("No invitation?")
        Text(
            "You can still see exactly how a study works. The demo walks through the whole flow — consent, tasks, and the study browser — on any website you choose, and records nothing.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Brand.textSecondary,
        )
        BrandPrimaryButton(
            text = "Try a demo study",
            onClick = { onOverlay(Overlay.DEMO_SETUP) },
            modifier = Modifier.fillMaxWidth(),
        )
        BrandGhostButton(
            text = "How it works",
            onClick = { onOverlay(Overlay.HOW_IT_WORKS) },
            modifier = Modifier.fillMaxWidth(),
        )
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
