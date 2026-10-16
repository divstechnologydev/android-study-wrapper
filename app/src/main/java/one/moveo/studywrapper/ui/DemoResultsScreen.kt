package one.moveo.studywrapper.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/// What a study produces — shown at the end of the demo so someone evaluating
/// the app can see the output, not just the participant flow (← iOS
/// DemoResultsView.swift).
///
/// **Every number here is illustrative.** The demo records nothing (see
/// `DemoStudy`), so there is no session to report on; this screen exists to
/// show the shape of a real result. That is stated at the top of the screen,
/// on the chart itself, and on the metric detail — never in one place only,
/// because a chart screenshotted out of context must still read as a sample.
@Composable
fun DemoResultsScreen(onDismiss: () -> Unit) {
    val metrics = DemoMetric.SAMPLE
    var selected by remember { mutableStateOf(metrics[0].id) }
    val selectedMetric = metrics.first { it.id == selected }

    // The draw-in is scenery: with animations off (accessibility or developer
    // settings) the chart is simply there, fully drawn.
    val animate = animationsEnabled()
    var started by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = if (animate) 700 else 0, easing = LinearEasing),
        label = "radarDraw",
    )

    OverlayScreen(title = "Sample results", actionLabel = "Done", onAction = onDismiss) {
        BrandNotice(
            text = "Sample data. The demo doesn't record anything, so these numbers are an illustration — this is the shape of the report a research team gets in Moveo One once a real study closes.",
            background = Brand.warningBg,
            foreground = Brand.warningText,
        )

        // The headline numbers carry real units; the chart below normalizes
        // everything to a 0–100 score so six different measures can share axes.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile("92%", "Tasks completed", "+14 vs median", Modifier.weight(1f))
            StatTile("3m 12s", "Time on task", "53s faster", Modifier.weight(1f))
            StatTile("4", "Hesitations", "3 fewer", Modifier.weight(1f))
        }

        AdaptiveColumns(
            left = {
                InfoCard("Behaviour profile") {
                    Text(
                        "Six measures, each scored 0–100 against everyone else in the study. Tap a measure to read it.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = Brand.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendItem(Brand.signalLive, "This session")
                        LegendItem(Brand.link, "Study median")
                    }
                    RadarChart(
                        metrics = metrics,
                        selected = selected,
                        progress = progress,
                        onSelect = { selected = it },
                        height = if (isWideWindow()) 420.dp else 320.dp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            right = {
                InfoCard("Measures") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        metrics.forEach { metric ->
                            MetricRow(
                                metric = metric,
                                isSelected = metric.id == selected,
                                onClick = { selected = metric.id },
                            )
                        }
                    }
                    HorizontalDivider(color = Brand.border, thickness = 1.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            selectedMetric.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.text,
                        )
                        CardBody(selectedMetric.explanation)
                        Text(
                            selectedMetric.readingVsMedian,
                            modifier = Modifier.padding(top = 2.dp),
                            fontSize = 13.sp,
                            color = if (selectedMetric.isAboveMedian) Brand.successText else Brand.textMuted,
                        )
                    }
                }
            },
        )

        InfoCard("In a real study") {
            Text(
                "Results are produced from what participants actually do, and go to the research team running the study — not to participants. They become available in Moveo One once the study closes.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Brand.textSecondary,
            )
        }
    }
}

/// Equal-height tiles: the label is allowed two lines and the row is sized to
/// the tallest, so the three cards line up whatever the copy.
@Composable
private fun StatTile(value: String, label: String, delta: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxHeight().brandCard().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Brand.text, maxLines = 1)
        Text(label, fontSize = 11.5.sp, lineHeight = 15.sp, color = Brand.textSecondary, maxLines = 2)
        Spacer(Modifier.weight(1f))
        Text(delta, fontSize = 11.sp, color = Brand.successText, maxLines = 1)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, fontSize = 12.sp, color = Brand.textSecondary)
    }
}

// MARK: - Model

