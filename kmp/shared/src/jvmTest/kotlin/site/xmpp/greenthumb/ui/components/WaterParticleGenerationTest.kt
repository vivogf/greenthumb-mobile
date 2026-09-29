package site.xmpp.greenthumb.ui.components

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.Motion

/**
 * Инварианты генератора серии сердец полива (RN `generateParticles`,
 * WaterParticles.tsx): 8–12 сердцевин, диапазоны RN по дрейфу/подъёму/
 * вращению/размеру/задержке серии.
 */
class WaterParticleGenerationTest {

    @Test
    fun count_between_8_and_12() {
        repeat(30) { seed ->
            val count = generateWaterParticles(Random(seed)).size
            assertTrue(count in 8..12, "seed $seed → $count")
        }
    }

    @Test
    fun field_ranges_match_rn() {
        val particles = generateWaterParticles(Random(11))
        particles.forEach { p ->
            assertTrue(p.xDp in -40f..40f, "x ${p.xDp}")
            assertTrue(p.travelDp in 40f..70f, "travel ${p.travelDp}")
            assertTrue(p.rotationDeg in -30f..30f, "rotation ${p.rotationDeg}")
            assertTrue(p.sizeDp in 14f..22f, "size ${p.sizeDp}")
            assertTrue(p.delayMs in 0f..300f, "delay ${p.delayMs}")
        }
    }

    @Test
    fun ids_are_unique_sequential() {
        val particles = generateWaterParticles(Random(12))
        assertEquals(particles.size, particles.map { it.id }.distinct().size)
        assertEquals(particles.indices.toList(), particles.map { it.id })
    }

    @Test
    fun delay_never_exceeds_stagger_token() {
        val particles = generateWaterParticles(Random(13))
        particles.forEach { p ->
            assertTrue(p.delayMs <= Motion.WaterParticleStaggerMaxMs, "delay ${p.delayMs} выше токена")
        }
    }
}
