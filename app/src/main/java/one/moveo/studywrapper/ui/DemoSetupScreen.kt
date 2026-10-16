package one.moveo.studywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.moveo.studycore.SiteInput
import one.moveo.studywrapper.AppViewModel
import one.moveo.studywrapper.DemoStudy

/// Site picker for the demo: the participant says which website to run it on,
/// and the demo builds a study scoped to that one origin (← iOS
/// DemoSetupView.swift).
///
/// Letting people point the demo at their own site is the whole value of it —
/// a research team evaluating Moveo One sees the participant experience on
/// the pages they actually care about. It stays a preview: nothing is
/// recorded on any site, ever (see `DemoStudy` for why that is structural),
/// and the study browser remains scoped to the single origin entered here, so
/// it does not become a general-purpose browser.
@Composable
fun DemoSetupScreen(
    model: AppViewModel,
    onDismiss: () -> Unit,
    onStart: (SiteInput.Parsed) -> Unit,
) {
    // Remembered across runs so a second demo doesn't mean retyping the site.
    var site by remember { mutableStateOf(model.store.demoSite ?: DemoStudy.DEFAULT_SITE) }
    val parsed = remember(site) { SiteInput.parse(site) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun start() {
        val resolved = parsed ?: return
        keyboard?.hide()
        model.store.demoSite = site.trim()
        onDismiss()
        onStart(resolved)
    }

    OverlayScreen(title = "Demo study", actionLabel = "Cancel", onAction = onDismiss) {
        AdaptiveColumns(
            left = {
                InfoCard("Which website?") {
                    CardBody("The demo runs the full participant flow — consent, tasks, and the study browser — on a website you choose. Try your own.")

                    SiteField(
                        value = site,
                        onValueChange = { site = it },
                        onGo = { start() },
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    // Shows what the typed text actually resolves to before it
                    // becomes the study — "www." dropped, a path kept as the
                    // starting page.
                    if (parsed != null) {
                        Text(
                            text = "Demo will run on ${parsed.origin}" +
                                (parsed.path?.let { ", starting at $it" } ?: ""),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Brand.textMuted,
                        )
                    } else if (site.isNotBlank()) {
                        Text(
                            text = "Enter a website address like example.com, or a full link to a page.",
                            fontSize = 12.sp,
                            color = Brand.dangerText,
                        )
                    }

                    BrandPrimaryButton(
                        text = "Start demo",
                        onClick = { start() },
                        enabled = parsed != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            },
            right = {
                InfoCard("What the demo does") {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        CardBullet("Shows the consent screen a real participant sees, word for word")
                        CardBullet("Opens the site in the study browser, with the same Done and Finish controls")
                        CardBullet("Records absolutely nothing — no measurement script runs, and nothing is sent anywhere")
                        CardBullet("Stays on the site you enter; links to other sites open in your normal browser, exactly as in a real study")
                    }
                    CardBody(
                        "A real study looks identical, except that the research team configures it and — after you accept consent — it measures how you use the pages.",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
        )
    }
}

@Composable
private fun SiteField(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        interactionSource = interaction,
        textStyle = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Brand.text),
        cursorBrush = SolidColor(Brand.text),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
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
                if (value.isEmpty()) {
                    Text("example.com", fontSize = 16.sp, fontFamily = FontFamily.Monospace, color = Brand.textMuted)
                }
                inner()
            }
        },
    )
}
