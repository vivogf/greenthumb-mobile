package site.xmpp.greenthumb.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Инварианты генератора пыли распада (RN `generateParticles`, ThanosSnap.tsx):
 * ровно 28 частиц, развёртка слева направо (задержка растёт с X), разлёт
 * в основном вправо и чуть вверх, палитра 8 цветов, диапазоны RN.
 */
class DustParticleGenerationTest {

    @Test
    fun exactly_28_particles() {
        repeat(20) {
            val particles = generateDustParticles(340f, 120f, Random(it))
            assertEquals(DustParticleCount, particles.size, "seed $it")
        }
    }

    @Test
    fun start_positions_inside_card() {
        val particles = generateDustParticles(340f, 120f, Random(1))
        particles.forEach { p ->
            assertTrue(p.startX in 0f..340f, "startX ${p.startX}")
            assertTrue(p.startY in 0f..120f, "startY ${p.startY}")
        }
    }

    @Test
    fun delay_is_sweep_base_share_plus_jitter() {
        val particles = generateDustParticles(340f, 120f, Random(2))
        particles.forEach { p ->
            // RN: delay = (startX / w) * 450 + random * 200.
            val sweepShare = p.delayMs - (p.startX / 340f) * 450f
            assertTrue(sweepShare in 0f..200f, "delay ${p.delayMs}, share $sweepShare")
        }
        // Лево-право развёртка: пылинка у левого края стартует раньше правой
        // (при равном джиттере; проверяем на средней величине по квантилям).
        val left = particles.filter { it.startX < 170f }.map { it.delayMs }
        val right = particles.filter { it.startX >= 170f }.map { it.delayMs }
        if (left.isNotEmpty() && right.isNotEmpty()) {
            assertTrue(left.average() <= right.average() + 200f, "sweep averages ${left.average()} vs ${right.average()}")
        }
    }

    @Test
    fun scatter_is_rightward_and_upward() {
        val particles = generateDustParticles(340f, 120f, Random(3))
        particles.forEach { p ->
            // RN: dx = cos(angle) * dist + 25, angle ∈ [-0.15π, 0.55π],
            // dist ∈ [50, 140] → dx ≥ cos(0.55π)·140 + 25 ≈ 3.1 > 0.
            assertTrue(p.dx > 0f, "dx ${p.dx} — разлёт вправо")
            // dy = -|sin(angle) * dist| - 8 → строго вверх.
            assertTrue(p.dy <= -8f, "dy ${p.dy} — разлёт вверх")
        }
    }

    @Test
    fun size_color_rotation_ranges() {
        val particles = generateDustParticles(340f, 120f, Random(4))
        particles.forEach { p ->
            assertTrue(p.sizeDp in 3f..9f, "size ${p.sizeDp}")
            assertTrue(p.colorIndex in DustPalette.indices, "colorIndex ${p.colorIndex}")
            assertTrue(abs(p.rotationDeg) <= 100f, "rotation ${p.rotationDeg}")
        }
        assertEquals(8, DustPalette.size, "палитра пыли — 8 цветов RN")
    }

    @Test
    fun angle_bounds_match_rn() {
        // Косвенная проверка угла: dx ∈ [cos(0.55π)·140+25, cos(−0.15π)·140+25].
        val lower = cos(0.55f * PI.toFloat()) * 140f + 25f
        val upper = cos(-0.15f * PI.toFloat()) * 140f + 25f
        val particles = generateDustParticles(340f, 120f, Random(5))
        particles.forEach { p ->
            assertTrue(p.dx >= lower - 0.01f && p.dx <= upper + 0.01f, "dx ${p.dx} вне [$lower, $upper]")
        }
    }
}
