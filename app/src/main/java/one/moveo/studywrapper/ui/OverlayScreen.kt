package one.moveo.studywrapper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// The iOS `.sheet` / `.fullScreenCover` screens (How it works, Demo setup,
/// Sample results) as Android full-screen destinations: same chrome — title
/// bar with one dismissing action — plus system Back, which is what an
/// Android participant will reach for first.
///
/// Rendered inside the app's root, so the window insets and the IME padding
/// already applied there hold here too (a Dialog window would have to redo
/// both).
@Composable
fun OverlayScreen(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onAction)

    Column(modifier = Modifier.fillMaxSize().background(Brand.bg)) {
        Box(
            modifier = Modifier.fillMaxWidth().background(Brand.bgElevated),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .readingColumn(regular = 1040.dp)
                    .padding(start = 16.dp, end = 4.dp)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.text,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAction) {
                    Text(actionLabel, fontSize = 15.sp, color = Brand.text)
                }
            }
        }
        HorizontalDivider(color = Brand.border, thickness = 1.dp)
        CenteringScrollColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            spacing = 18.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().readingColumn().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

/// The card shape every explainer/demo section uses: elevated panel, eyebrow
/// heading, 20dp padding (the extension's `.card` + `.eyebrow`).
@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().brandCard().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandEyebrow(title)
        content()
    }
}

/// Body prose inside a card.
@Composable
fun CardBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Brand.textSecondary,
    )
}

@Composable
fun CardBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("\u2022", fontSize = 14.sp, color = Brand.textMuted)
        Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Brand.textSecondary)
    }
}

/// Tappable link text (the app has no HTML, so links are plain styled text —
/// same treatment as the consent screen's privacy-policy link).
@Composable
fun LinkText(text: String, url: String, modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        text = text,
        modifier = modifier.clickable { uriHandler.openUri(url) },
        fontSize = 14.sp,
        color = Brand.link,
    )
}