/// One axis of the sample profile. Scores are 0–100 so six unlike measures
/// can share one set of axes; the headline tiles carry the real units.
data class DemoMetric(
    val id: String,
    val name: String,
    /// Short form for the chart's axis labels, where space is tight.
    val shortName: String,
    val session: Double,
    val median: Double,
    val explanation: String,
) {
    val isAboveMedian: Boolean get() = session >= median

    val readingVsMedian: String
        get() {
            val delta = (session - median).roundToInt()
            return when {
                delta == 0 -> "Exactly at the study median."
                delta > 0 -> "$delta points above the study median."
                else -> "${-delta} points below the study median."
            }
        }

    companion object {
        /// Deliberately mixed: a sample where every measure beats the median
        /// reads as marketing, not as a report.
        val SAMPLE = listOf(
            DemoMetric(
                "completion", "Task completion", "Completion", 92.0, 78.0,
                "How much of what the study asked for actually got done, from the pages reached and the actions taken along the way.",
            ),
            DemoMetric(
                "pace", "Pace", "Pace", 68.0, 50.0,
                "Speed through the tasks relative to everyone else in the study. A high score is faster, which is good for a routine task and worth a second look on one meant to be considered.",
            ),
            DemoMetric(
                "attention", "Attention", "Attention", 74.0, 62.0,
                "How much of the session was spent engaged with page content rather than idle or scrolling past it.",
            ),
            DemoMetric(
                "navigation", "Navigation", "Navigation", 81.0, 70.0,
                "How directly the participant moved toward what they were looking for, counting backtracks and loops against the score.",
            ),
            DemoMetric(
                "exploration", "Exploration", "Exploration", 57.0, 66.0,
                "How much of the site was opened up beyond the direct path. A low score can mean confident targeting — or that nothing else invited a look.",
            ),
            DemoMetric(
                "decisiveness", "Decisiveness", "Decisiveness", 63.0, 71.0,
                "Hesitation before committing: hovering, re-reading and abandoned interactions all pull this down.",
            ),
        )
    }
}

// MARK: - Metric row

/// A paired bar per measure — the form a reader can actually rank, which the
/// radar deliberately isn't. Value is direct-labelled, so identity never rests
/// on colour alone.
@Composable
private fun MetricRow(metric: DemoMetric, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Brand.radiusMD))
            .background(if (isSelected) Brand.bgSunken else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = "${metric.name}. This session ${metric.session.roundToInt()} " +
                    "out of 100, study median ${metric.median.roundToInt()}."
            },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                metric.name,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = Brand.text,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${metric.session.roundToInt()}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Brand.text,
            )
            Text(
                "vs ${metric.median.roundToInt()}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Brand.textMuted,
            )
        }
        // Median sits behind as the reference. Kept dense enough to still read
        // as the legend's indigo — at a lighter tint it turns grey and loses
        // its link to the chart.
        Box(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brand.bgSunken),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((metric.median / 100).toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brand.link.copy(alpha = 0.55f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((metric.session / 100).toFloat())
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brand.signalLive),
            )
        }
    }
}

// MARK: - Radar chart

