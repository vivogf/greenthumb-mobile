package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.ui.theme.GreenThumbColors
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/** Частица всплеска полива. */
internal data class WaterParticleSpec(
    val id: Int,
    /** Горизонтальный дрейф, dp: веер ±90 вокруг центра кнопки. */
    val xDp: Float,
    /** Подъём, dp: 70..130. */
    val travelDp: Float,
    /** Градусы: −40..+40. */
    val rotationDeg: Float,
    /** Сторона иконки, dp: 21..33 (RN 14..22, ×1.5). */
    val sizeDp: Float,
    /** Задержка серии, мс: 0..[Motion.WaterParticleStaggerMaxMs]. */
    val delayMs: Float,
    /** Индекс оттенка в палитре всплеска (0..[WaterParticleShades]-1). */
    val shade: Int,
    /** Максимальная непрозрачность, 0.65..1: сердца не одинаково яркие. */
    val peakAlpha: Float,
)

/** Падение в конце дуги, dp (RN `* 12` — «slight drop at end»). */
private const val WaterDropBackDp: Float = 12f

internal const val WaterParticleShades: Int = 3

/** Серия сердец полива: 14–18 штук (в RN было 8–12). Внутренняя — для jvmTest инвариантов. */
internal fun generateWaterParticles(random: Random): List<WaterParticleSpec> {
    val count = 14 + random.nextInt(5)
    return List(count) { id ->
        WaterParticleSpec(
            id = id,
            xDp = (random.nextFloat() - 0.5f) * 180f,
            travelDp = 70f + random.nextFloat() * 60f,
            rotationDeg = (random.nextFloat() - 0.5f) * 80f,
            sizeDp = (21 + random.nextInt(13)).toFloat(),
            delayMs = random.nextFloat() * Motion.WaterParticleStaggerMaxMs,
            shade = random.nextInt(WaterParticleShades),
            peakAlpha = 0.65f + random.nextFloat() * 0.35f,
        )
    }
}

/**
 * Кнопка полива с всплеском сердец — порт `components/WaterButtonWithParticles.tsx`
 * (+ `WaterParticles.tsx`): compact — круг 40 для списка/сетки, fullWidth — кнопка
 * с подписью для карточки/детали.
 *
 * Тап: гаптика Medium ([Haptics.medium]), сразу состояние «полито» (галочка +
 * [successLabel]), всплеск; через [Motion.WaterPressLeadMs] мутация
 * ([onWater]), затем состояние держится ещё [Motion.WaterParticlesHoldMs].
 * Пока запрос в полёте ([isWatering]), кнопка остаётся в «полито» — одинокого
 * спиннера нет: запись оптимистична в репозитории, при отказе откат и алерт
 * делает вызывающий экран, а кнопка возвращается в обычный вид.
 *
 * Лид перед мутацией нужен потому, что оптимистичный healthy-статус снимает
 * кнопку со списка в тот же кадр — без него всплеск не виден.
 *
 * Повторный тап во время всплеска/полива игнорируется (RN guard
 * `isWatering || showParticles`).
 */
@Composable
public fun GtWaterButton(
    onWater: () -> Unit,
    isWatering: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String? = null,
    successLabel: String? = null,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var celebrating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val a11yLabel: String? = contentDescription
    val watered = celebrating || isWatering

    fun handlePress() {
        if (isWatering || celebrating) return
        Haptics.medium()
        celebrating = true
        scope.launch {
            delay(Motion.WaterPressLeadMs.toLong())
            onWater()
            delay(Motion.WaterParticlesHoldMs.toLong())
            celebrating = false
        }
    }

    val pop by animateFloatAsState(
        targetValue = if (watered) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "waterPop",
    )

    // Центрируем и кнопку, и область частиц: сердца вылетают за границы
    // кнопки из её центра.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (compact) {
            Box(
                modifier = Modifier
                    .size(Spacing.xxl + Spacing.lg)
                    .graphicsLayer { scaleX = pop; scaleY = pop }
                    .background(scheme.primary, CircleShape)
                    .clickable(enabled = !watered, role = Role.Button, onClick = ::handlePress)
                    .semantics { if (a11yLabel != null) this.contentDescription = a11yLabel },
                contentAlignment = Alignment.Center,
            ) {
                WaterStateIcon(watered = watered, size = Spacing.lg + Spacing.xxs)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = pop; scaleY = pop }
                    .background(scheme.primary, RoundedCornerShape(Radii.md))
                    .clickable(enabled = !watered, role = Role.Button, onClick = ::handlePress)
                    .semantics { if (a11yLabel != null) this.contentDescription = a11yLabel }
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
            ) {
                WaterStateIcon(watered = watered, size = Spacing.md + Spacing.xxs)
                val text = if (watered) successLabel ?: label else label
                if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimary,
                    )
                }
            }
        }
        // Всплеск поверх кнопки: не кликабелен, вылетает за границы кнопки.
        if (celebrating) {
            GtWaterParticles(modifier = Modifier.matchParentSize())
        }
    }
}

