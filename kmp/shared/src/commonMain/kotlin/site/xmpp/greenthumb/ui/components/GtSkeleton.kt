package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

enum class GtSkeletonMode { List, Card, Grid }

/**
 * Замена `components/SkeletonPlaceholder.tsx`.
 * Пульс — [Motion.SkeletonPulseMs] и [Motion.InOutQuad], альфа из Motion.
 * Поверх пульса Stage 10 добавляет анимированный градиент-шиммер: полоса
 * света проходит по каждой кости за тот же цикл [Motion.SkeletonPulseMs]
 * (единый бесконечный переход на все кости, как единый animatedStyle в RN).
 * Счётчики по умолчанию как в RN: list 6, card 2, grid 9.
 * Размеры костей, которых нет на шкале, собраны из токенов
 * (56 = xxl*2+xs, 40 = xxl+lg, 280 = xxl*11+lg).
 */
@Composable
fun GtSkeleton(
    mode: GtSkeletonMode,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val rows = count ?: when (mode) {
        GtSkeletonMode.List -> 6
        GtSkeletonMode.Card -> 2
        GtSkeletonMode.Grid -> 9
    }
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = Motion.SkeletonMinAlpha,
        targetValue = Motion.SkeletonMaxAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = Motion.SkeletonPulseMs,
                easing = Motion.InOutQuad,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val shimmerPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = Motion.SkeletonPulseMs,
                easing = LinearEasing,
            ),
        ),
    )
    when (mode) {
        GtSkeletonMode.List -> SkeletonList(rows, alpha, shimmerPhase, modifier)
        GtSkeletonMode.Card -> SkeletonCards(rows, alpha, shimmerPhase, modifier)
        GtSkeletonMode.Grid -> SkeletonGrid(rows, alpha, shimmerPhase, modifier)
    }
}

@Composable
private fun SkeletonList(count: Int, alpha: Float, shimmerPhase: Float, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(count) {
            SkeletonListRow(alpha, shimmerPhase)
        }
    }
}

@Composable
private fun SkeletonListRow(alpha: Float, shimmerPhase: Float) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .borderHairline(greenThumbExtendedColors().cardBorder, shape)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Bone(
            modifier = Modifier.size(Spacing.xxl * 2 + Spacing.xs).clip(RoundedCornerShape(Radii.md)),
            alpha = alpha,
            shimmerPhase = shimmerPhase,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Bone(modifier = Modifier.fillMaxWidth(0.65f).height(Spacing.md), alpha = alpha, shimmerPhase = shimmerPhase)
            Bone(modifier = Modifier.fillMaxWidth(0.4f).height(Spacing.sm), alpha = alpha, shimmerPhase = shimmerPhase)
            Bone(
                modifier = Modifier.size(width = Spacing.xxl * 3 + Spacing.xs, height = Spacing.xl),
                alpha = alpha,
                shimmerPhase = shimmerPhase,
            )
        }
        Bone(
            modifier = Modifier.size(Spacing.xxl + Spacing.lg).clip(CircleShape),
            alpha = alpha,
            shimmerPhase = shimmerPhase,
        )
    }
}

@Composable
private fun SkeletonCards(count: Int, alpha: Float, shimmerPhase: Float, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        repeat(count) {
            val shape = RoundedCornerShape(Radii.xl)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .borderHairline(greenThumbExtendedColors().cardBorder, shape),
            ) {
                Bone(
                    modifier = Modifier.fillMaxWidth().height(Spacing.xxl * 11 + Spacing.lg),
                    alpha = alpha,
                    rounded = false,
                    shimmerPhase = shimmerPhase,
                )
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Bone(modifier = Modifier.fillMaxWidth(0.55f).height(Spacing.lg), alpha = alpha, shimmerPhase = shimmerPhase)
                    Bone(modifier = Modifier.fillMaxWidth().height(Spacing.xxl + Spacing.md), alpha = alpha, shimmerPhase = shimmerPhase)
                }
            }
        }
    }
}

@Composable
private fun SkeletonGrid(count: Int, alpha: Float, shimmerPhase: Float, modifier: Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        val gap = Spacing.xs
        val cell = (maxWidth - gap * 2) / 3
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            val rows = (count + 2) / 3
            repeat(rows) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(3) { column ->
                        val index = row * 3 + column
                        if (index < count) {
                            Bone(
                                modifier = Modifier.size(cell).clip(RoundedCornerShape(Radii.sm)),
                                alpha = alpha,
                                shimmerPhase = shimmerPhase,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Яркость блика шиммера на кости; не длительность — вне правила Motion-токенов. */
private const val SHIMMER_HIGHLIGHT_ALPHA = 0.12f

@Composable
private fun Bone(modifier: Modifier, alpha: Float, shimmerPhase: Float, rounded: Boolean = true) {
    val shape = if (rounded) RoundedCornerShape(Radii.sm) else RoundedCornerShape(percent = 0)
    val highlight = Color.White.copy(alpha = SHIMMER_HIGHLIGHT_ALPHA)
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .drawWithContent {
                drawContent()
                // Блик — узкая полоса линейного градиента, за цикл проходит кость слева
                // направо (Stage 10 п.1: анимированный градиент).
                val band = size.width * 0.6f
                val x = -band + shimmerPhase * (size.width + band)
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to highlight,
                        1f to Color.Transparent,
                        start = Offset(x, 0f),
                        end = Offset(x + band, size.height),
                    ),
                )
            },
    )
}
