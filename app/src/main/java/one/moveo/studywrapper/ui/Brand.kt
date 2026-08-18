package one.moveo.studywrapper.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import one.moveo.studywrapper.R

/// Moveo One brand kit v3 — Compose port of the iOS `Brand.swift`, itself a
/// port of the extension's tokens (popup.css / consent.css → the platform's
/// tokens.css). Near-black on warm neutrals, mono eyebrows, hairline borders,
/// small radii, outlined-invert primary buttons. Orange is reserved for the
/// "live" signal — active tracking. Keep values in lockstep with the
/// extension + iOS so all clients read as one product.
object Brand {
    // Surfaces
    val bg = Color(0xFFFAFAFA)
    val bgChrome = Color(0xFFF4F4F4)
    val bgElevated = Color(0xFFFFFFFF)
    val bgSunken = Color(0xFFF0F0F0)
    val bgHover = Color(0xFFEDEDED)

    // Text
    val text = Color(0xFF111111)
    val textSecondary = Color(0xFF525252)
    val textMuted = Color(0xFFA3A3A3)

    // Borders
    val border = Color(0xFFE5E5E5)
    val borderStrong = Color(0xFFD4D4D4)

    // The scarce live signal (active tracking) — never decorative.
    val signalLive = Color(0xFFFF5A1F)
    val signalLiveBg = Color(0xFFFFF1EA)

    // Status pairs
    val successBg = Color(0xFFE8F0E4)
    val successText = Color(0xFF4A6B3E)
    val warningBg = Color(0xFFF5ECD9)
    val warningText = Color(0xFF7A5E2A)
    val warningSolid = Color(0xFFB0934E)
    val dangerBg = Color(0xFFF5E0DA)
    val dangerText = Color(0xFF843A2C)
    val dangerSolid = Color(0xFFB04A3A)
    val infoBg = Color(0xFFE3E9F2)
    val infoText = Color(0xFF3B5675)
    val link = Color(0xFF3B5BA8)

    val radiusXS: Dp = 2.dp
    val radiusSM: Dp = 4.dp
    val radiusMD: Dp = 6.dp

    /// The landing/consent card radius.
    val radiusCard: Dp = 16.dp

    /// Button-press pressed danger fill / danger label (iOS literals).
    val dangerPressed = Color(0xFF983E30)
    val dangerLabel = Color(0xFFFAFAF8)
}

// MARK: - Type treatments

/// Label/section eyebrow — mono, uppercase, quiet (the brand's h2).
@Composable
fun BrandEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.7.sp,
        color = Brand.textMuted,
    )
}

// MARK: - Buttons (brand kit v3: outlined-invert primary, ghost, danger)

/// Primary — outlined near-black that fills when pressed (the hover-invert
/// of `.mo-btn-primary`, mapped to the press state on touch).
@Composable
fun BrandPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) Brand.text else Brand.bgElevated
    val fg = if (pressed) Brand.bgElevated else Brand.text
    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (enabled) 1f else 0.5f),
        enabled = enabled,
        shape = RoundedCornerShape(Brand.radiusSM),
        color = bg,
        contentColor = fg,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Brand.text),
        interactionSource = interaction,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            content(fg)
        }
    }
}

@Composable
fun BrandPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BrandPrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) { fg ->
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

enum class GhostRole { NEUTRAL, DANGER }

@Composable
fun BrandGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: GhostRole = GhostRole.NEUTRAL,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        pressed && role == GhostRole.DANGER -> Brand.dangerText
        pressed -> Brand.text
        role == GhostRole.DANGER -> Brand.dangerSolid
        else -> Brand.textSecondary
    }
    val bg = when {
        pressed && role == GhostRole.DANGER -> Brand.dangerBg
        pressed -> Brand.bgHover
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 44.dp).alpha(if (enabled) 1f else 0.5f),
        enabled = enabled,
        shape = RoundedCornerShape(Brand.radiusSM),
        color = bg,
        contentColor = fg,
        interactionSource = interaction,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = fg)
        }
    }
}

@Composable
fun BrandDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 44.dp).alpha(if (enabled) 1f else 0.5f),
        enabled = enabled,
        shape = RoundedCornerShape(Brand.radiusSM),
        color = if (pressed) Brand.dangerPressed else Brand.dangerSolid,
        contentColor = Brand.dangerLabel,
        interactionSource = interaction,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Brand.dangerLabel)
        }
    }
}