/// Six-axis profile, two series. The radar earns its place here by showing the
/// *shape* of a session at a glance; the ranked comparison beside it is what
/// the numbers are actually read from.
@OptIn(ExperimentalTextApi::class)
@Composable
private fun RadarChart(
    metrics: List<DemoMetric>,
    selected: String,
    progress: Float,
    onSelect: (String) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val rings = 4
    val measurer = rememberTextMeasurer()
    val axisDescription = metrics.joinToString(" ") {
        "${it.name}: this session ${it.session.roundToInt()}, study median ${it.median.roundToInt()}."
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = "Behaviour profile, sample data. $axisDescription" }
            .pointerInput(metrics) {
                detectTapGestures { tap ->
                    // Labels are the tap targets on iOS; here the whole sector
                    // around each label answers, which is what a finger needs.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = radarRadius(size.width.toFloat(), size.height.toFloat())
                    val nearest = metrics.indices.minByOrNull { i ->
                        val p = axisPoint(center, radius + LABEL_INSET_PX, i, metrics.size, 100.0, 1f)
                        hypot(tap.x - p.x, tap.y - p.y)
                    } ?: return@detectTapGestures
                    onSelect(metrics[nearest].id)
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = radarRadius(size.width, size.height)
        if (radius <= 0f) return@Canvas
        val count = metrics.size

        // Grid is scenery and must not grow in with the data.
        for (ring in 1..rings) {
            drawPath(
                path = polygonPath(
                    center, radius, List(count) { ring.toDouble() / rings * 100 }, count, 1f,
                ),
                color = Brand.border,
                style = Stroke(width = if (ring == rings) 1.5.dp.toPx() else 1.dp.toPx()),
            )
        }
        metrics.indices.forEach { i ->
            drawLine(
                color = if (metrics[i].id == selected) Brand.borderStrong else Brand.border,
                start = center,
                end = axisPoint(center, radius, i, count, 100.0, 1f),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // The median carries almost no fill: two filled polygons overlap into
        // a muddy third colour that means nothing. The dashed stroke is what
        // identifies it.
        drawSeries(
            path = polygonPath(center, radius, metrics.map { it.median }, count, progress),
            color = Brand.link, fillAlpha = 0.05f, dashed = true,
        )
        drawSeries(
            path = polygonPath(center, radius, metrics.map { it.session }, count, progress),
            color = Brand.signalLive, fillAlpha = 0.13f, dashed = false,
        )

        // Markers on the session series only — two sets of dots on six axes is
        // noise. A surface ring keeps them legible where the fills overlap.
        metrics.indices.forEach { i ->
            val isSelected = metrics[i].id == selected
            val p = axisPoint(center, radius, i, count, metrics[i].session, progress)
            val r = (if (isSelected) 6.dp else 4.dp).toPx()
            drawCircle(color = Brand.signalLive, radius = r, center = p)
            drawCircle(color = Brand.bgElevated, radius = r, center = p, style = Stroke(width = 2.dp.toPx()))
        }

        // Axis labels: name over score, centred on the axis outside the plot.
        metrics.indices.forEach { i ->
            val isSelected = metrics[i].id == selected
            val anchor = axisPoint(center, radius + LABEL_INSET_PX, i, count, 100.0, 1f)
            val nameLayout = measurer.measure(
                text = metrics[i].shortName,
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Brand.text else Brand.textSecondary,
                ),
            )
            val valueLayout = measurer.measure(
                text = "${metrics[i].session.roundToInt()}",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) Brand.signalLive else Brand.textMuted,
                ),
            )
            val blockHeight = nameLayout.size.height + valueLayout.size.height + 1
            val top = anchor.y - blockHeight / 2f
            drawText(
                textLayoutResult = nameLayout,
                topLeft = Offset(anchor.x - nameLayout.size.width / 2f, top),
            )
            drawText(
                textLayoutResult = valueLayout,
                topLeft = Offset(
                    anchor.x - valueLayout.size.width / 2f,
                    top + nameLayout.size.height + 1,
                ),
            )
        }
    }
}

/// Labels need room outside the plot, and the widest sit on the horizontal
/// axis — hence more inset on x than y.
private const val LABEL_INSET_PX = 34f

private fun radarRadius(width: Float, height: Float): Float =
    minOf(width / 2f - 62f, height / 2f - 34f)

/// Axis 0 at 12 o'clock, clockwise from there.
private fun axisAngle(index: Int, count: Int): Float =
    (-PI / 2 + (2 * PI / count) * index).toFloat()

private fun axisPoint(
    center: Offset,
    radius: Float,
    index: Int,
    count: Int,
    value: Double,
    progress: Float,
): Offset {
    val r = radius * (value / 100).toFloat() * progress
    val a = axisAngle(index, count)
    return Offset(center.x + cos(a) * r, center.y + sin(a) * r)
}

private fun polygonPath(
    center: Offset,
    radius: Float,
    values: List<Double>,
    count: Int,
    progress: Float,
): Path = Path().apply {
    values.forEachIndexed { i, v ->
        val p = axisPoint(center, radius, i, count, v, progress)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.drawSeries(path: Path, color: Color, fillAlpha: Float, dashed: Boolean) {
    drawPath(path = path, color = color.copy(alpha = fillAlpha))
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = if (dashed) {
                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            } else {
                null
            },
        ),
    )
}
