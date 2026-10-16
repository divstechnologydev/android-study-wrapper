package one.moveo.studywrapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// Large-screen layout primitives (← iOS AdaptiveLayout.swift). The app's
/// screens are phone-shaped by origin — one narrow column — which on a tablet
/// or an unfolded foldable reads as a blown-up phone app with a sea of empty
/// space either side. These give a screen a second column and a wider reading
/// measure when there is room, and collapse back to the phone layout when
/// there isn't.
///
/// The iOS size classes have no exact Android equivalent; the window's own
/// width is the honest measure, and it tracks split-screen and folding the
/// same way `horizontalSizeClass` tracks Split View.
private val WIDE_BREAKPOINT = 720.dp

@Composable
fun isWideWindow(): Boolean = LocalConfiguration.current.screenWidthDp.dp >= WIDE_BREAKPOINT

/// Side by side when there is width for it, stacked when there isn't. Both
/// columns take equal width and align to the top, so the row is as tall as
/// its tallest child.
@Composable
fun AdaptiveColumns(
    modifier: Modifier = Modifier,
    spacing: Dp = 18.dp,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (isWideWindow()) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) { left() }
            Box(modifier = Modifier.weight(1f)) { right() }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            left()
            right()
        }
    }
}

/// One centred reading column — wide enough to use a tablet, narrow enough
/// that prose keeps a sane measure. Apply to content that sits inside a
/// full-width background (a header bar, a status strip) as well as to the
/// scrolling body, so everything lines up on one grid.
@Composable
fun Modifier.readingColumn(compact: Dp = 560.dp, regular: Dp = 1040.dp): Modifier =
    this.widthIn(max = if (isWideWindow()) regular else compact)

/// A vertically scrolling column that centres short content instead of
/// stranding it at the top of a tall screen. A no-op once the content is
/// taller than the viewport, and on narrow windows, where plain top-aligned
/// scrolling is what a phone should do.
@Composable
fun CenteringScrollColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val wide = isWideWindow()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .then(if (wide) Modifier.heightIn(min = viewportHeight) else Modifier),
            horizontalAlignment = horizontalAlignment,
            // spacedBy carries the vertical alignment, so centring short
            // content never costs the spacing between the cards.
            verticalArrangement = Arrangement.spacedBy(
                spacing,
                if (wide) Alignment.CenterVertically else Alignment.Top,
            ),
            content = content,
        )
    }
}