/** Капля в покое; заполненный круг с галочкой после тапа. */
@Composable
private fun WaterStateIcon(watered: Boolean, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    if (watered) {
        Box(
            modifier = Modifier.size(size).background(scheme.onPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            GtTickMark(tint = scheme.primary, modifier = Modifier.size(size * 0.62f))
        }
    } else {
        GtWaterDropMark(tint = scheme.onPrimary, modifier = Modifier.size(size), filled = true)
    }
}

/**
 * Серия сердец из центра кнопки: вверх веером с вращением и масштабом;
 * прогресс серии — [Motion.OutCubic] на [Motion.WaterParticlesMs] с
 * задержками до [Motion.WaterParticleStaggerMaxMs].
 */
@Composable
private fun BoxScope.GtWaterParticles(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val specs = remember { generateWaterParticles(Random.Default) }
    val shades = remember(scheme) {
        listOf(
            scheme.primary,
            GreenThumbColors.success,
            lerp(scheme.primary, scheme.onPrimary, 0.4f),
        )
    }
    val heart = remember { unitHeartPath() }
    val elapsed = remember { Animatable(0f) }
    val totalMs = (Motion.WaterParticlesMs + Motion.WaterParticleStaggerMaxMs).toFloat()
    LaunchedEffect(specs) {
        elapsed.animateTo(1f, tween(durationMillis = totalMs.toInt()))
    }
    Canvas(modifier = modifier) {
        // Область рисования = размер кнопки; сердца рисуются за границами
        // канвы (компоновка их не клипует), центр — центр кнопки.
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val t = elapsed.value * totalMs
        specs.forEach { spec ->
            val local = ((t - spec.delayMs) / Motion.WaterParticlesMs).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val p = Motion.OutCubic.transform(local)
            // Масштаб RN: 0.3 → 1.1 (30%) → 0.7 (70%) → 0.
            val scale = when {
                p < 0.3f -> 0.3f + (p / 0.3f) * 0.8f
                p < 0.7f -> 1.1f - ((p - 0.3f) / 0.4f) * 0.4f
                else -> (0.7f - ((p - 0.7f) / 0.3f) * 0.7f).coerceAtLeast(0f)
            }
            val offsetYDp = if (p < 0.75f) {
                -(p / 0.75f) * spec.travelDp
            } else {
                -spec.travelDp + ((p - 0.75f) / 0.25f) * WaterDropBackDp
            }
            val fade = if (p < 0.8f) 1f else (1f - (p - 0.8f) / 0.2f).coerceAtLeast(0f)
            val side = spec.sizeDp.dp.toPx()
            val shiftX = centerX + spec.xDp.dp.toPx() * p
            val shiftY = centerY + offsetYDp.dp.toPx()
            withTransform({
                translate(left = shiftX, top = shiftY)
                rotate(degrees = spec.rotationDeg * p, pivot = Offset.Zero)
                scale(scaleX = scale * side, scaleY = scale * side, pivot = Offset.Zero)
            }) {
                drawPath(heart, shades[spec.shade].copy(alpha = spec.peakAlpha * fade))
            }
        }
    }
}

/** Сердце — Ionicons `heart` (заливка), глиф в единичном квадрате вокруг нуля. */
private fun unitHeartPath(): Path = Path().apply {
    moveTo(0.5f, 0.85f)
    cubicTo(0.2f, 0.6f, 0.05f, 0.42f, 0.05f, 0.28f)
    cubicTo(0.05f, 0.12f, 0.2f, 0.05f, 0.32f, 0.05f)
    cubicTo(0.4f, 0.05f, 0.47f, 0.09f, 0.5f, 0.15f)
    cubicTo(0.53f, 0.09f, 0.6f, 0.05f, 0.68f, 0.05f)
    cubicTo(0.8f, 0.05f, 0.95f, 0.12f, 0.95f, 0.28f)
    cubicTo(0.95f, 0.42f, 0.8f, 0.6f, 0.5f, 0.85f)
    close()
}
