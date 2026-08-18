package one.moveo.studywrapper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.R
import one.moveo.studycore.BackendConstants

/// Screen 2 of 3: the consent form (← iOS ConsentView.swift). Wording ported
/// from the extension's `consent/consent.html` with device-appropriate edits
/// ("this app", the in-app browser explanation) — a0.5. ANY change to
/// visible wording here must bump `ConsentConstants.TEXT_VERSION`: that
/// string is stored with the enrollment as the GDPR audit record of exactly
/// what the participant saw.
///
/// Layout mirrors the extension's consent tab (brand kit v3): constellation
/// background, centered wordmark, one elevated card, eyebrow section heads.
@Composable
fun ConsentScreen(model: AppViewModel, pending: AppViewModel.PendingActivation) {
    val enrolling by model.enrolling.collectAsState()
    val consentError by model.consentError.collectAsState()

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
                modifier = Modifier.padding(top = 32.dp).height(24.dp),
            )
            Spacer(Modifier.height(28.dp))
            Box(modifier = Modifier.widthIn(max = 560.dp)) {
                ConsentCard(model, pending, consentError)
            }
            // Keep the card clear of the pinned action bar.
            Spacer(Modifier.height(96.dp))
        }

        // The pinned action bar (the iOS safeAreaInset bottom bar).
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            HorizontalDivider(color = Brand.border, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brand.bgElevated.copy(alpha = 0.92f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                BrandGhostButton(
                    text = "Decline",
                    onClick = { model.declineConsent() },
                    enabled = !enrolling,
                )
                BrandPrimaryButton(
                    onClick = { model.acceptConsent() },
                    enabled = !enrolling,
                    modifier = Modifier.widthIn(min = 150.dp),
                ) { fg ->
                    if (enrolling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), color = fg, strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Accept & continue", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(
    model: AppViewModel,
    pending: AppViewModel.PendingActivation,
    consentError: String?,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier.fillMaxWidth().brandCard().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrandEyebrow("Join research study")
        Text(
            pending.config.study.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Brand.text,
        )

        pending.replacingName?.let { replacing ->
            BrandNotice(
                text = "Accepting will replace your current study “$replacing” — its tracking stops.",
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        SectionTitle("Websites this study tracks")
        Explain("The study runs inside this app's built-in browser, and only on these websites (including their subdomains):")
        OriginChips(
            origins = pending.config.tracking.origins,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionTitle("What is collected")
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Bullet("Your interactions on the study websites — taps, scrolling, typing activity (never the text you type), and how long you stay on each page")
            Bullet("Pages you view on the study websites")
            Bullet("Basic technical context: device type, screen size, language")
        }
        Explain(
            "Nothing is collected on any other website, and nothing is collected outside this app. No passwords, payment details, or typed text are ever recorded.",
            modifier = Modifier.padding(top = 6.dp),
        )

        SectionTitle("Who receives the data")
        Explain("Moveo One processes the data and shares study results with the research team running this study. You take part under a random participant ID — the study team does not receive your name or email.")
        Text(
            text = "Privacy policy",
            fontSize = 14.sp,
            color = Brand.link,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable { uriHandler.openUri(BackendConstants.PRIVACY_POLICY_URL) },
        )

        consentError?.let { error ->
            BrandNotice(
                text = error,
                background = Brand.dangerBg,
                foreground = Brand.dangerText,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Text(
            text = "You can leave the study at any time from the study screen.",
            fontSize = 12.sp,
            color = Brand.textMuted,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    BrandEyebrow(text, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
}

@Composable
private fun Explain(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Brand.textSecondary,
        modifier = modifier,
    )
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", fontSize = 14.sp, color = Brand.textMuted)
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Brand.textSecondary)
    }
}
