package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/** Частица всплеска полива (RN `WaterParticles.tsx` Particle). */
internal data class WaterParticleSpec(
    val id: Int,
    /** Горизонтальный дрейф, dp: −40..+40 (RN `(Math.random() - 0.5) * 80`). */
    val xDp: Float,
    /** Подъём, dp: 40..70 (RN `40 + Math.random() * 30`). */
    val travelDp: Float,
    /** Градусы: −30..+30 (RN `(Math.random() - 0.5) * 60`). */
    val rotationDeg: Float,
    /** Сторона иконки, dp: 14..22 (RN `14 + Math.floor(Math.random() * 9)`). */
    val sizeDp: Float,
    /** Задержка серии, мс: 0..300 (RN `Math.random() * 300`). */
    val delayMs: Float,
)

/** Падение в конце дуги, dp (RN `* 12` — «slight drop at end»). */
private const val WaterDropBackDp: Float = 12f

/**
 * Генератор серии сердец полива (RN `generateParticles`, `WaterParticles.tsx:24`):
 * 8–12 сердцевин. Внутренняя — для jvmTest инвариантов.
 */
internal fun generateWaterParticles(random: Random): List<WaterParticleSpec> {
    val count = 8 + random.nextInt(5) // RN 8 + floor(random * 5) → 8..12
    return List(count) { id ->
        WaterParticleSpec(
            id = id,
            xDp = (random.nextFloat() - 0.5f) * 80f,
            travelDp = 40f + random.nextFloat() * 30f,
            rotationDeg = (random.nextFloat() - 0.5f) * 60f,
            sizeDp = (14 + random.nextInt(9)).toFloat(), // RN 14 + floor(random * 9)
            delayMs = random.nextFloat() * Motion.WaterParticleStaggerMaxMs,
        )
    }
}

/**
 * Кнопка полива с всплеском сердцевин — порт `components/WaterButtonWithParticles.tsx`
 * (+ `WaterParticles.tsx`): compact — круг 40 для списка/сетки, fullWidth — кнопка
 * с подписью для карточки/детали.
 *
 * Ритм RN handlePress: гаптика Medium ([Haptics.medium]), частицы, через
 * [Motion.WaterPressLeadMs] мутация, ещё [Motion.WaterParticlesHoldMs] частицы
 * держатся. Данные и эффект независимы в смысле M4 (architecture.md §7):
 * связка «данные ждут конца анимации» (RN-он successor массового полива
 * ждал 1150 мс) не переносится — полив оптимистичен в репозитории с момента
 * старта мутации; лид — компонентный ритм RN (без него оптимистичный
 * healthy-статус снимает кнопку в тот же кадр — всплеск не виден).
 *
 * Повторный тап во время всплеска/полива игнорируется (RN guard
 * `isWatering || showParticles`).
 *
 * Сердца рисуются на Canvas (Ionicons `heart` → свой Path-глиф, material-icons
 * в пинах миссии нет, прецедент GtMarks).
 */