// MARK: - Components shared across screens

/// Origin chip — hostname in mono on a sunken surface (popup/consent
/// `.origins li`).
@Composable
fun OriginChip(origin: String) {
    Text(
        text = origin,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        color = Brand.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(Brand.radiusSM))
            .background(Brand.bgSunken)
            .border(1.dp, Brand.border, RoundedCornerShape(Brand.radiusSM))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

/// Simple wrapping layout for origin chips (they wrap in the extension too).
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OriginChips(origins: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        origins.forEach { OriginChip(it) }
    }
}

/// Status block (notice / error / banner) — tinted bg + matching text.
@Composable
fun BrandNotice(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Brand.warningBg,
    foreground: Color = Brand.warningText,
) {
    Text(
        text = text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = foreground,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Brand.radiusMD))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/// The elevated card of the landing/consent pages (`.card`).
fun Modifier.brandCard(): Modifier = this
    .shadow(elevation = 6.dp, shape = RoundedCornerShape(Brand.radiusCard), clip = false,
        ambientColor = Color(0xFF141414).copy(alpha = 0.06f),
        spotColor = Color(0xFF141414).copy(alpha = 0.06f))
    .clip(RoundedCornerShape(Brand.radiusCard))
    .background(Brand.bgElevated)
    .border(1.dp, Color(0xFF141414).copy(alpha = 0.08f), RoundedCornerShape(Brand.radiusCard))

/// The iOS `accessibilityReduceMotion` analogue: the system animator scale.
/// 0 (animations off in accessibility/developer settings) disables the
/// repeat-forever brand animations — same rule as iOS. Also keeps the app
/// fully render-idle for QA tooling (uiautomator waits for idle).
@Composable
private fun animationsEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}

/// Brand kit v3 auth background — the platform's AuthBackground: faint
/// constellation, an orange spark and a muted indigo wash for depth.
@Composable
fun AuthBackground(modifier: Modifier = Modifier) {
    var dx = 0f
    var dy = 0f
    if (animationsEnabled()) {
        // Same rule as StatusDot: repeat-forever only when motion is allowed.
        val drift = rememberInfiniteTransition(label = "authDrift")
        val dxAnim by drift.animateFloat(
            initialValue = 0f, targetValue = -12f,
            animationSpec = infiniteRepeatable(tween(30_000), RepeatMode.Reverse),
            label = "dx",
        )
        val dyAnim by drift.animateFloat(
            initialValue = 0f, targetValue = 8f,
            animationSpec = infiniteRepeatable(tween(30_000), RepeatMode.Reverse),
            label = "dy",
        )
        dx = dxAnim
        dy = dyAnim
    }
    Box(modifier = modifier.fillMaxSize().background(Brand.bg)) {
        Image(
            painter = painterResource(R.drawable.auth_constellation),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.35f,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dx * density
                    translationY = dy * density
                },
        )
        // Orange spark (top right) + muted indigo wash (bottom left).
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Brand.signalLive.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(this.size.width * 0.88f, this.size.height * 0.06f),
                    radius = 320.dp.toPx(),
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF5B7DB1).copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(this.size.width * 0.06f, this.size.height * 0.94f),
                    radius = 340.dp.toPx(),
                ),
            )
        }
    }
}

/// Live-tracking status dot (popup `.status-dot`): orange + slow blink when
/// live, warning gold when paused, neutral otherwise.
@Composable
fun StatusDot(mode: StatusDotMode) {
    val color = when (mode) {
        StatusDotMode.ACTIVE -> Brand.signalLive
        StatusDotMode.PAUSED -> Brand.warningSolid
        StatusDotMode.NEUTRAL -> Brand.borderStrong
    }
    val alpha: Float = if (mode == StatusDotMode.ACTIVE && animationsEnabled()) {
        val blink = rememberInfiniteTransition(label = "dotBlink")
        val value by blink.animateFloat(
            initialValue = 1f, targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        )
        value
    } else 1f
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

enum class StatusDotMode { ACTIVE, PAUSED, NEUTRAL }
