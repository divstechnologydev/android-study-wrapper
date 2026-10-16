package one.moveo.studywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studycore.BackendConstants
import one.moveo.studycore.SupportConstants
import one.moveo.studywrapper.BuildConfig

/// The app's standing explainer — reachable with no invitation, no study and
/// no network (← iOS HowItWorksView.swift). It answers the questions a
/// participant actually arrives with (what is this, what gets recorded, how
/// do I stop, how do I get my data deleted) and mirrors the public support
/// page at `SupportConstants.SUPPORT_URL`, so the two never diverge in
/// substance.
///
/// Deliberately not a consent screen: nothing here is part of the GDPR audit
/// record, so editing this copy does NOT require a `ConsentConstants`
/// TEXT_VERSION bump. Keep it that way — consent wording lives in
/// ConsentScreen.
@Composable
fun HowItWorksScreen(
    onDismiss: () -> Unit,
    /// Offered only where starting a study makes sense (the entry screen) —
    /// not while a study is already running.
    onStartDemo: (() -> Unit)? = null,
) {
    OverlayScreen(title = "How it works", actionLabel = "Done", onAction = onDismiss) {
        InfoCard("What this app is") {
            CardBody("Moveo One User Research is the app you use to take part in a research study you've been invited to. A research team sends you an invitation, the app shows you exactly what will and won't be recorded, and you browse the study website as you normally would.")
            CardBody("You don't need an account, and there is nothing to set up. If you haven't been invited to a study, you can still see the whole thing end to end by running the demo on any website you like — it records nothing.")
            onStartDemo?.let { startDemo ->
                BrandPrimaryButton(
                    text = "Try the demo study",
                    onClick = {
                        onDismiss()
                        startDemo()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }

        AdaptiveColumns(
            left = {
                InfoCard("Taking part, step by step") {
                    Step(
                        1, "Open your invitation",
                        "Tap the setup link from your invitation, or type in the study code you were given. The app loads the study automatically.",
                    )
                    Step(
                        2, "Read and accept consent",
                        "You'll see the study name, every website the study covers, and precisely what is and isn't recorded. Nothing at all is collected until you accept — and declining costs you nothing.",
                    )
                    Step(
                        3, "Read your tasks",
                        "The research team's instructions appear on the study screen. Some studies have specific tasks; others just ask you to browse.",
                    )
                    Step(
                        4, "Browse, then finish",
                        "Tap Open study browser and use the website the way you normally would. That's the whole task. Tap Done when you're finished.",
                    )
                }
            },
            right = {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    InfoCard("What is collected") {
                        CardBody("Only while a study is active, and only on the websites named on that study's consent screen:")
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CardBullet("How you interact with the pages — taps, scrolling, typing activity (never the text you type), and how long you stay on each page")
                            CardBullet("Which pages you view on the study websites")
                            CardBullet("Basic technical context: device type, screen size, language")
                        }
                    }
                    InfoCard("What is never collected") {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CardBullet("Nothing on any other website")
                            CardBullet("Nothing outside this app — your other apps and your normal browser are untouched")
                            CardBullet("No passwords, no payment details, and none of the text you type")
                            CardBullet("Nothing at all before you accept consent, and nothing after a study ends")
                        }
                        BrandNotice(
                            text = "The study runs inside this app's own browser and is limited to the websites listed on the consent screen. The app has no way to see anything else.",
                            background = Brand.successBg,
                            foreground = Brand.successText,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
        )

        AdaptiveColumns(
            left = {
                InfoCard("Your choices") {
                    Labelled(
                        "Stopping at any time",
                        "Tap Finish study in the app whenever you like. You don't have to complete the tasks and you don't need to give a reason — collection stops immediately. Leave study, in the study menu, also clears the study and your logins on the study websites from this device.",
                    )
                    Labelled(
                        "How you're identified",
                        "You take part under a random participant ID. The research team receives study results under that ID — not your name and not your email address.",
                    )
                    Labelled(
                        "Getting your data deleted",
                        "Email us with the study code shown on the study screen and roughly when you took part. That's enough to find and remove your session. Because we don't hold your name alongside study data, the code is how we identify it.",
                    )
                    LinkText("Privacy policy", BackendConstants.PRIVACY_POLICY_URL)
                }
            },
            right = {
                InfoCard("Getting help") {
                    CardBody("If your question is about a particular study — what it's for, whether you'll be paid, when it closes — the research team who invited you is the faster route. For anything about the app itself:")
                    LinkText("Support page", SupportConstants.SUPPORT_URL)
                    LinkText("Email ${SupportConstants.SUPPORT_EMAIL}", SupportConstants.SUPPORT_MAILTO)
                }
            },
        )

        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Brand.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Step(number: Int, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(26.dp).background(Brand.bgSunken, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Brand.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
            CardBody(text)
        }
    }
}

@Composable
private fun Labelled(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Brand.text)
        CardBody(text)
    }
}
