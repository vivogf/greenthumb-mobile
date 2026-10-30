package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import site.xmpp.greenthumb.ui.theme.LocalReduceMotion
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/** How the smart camera ring is drawn. */
sealed interface GlowSpec {
    /** A fixed glow → accent gradient: system animations are off or the dashboard is not in front. */
    data object Static : GlowSpec

    /** A rotating sweep gradient whose alpha pulses between [minAlpha] and 1. */
    data class Animated(val rotationMs: Int, val pulseMs: Int, val minAlpha: Float) : GlowSpec
}

/** The ring animates only while the dashboard is resumed and the user has not turned animations off. */
fun glowSpec(reduceMotion: Boolean, active: Boolean): GlowSpec =
    if (reduceMotion || !active) {
        GlowSpec.Static
    } else {
        GlowSpec.Animated(Motion.GlowSpinMs, Motion.GlowPulseMs, Motion.GlowMinAlpha)
    }

/**
 * Icon-only round «Умная камера» button of the dashboard: one clickable node named by
 * [contentDescription], no text, a green glowing ring (D§ item 9a).
 */
@Composable
fun GtSmartCameraButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val glow = greenThumbExtendedColors().glow
    val accent = scheme.secondary
    val spec = glowSpec(LocalReduceMotion.current, active)
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .size(Sizes.smartCameraButton)
            // One accessibility node with the name and the click (uiautomator showed the name on a child otherwise).
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
                testTag = if (spec is GlowSpec.Animated) "smartCamera_glow_animated" else "smartCamera_glow_static"
                onClick { currentOnClick(); true }
            }
            .clip(CircleShape)
            .background(scheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (spec) {
            GlowSpec.Static -> Box(
                modifier = Modifier
                    .size(Sizes.smartCameraButton)
                    .border(Sizes.glowBorder, Brush.linearGradient(listOf(glow, accent)), CircleShape),
            )
            is GlowSpec.Animated -> AnimatedRing(spec, glow, accent)
        }
        GtSmartCameraMark(tint = accent, modifier = Modifier.size(Sizes.iconSlot))
    }
}

@Composable
private fun AnimatedRing(spec: GlowSpec.Animated, glow: Color, accent: Color) {
    val transition = rememberInfiniteTransition(label = "smartCameraGlow")
    val angle = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(spec.rotationMs, easing = LinearEasing)),
        label = "rotation",
    )
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = spec.minAlpha,
        animationSpec = infiniteRepeatable(tween(spec.pulseMs / 2, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val brush = Brush.sweepGradient(listOf(glow, accent, Color.Transparent, glow))
    Box(
        modifier = Modifier
            .size(Sizes.smartCameraButton)
            .drawBehind {
                val stroke = Sizes.glowBorder.toPx()
                // Values are read here, in the draw phase, so the ring redraws without recomposition.
                rotate(angle.value) {
                    drawCircle(
                        brush = brush,
                        radius = size.minDimension / 2f - stroke / 2f,
                        alpha = alpha.value,
                        style = Stroke(width = stroke),
                    )
                }
            },
    )
}
