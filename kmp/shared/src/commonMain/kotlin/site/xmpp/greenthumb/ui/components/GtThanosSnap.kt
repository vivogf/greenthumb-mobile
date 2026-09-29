package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import site.xmpp.greenthumb.ui.theme.Motion

/**
 * Частица пыли распада (RN `ThanosSnap.tsx` ParticleData): старт в пределах
 * карточки, разлёт в основном вправо и чуть вверх, вращение, сжатие.
 */
internal data class DustParticleSpec(
    /** Старт, px от левого/верхнего края карточки. */
    val startX: Float,
    val startY: Float,
    /** Смещение к концу анимации, px (RN dx/dy). */
    val dx: Float,
    val dy: Float,
    /** Сторона квадратной пылинки, dp: 3..9 (RN `3 + Math.random() * 6`). */
    val sizeDp: Float,
    /** Индекс палитры пыли (8 цветов, RN DUST_COLORS). */
    val colorIndex: Int,
    /** Задержка развёртки, мс: (startX/w)·450 + 0..200 (RN delay). */
    val delayMs: Float,
    /** Градусы: −100..+100 (RN `(Math.random() - 0.5) * 200`). */
    val rotationDeg: Float,
)

/** Палитра пыли — 8 земляных/зелёных цветов RN DUST_COLORS (эффект, не тема). */
internal val DustPalette: List<Color> = listOf(
    Color(red = 180f / 255f, green = 160f / 255f, blue = 130f / 255f, alpha = 0.9f),
    Color(red = 140f / 255f, green = 120f / 255f, blue = 95f / 255f, alpha = 0.85f),
    Color(red = 100f / 255f, green = 90f / 255f, blue = 70f / 255f, alpha = 0.75f),
    Color(red = 160f / 255f, green = 180f / 255f, blue = 140f / 255f, alpha = 0.85f),
    Color(red = 120f / 255f, green = 140f / 255f, blue = 100f / 255f, alpha = 0.75f),
    Color(red = 200f / 255f, green = 180f / 255f, blue = 150f / 255f, alpha = 0.7f),
    Color(red = 80f / 255f, green = 70f / 255f, blue = 55f / 255f, alpha = 0.8f),
    Color(red = 170f / 255f, green = 150f / 255f, blue = 120f / 255f, alpha = 0.8f),
)

/** Число пылинок на карточку (RN PARTICLE_COUNT = 28). */
internal const val DustParticleCount: Int = 28

/**
 * Генератор пыли распада (RN `generateParticles`, `ThanosSnap.tsx:32`):
 * 28 частиц из размеров карточки. Внутренняя — для jvmTest инвариантов.
 */
internal fun generateDustParticles(
    width: Float,
    height: Float,
    random: Random,
): List<DustParticleSpec> = List(DustParticleCount) { _ ->
    val startX = random.nextFloat() * width
    val startY = random.nextFloat() * height
    // В основном вправо и чуть вверх: угол (random * 0.7 - 0.15)·π, дистанция 50..140.
    val angle = (random.nextFloat() * 0.7f - 0.15f) * PI.toFloat()
    val dist = 50f + random.nextFloat() * 90f
    DustParticleSpec(
        startX = startX,
        startY = startY,
        dx = cos(angle) * dist + 25f,
        dy = -abs(sin(angle) * dist) - 8f,
        sizeDp = 3f + random.nextFloat() * 6f,
        colorIndex = random.nextInt(DustPalette.size),
        // Лево-право развёртка: левые пылинки стартуют первыми.
        delayMs = (startX / width) * Motion.ThanosSweepBaseMs +
            random.nextFloat() * Motion.ThanosSweepJitterMs,
        rotationDeg = (random.nextFloat() - 0.5f) * 200f,
    )
}

/**
 * Распад карточки в пыль — порт `components/ThanosSnap.tsx`.
 *
 * Структура RN — без снапшота композиции (байтов на пиксели вью RN не снимает:
 * он порождает частицы из размеров `onLayout`, а ребёнок просто затухает по
 * contentOpacity; решение Stage 10 — тот же Canvas поверх затухающего контента):
 * - контент затухает на [Motion.ThanosContentDelayMs] позже старта за
 *   [Motion.ThanosContentFadeMs] ([Motion.InQuad], RN `delay + 250`, `duration * 0.75`);
 * - [DustParticleCount] пылинок [DustPalette], развёртка слева направо
 *   (`(startX / w) · [Motion.ThanosSweepBaseMs]` + [Motion.ThanosSweepJitterMs]),
 *   траектория [Motion.OutCubic] на [Motion.ThanosParticlesMs];
 * - [onDissolved] — RN onComplete по завершении затухания контента (каскад
 *   обрезается на Hold-ритме дашборда — RN так же обрезает на 1150 мс).
 *
 * Пока [snap] false — просто контент. Повторный snap после сброса — новый
 * распад (дашборд перемонтирует обёртку вместе со снятием id из снап-набора).
 */
@Composable
public fun GtThanosSnap(
    snap: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    onDissolved: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var contentSize by remember { mutableStateOf(Size.Zero) }
    var particles by remember { mutableStateOf<List<DustParticleSpec>?>(null) }
    val contentAlpha = remember { Animatable(1f) }

    LaunchedEffect(snap, contentSize) {
        if (snap && contentSize.width > 0f && contentSize.height > 0f && particles == null) {
            particles = generateDustParticles(contentSize.width, contentSize.height, Random.Default)
            // Затухание контента (RN contentOpacity: delay + 250, duration*0.75).
            delay(delayMillis + Motion.ThanosContentDelayMs.toLong())
            contentAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = Motion.ThanosContentFadeMs, easing = Motion.InQuad),
            )
            onDissolved?.invoke()
        }
    }

    Box(modifier = modifier.onSizeChanged { contentSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { alpha = contentAlpha.value },
        ) {
            content()
        }
        particles?.let { specs ->
            DustCanvas(specs = specs, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * Слой пыли: один таймер-кадр ([Animatable]) → локальный прогресс каждой
 * пылинки из её задержки; положение/вращение/сжатие/прозрачность — RN
 * useAnimatedStyle DustParticle.
 */
@Composable
private fun DustCanvas(
    specs: List<DustParticleSpec>,
    modifier: Modifier = Modifier,
) {
    val totalMs = specs.maxOf { it.delayMs } + Motion.ThanosParticlesMs
    val elapsed = remember(specs) { Animatable(0f) }
    LaunchedEffect(specs) {
        elapsed.animateTo(1f, tween(durationMillis = totalMs.toInt()))
    }
    Canvas(modifier = modifier) {
        val t = elapsed.value * totalMs
        specs.forEach { spec ->
            val local = ((t - spec.delayMs) / Motion.ThanosParticlesMs).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val p = Motion.OutCubic.transform(local)
            // Прозрачность RN: вспышка в первые 12%, полная до 45%, затухание дальше.
            val alpha = when {
                p < 0.12f -> p / 0.12f
                p < 0.45f -> 1f
                else -> (1f - (p - 0.45f) / 0.55f).coerceAtLeast(0f)
            }
            val side = spec.sizeDp.dp.toPx()
            val rotation = spec.rotationDeg * p
            val scale = 1f - p * 0.35f
            withTransform({
                translate(left = spec.startX + spec.dx * p, top = spec.startY + spec.dy * p)
                rotate(degrees = rotation, pivot = Offset.Zero)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                drawRect(
                    color = DustPalette[spec.colorIndex].copy(alpha = alpha),
                    topLeft = Offset(-side / 2f, -side / 2f),
                    size = Size(side, side),
                )
            }
        }
    }
}
