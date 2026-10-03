package site.xmpp.greenthumb.ui.components

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.Motion

/** Invariants of the water-burst generator: 14-18 hearts, wide fan, varied size and opacity. */
class WaterParticleGenerationTest {

    @Test
    fun count_between_14_and_18() {
        repeat(30) { seed ->
            val count = generateWaterParticles(Random(seed)).size
            assertTrue(count in 14..18, "seed $seed -> $count")
        }
    }

    @Test
    fun field_ranges() {
        val particles = generateWaterParticles(Random(11))
        particles.forEach { p ->
            assertTrue(p.xDp in -90f..90f, "x ${p.xDp}")
            assertTrue(p.travelDp in 70f..130f, "travel ${p.travelDp}")
            assertTrue(p.rotationDeg in -40f..40f, "rotation ${p.rotationDeg}")
            assertTrue(p.sizeDp in 21f..33f, "size ${p.sizeDp}")
            assertTrue(p.delayMs in 0f..Motion.WaterParticleStaggerMaxMs.toFloat(), "delay ${p.delayMs}")
            assertTrue(p.shade in 0 until WaterParticleShades, "shade ${p.shade}")
            assertTrue(p.peakAlpha in 0.65f..1f, "alpha ${p.peakAlpha}")
        }
    }

    @Test
    fun hearts_vary_in_size_and_opacity() {
        val particles = generateWaterParticles(Random(5))
        assertTrue(particles.map { it.sizeDp }.distinct().size > 3, "sizes vary")
        assertTrue(particles.map { it.peakAlpha }.distinct().size > 3, "opacities vary")
    }

    @Test
    fun ids_are_unique_sequential() {
        val particles = generateWaterParticles(Random(12))
        assertEquals(particles.size, particles.map { it.id }.distinct().size)
        assertEquals(particles.indices.toList(), particles.map { it.id })
    }
}