@Composable
public fun GtWaterButton(
    onWater: () -> Unit,
    isWatering: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String? = null,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var showParticles by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // a11y-имя кнопки (RN accessibilityLabel); particles-оверлей — pointerEvents
    // none, имя несёт сама кнопка.
    val a11yLabel: String? = contentDescription

    fun handlePress() {
        if (isWatering || showParticles) return
        // RN Haptics.impactAsync(Medium) — точка тактильного отклика полива.
        Haptics.medium()
        showParticles = true
        // Ритм RN-компонента (WaterButtonWithParticles.handlePress): 400 мс
        // лид ([Motion.WaterPressLeadMs]) до мутации — иначе оптимистичная
        // запись переворачивает статус в тот же кадр и всплеск не виден
        // (в RN так же: лид держит кнопку смонтированной) — затем мутация и
        // удержание частиц 800 мс ([Motion.WaterParticlesHoldMs]; на дашборде
        // кнопку снимает healthy-статус раньше — RN-паритет, в детали играет
        // целиком). Связки «данные ждут КОНЦА эффекта» нет: запись
        // оптимистична в репозитории с момента старта мутации (M4),
        // вкладочный чек VAL-DASH-006 — по массовому поливу.
        scope.launch {
            delay(Motion.WaterPressLeadMs.toLong())
            onWater()
            delay(Motion.WaterParticlesHoldMs.toLong())
            showParticles = false
        }
    }

    // Центрируем и кнопку, и область частиц: сердца вылетают за границы
    // кнопки из её центра (RN left '50%' / top '50%' + overflow visible).
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (compact) {
            // Круг 40 (RN compact: 40×40, radius 20 → CircleShape; muted при поливе).
            Box(
                modifier = Modifier
                    .size(Spacing.xxl + Spacing.lg)
                    .background(if (isWatering) scheme.surfaceVariant else scheme.primary, CircleShape)
                    .clickable(enabled = !isWatering, role = Role.Button, onClick = ::handlePress)
                    .semantics { if (a11yLabel != null) this.contentDescription = a11yLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (isWatering) {
                    CircularProgressIndicator(
                        color = scheme.onPrimary,
                        modifier = Modifier.size(Spacing.lg + Spacing.xxs),
                    )
                } else {
                    GtWaterDropMark(
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(Spacing.lg + Spacing.xxs),
                    )
                }
            }
        } else {
            // Полная ширина (RN fullWidth: radius 10 → Radii.md, gap 8 → Spacing.xs).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.primary, RoundedCornerShape(Radii.md))
                    .clickable(enabled = !isWatering, role = Role.Button, onClick = ::handlePress)
                    .semantics { if (a11yLabel != null) this.contentDescription = a11yLabel }
                    .padding(vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
            ) {
                if (isWatering) {
                    CircularProgressIndicator(
                        color = scheme.onPrimary,
                        modifier = Modifier.size(Spacing.md + Spacing.xxs),
                    )
                } else {
                    GtWaterDropMark(
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(Spacing.md + Spacing.xxs),
                    )
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimary,
                    )
                }
            }
        }
        // Всплеск поверх кнопки (RN WaterParticles sibling-оверлей z-index 999,
        // pointerEvents none): не кликабелен, вылетает за границы кнопки.
        if (showParticles) {
            GtWaterParticles(color = scheme.primary, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * Серия сердец из центра кнопки (RN WaterParticles): 8–12 штук, вверх с
 * разбросом, вращением и масштабом; прогресс серии — [Motion.OutCubic] на
 * [Motion.WaterParticlesMs] с задержками до [Motion.WaterParticleStaggerMaxMs].
 */
@Composable
private fun BoxScope.GtWaterParticles(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val specs = remember { generateWaterParticles(Random.Default) }
    val elapsed = remember { Animatable(0f) }
    val totalMs = (Motion.WaterParticlesMs + Motion.WaterParticleStaggerMaxMs).toFloat()
    LaunchedEffect(specs) {
        elapsed.animateTo(1f, tween(durationMillis = totalMs.toInt()))
    }
    Canvas(modifier = modifier) {
        // Область рисования = размер кнопки (matchParentSize не влияет на
        // компоновку — RN position:absolute); сердца рисуются за границами
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
            // Вверх по дуге, в конце лёгкое падение (RN translateY).
            val offsetYDp = if (p < 0.75f) {
                -(p / 0.75f) * spec.travelDp
            } else {
                -spec.travelDp + ((p - 0.75f) / 0.25f) * WaterDropBackDp
            }
            val alpha = if (p < 0.8f) 1f else (1f - (p - 0.8f) / 0.2f).coerceAtLeast(0f)
            val side = spec.sizeDp.dp.toPx()
            val offsetX = spec.xDp.dp.toPx() * p
            val offsetY = offsetYDp.dp.toPx()
            withTransform({
                translate(left = centerX + offsetX, top = centerY + offsetY)
                rotate(degrees = spec.rotationDeg * p, pivot = Offset.Zero)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                drawHeart(side = side, color = color.copy(alpha = alpha))
            }
        }
    }
}

/** Сердце — Ionicons `heart` (заливка), глиф в квадрате [side]×[side] вокруг нуля. */
private fun DrawScope.drawHeart(side: Float, color: Color) {
    val path = Path().apply {
        moveTo(0.5f * side, 0.85f * side)
        cubicTo(0.2f * side, 0.6f * side, 0.05f * side, 0.42f * side, 0.05f * side, 0.28f * side)
        cubicTo(0.05f * side, 0.12f * side, 0.2f * side, 0.05f * side, 0.32f * side, 0.05f * side)
        cubicTo(0.4f * side, 0.05f * side, 0.47f * side, 0.09f * side, 0.5f * side, 0.15f * side)
        cubicTo(0.53f * side, 0.09f * side, 0.6f * side, 0.05f * side, 0.68f * side, 0.05f * side)
        cubicTo(0.8f * side, 0.05f * side, 0.95f * side, 0.12f * side, 0.95f * side, 0.28f * side)
        cubicTo(0.95f * side, 0.42f * side, 0.8f * side, 0.6f * side, 0.5f * side, 0.85f * side)
        close()
    }
    drawPath(path, color)
}
