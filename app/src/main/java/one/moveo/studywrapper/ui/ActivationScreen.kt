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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.R
import one.moveo.studycore.Codes

/// Screen 1 of 3: code entry → fetch → validate → confirmation sheet
/// (← iOS ActivationView.swift). Consumes typed/pasted codes and codes
/// arriving via setup links. Composition mirrors the extension's auth pages
/// (brand kit v3): faint constellation background, centered wordmark, one
/// elevated card.
@Composable
fun ActivationScreen(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    val pending by model.pendingConfirmation.collectAsState()

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

    pending?.let { StudySummarySheet(model, it) }
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

@Composable
private fun EntryCard(model: AppViewModel) {
    val phase by model.phase.collectAsState()
    val codeInput by model.codeInput.collectAsState()
    val fetching = phase is AppViewModel.Phase.Fetching
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandEyebrow("Join research study")
        Text(
            text = "Taking part in a Moveo One research study? Open the setup link from your study invitation, or enter the code you received.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = Brand.textSecondary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            BrandEyebrow("Setup code")
            BasicTextField(
                value = codeInput,
                onValueChange = { model.codeInput.value = it },
                enabled = !fetching,
                singleLine = true,
                interactionSource = interaction,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.text,
                ),
                cursorBrush = SolidColor(Brand.text),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { model.activate() }),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(Brand.radiusSM))
                            .background(if (focused) Brand.bgElevated else Brand.bgSunken)
                            .border(
                                1.dp,
                                if (focused) Brand.text else Brand.border,
                                RoundedCornerShape(Brand.radiusSM),
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (codeInput.isEmpty()) {
                            Text(
                                "e.g. 4831",
                                fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Brand.textMuted,
                            )
                        }
                        inner()
                    }
                },
            )
            Codes.normalize(codeInput)?.let { normalized ->
                Text(
                    text = Codes.group(normalized),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textMuted,
                )
            }
        }

        BrandPrimaryButton(
            onClick = { model.activate() },
            enabled = Codes.normalize(codeInput) != null && !fetching,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { fg ->
            if (fetching) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
            } else {
                Text("Activate", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
            }
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

/// The activation confirmation: what the study is, which sites it covers
/// (wording derives from the same origin list the guard + policy enforce),
/// and the explicit replacement warning when another study is active.
/// (Android idiom: bottom sheet instead of the iOS page sheet; swipe-dismiss
/// acts as Cancel — same invariant, nothing stored.)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySummarySheet(model: AppViewModel, pending: AppViewModel.PendingActivation) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { model.cancelActivation() },
        sheetState = sheetState,
        containerColor = Brand.bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header row: Cancel / title / Continue (the iOS nav bar).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandGhostButton(text = "Cancel", onClick = { model.cancelActivation() })
                Spacer(Modifier.weight(1f))
                Text("Join study", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
                Spacer(Modifier.weight(1f))
                BrandGhostButton(text = "Continue", onClick = { model.confirmActivation() })
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BrandEyebrow("Study")
                Text(
                    pending.config.study.name,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.text,
                )
                Text(
                    "Code ${Codes.group(pending.code)}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Brand.textMuted,
                )
            }

            pending.replacingName?.let { replacing ->
                BrandNotice(
                    text = "Continuing will replace your current study “$replacing” — its tracking stops.",
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                BrandEyebrow("Websites this study tracks")
                OriginChips(origins = pending.config.tracking.origins)
                Text(
                    text = "Tracking only happens on these websites (including their subdomains), inside this app, and only after you give consent on the next screen.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Brand.textSecondary,
                )
            }
        }
    }
}

