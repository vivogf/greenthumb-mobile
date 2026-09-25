package site.xmpp.greenthumb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * VAL-DS-001: каждый токен light/dark равен hex (или rgba) из `lib/constants.ts:24-60`.
 * Эталон зашит здесь литералами, не читается из реализации.
 */
class ThemeTokensTest {

    @Test
    fun light_scheme_matches_constants_hex() {
        val scheme = GreenThumbColors.light
        assertHex(scheme.background, "#ffffff")
        assertHex(scheme.onBackground, "#1a1a1a")
        assertHex(scheme.surface, "#fafafa")
        assertHex(scheme.onSurface, "#1a1a1a")
        assertHex(scheme.outline, "#e5e5e5")
        assertHex(scheme.outlineVariant, "#f0f0f0")
        assertHex(scheme.surfaceVariant, "#ebebea")
        assertHex(scheme.onSurfaceVariant, "#8a8a85")
        assertHex(scheme.primary, "#2e7740")
        assertHex(scheme.onPrimary, "#f0f9f2")
        assertHex(scheme.error, "#993333")
        assertHex(scheme.onError, "#fef2f2")
        assertHex(scheme.surfaceContainerHighest, "#c0c0c0")
        assertHex(scheme.surfaceTint, "#2e7740")
        assertHex(scheme.inversePrimary, "#4a9a5a")
        assertHex(scheme.inverseSurface, "#1c1a14")
        assertHex(scheme.inverseOnSurface, "#f3f2ef")
    }

    @Test
    fun dark_scheme_matches_constants_hex() {
        val scheme = GreenThumbColors.dark
        assertHex(scheme.background, "#15130e")
        assertHex(scheme.onBackground, "#f3f2ef")
        assertHex(scheme.surface, "#1c1a14")
        assertHex(scheme.onSurface, "#f3f2ef")
        assertHex(scheme.outline, "#302d27")
        assertHex(scheme.outlineVariant, "#272420")
        assertHex(scheme.surfaceVariant, "#2a2822")
        assertHex(scheme.onSurfaceVariant, "#a89f92")
        assertHex(scheme.primary, "#4a9a5a")
        assertHex(scheme.onPrimary, "#f0f9f2")
        assertHex(scheme.error, "#a33535")
        assertHex(scheme.onError, "#fef2f2")
        assertHex(scheme.surfaceContainerHighest, "#3d3b35")
        assertHex(scheme.surfaceTint, "#4a9a5a")
        assertHex(scheme.inversePrimary, "#2e7740")
        assertHex(scheme.inverseSurface, "#fafafa")
        assertHex(scheme.inverseOnSurface, "#1a1a1a")
    }

    @Test
    fun extended_colors_match_constants() {
        val light = GreenThumbExtendedColors.light
        assertHex(light.amber, "#d97706")
        assertRgba(light.amberBg, 217, 119, 6, 0.08f, "light.amberBg")
        assertRgba(light.amberBorder, 217, 119, 6, 0.20f, "light.amberBorder")
        assertHex(light.cardBorder, "#f0f0f0")

        val dark = GreenThumbExtendedColors.dark
        assertHex(dark.amber, "#d97706")
        assertRgba(dark.amberBg, 217, 119, 6, 0.12f, "dark.amberBg")
        assertRgba(dark.amberBorder, 217, 119, 6, 0.25f, "dark.amberBorder")
        assertHex(dark.cardBorder, "#272420")

        assertEquals(GreenThumbColors.light.outlineVariant.toArgb(), light.cardBorder.toArgb())
        assertEquals(GreenThumbColors.dark.outlineVariant.toArgb(), dark.cardBorder.toArgb())
    }

    @Test
    fun scheme_slots_stay_inside_palette_or_modal_scrim() {
        val allowed = paletteArgb() + GreenThumbColors.modalScrim.toArgb()
        for (scheme in listOf(GreenThumbColors.light, GreenThumbColors.dark)) {
            for ((name, color) in slots(scheme)) {
                assertTrue(color.toArgb() in allowed, "$name ${color.toArgb().toUInt().toString(16)} is not a constants.ts color or the modal scrim")
            }
        }
        assertRgba(GreenThumbColors.modalScrim, 0, 0, 0, 0.5f, "modalScrim")
        assertEquals(GreenThumbColors.modalScrim, GreenThumbColors.light.scrim)
        assertEquals(GreenThumbColors.modalScrim, GreenThumbColors.dark.scrim)
    }

    @Test
    fun spacing_is_the_rn_scale() {
        assertEquals(4.dp, Spacing.xxs)
        assertEquals(8.dp, Spacing.xs)
        assertEquals(12.dp, Spacing.sm)
        assertEquals(14.dp, Spacing.md)
        assertEquals(16.dp, Spacing.lg)
        assertEquals(20.dp, Spacing.xl)
        assertEquals(24.dp, Spacing.xxl)
    }

    @Test
    fun radii_are_the_rn_scale() {
        assertEquals(8.dp, Radii.sm)
        assertEquals(10.dp, Radii.md)
        assertEquals(12.dp, Radii.lg)
        assertEquals(16.dp, Radii.xl)
        assertEquals(RoundedCornerShape(Radii.sm), GreenThumbShapes.extraSmall)
        assertEquals(RoundedCornerShape(Radii.md), GreenThumbShapes.small)
        assertEquals(RoundedCornerShape(Radii.lg), GreenThumbShapes.medium)
        assertEquals(RoundedCornerShape(Radii.xl), GreenThumbShapes.large)
        assertEquals(RoundedCornerShape(Radii.xl), GreenThumbShapes.extraLarge)
        assertEquals(RoundedCornerShape(Radii.xl), GreenThumbShapes.largeIncreased)
        assertEquals(RoundedCornerShape(Radii.xl), GreenThumbShapes.extraLargeIncreased)
        assertEquals(RoundedCornerShape(Radii.xl), GreenThumbShapes.extraExtraLarge)
    }

    @Test
    fun motion_matches_rn_durations_and_easings() {
        assertEquals(300, Motion.ListEnterMs)
        assertEquals(40, Motion.ListEnterStaggerMs)
        assertEquals(10f, Motion.ListLayoutDamping)
        assertEquals(1f, Motion.ListLayoutMass)
        assertEquals(100f, Motion.ListLayoutStiffness)
        assertEquals(11f, Motion.GridLayoutDamping)
        assertEquals(1f, Motion.GridLayoutMass)
        assertEquals(90f, Motion.GridLayoutStiffness)
        assertEquals(800, Motion.SkeletonPulseMs)
        assertEquals(1800, Motion.WaterParticlesMs)
        assertEquals(300, Motion.WaterParticleStaggerMaxMs)
        assertEquals(400, Motion.WaterPressLeadMs)
        assertEquals(800, Motion.WaterParticlesHoldMs)
        assertEquals(1300, Motion.ThanosParticlesMs)
        assertEquals(250, Motion.ThanosContentDelayMs)
        assertEquals(975, Motion.ThanosContentFadeMs)
        assertEquals(1300 * 3 / 4, Motion.ThanosContentFadeMs)
        assertEquals(450, Motion.ThanosSweepBaseMs)
        assertEquals(200, Motion.ThanosSweepJitterMs)
        assertEquals(1150, Motion.BulkWaterSnapMs)
        assertEquals(2400, Motion.BulkWaterSuccessBannerMs)

        assertEquals(0f, Motion.OutCubic.transform(0f))
        assertEquals(1f, Motion.OutCubic.transform(1f))
        assertEquals(0.875f, Motion.OutCubic.transform(0.5f))
        assertEquals(0.25f, Motion.InQuad.transform(0.5f))
        assertEquals(0.125f, Motion.InOutQuad.transform(0.25f))
        assertEquals(0.5f, Motion.InOutQuad.transform(0.5f))
        assertEquals(0.875f, Motion.InOutQuad.transform(0.75f))
    }

    @Test
    fun theme_wraps_material_and_provides_extended_colors() {
        val seen = ThemeSample()
        compose {
            GreenThumbTheme(darkTheme = true) {
                seen.darkPrimary = MaterialTheme.colorScheme.primary
                seen.darkAmber = LocalGreenThumbExtendedColors.current.amber
                seen.darkCardBorder = greenThumbExtendedColors().cardBorder
                seen.darkShape = MaterialTheme.shapes.small
            }
            GreenThumbTheme(darkTheme = false) {
                seen.lightBackground = MaterialTheme.colorScheme.background
                seen.lightAmberBg = LocalGreenThumbExtendedColors.current.amberBg
            }
        }
        assertHex(seen.darkPrimary ?: error("dark primary not composed"), "#4a9a5a")
        assertHex(seen.darkAmber ?: error("dark amber not composed"), "#d97706")
        assertHex(seen.darkCardBorder ?: error("dark cardBorder not composed"), "#272420")
        assertEquals(RoundedCornerShape(Radii.md), seen.darkShape)
        assertHex(seen.lightBackground ?: error("light background not composed"), "#ffffff")
        assertRgba(seen.lightAmberBg ?: error("light amberBg not composed"), 217, 119, 6, 0.08f, "composed amberBg")
    }
}

private class ThemeSample {
    var darkPrimary: Color? = null
    var darkAmber: Color? = null
    var darkCardBorder: Color? = null
    var darkShape: androidx.compose.foundation.shape.CornerBasedShape? = null
    var lightBackground: Color? = null
    var lightAmberBg: Color? = null
}

private class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}

private fun compose(content: @Composable () -> Unit) = runBlocking {
    withTimeout(5_000) {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(UnitApplier(), recomposer)
        val job = launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent(content)
        } finally {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }
}

private fun assertHex(color: Color, hex: String) {
    val rgb = hex.removePrefix("#").toLong(16)
    val expected = (0xFF000000L or rgb).toInt()
    assertEquals(expected, color.toArgb(), hex)
}

/**
 * Compose Color пакует sRGB в 8 бит на канал, поэтому `Color(alpha = 0.08f)`
 * читается обратно как 20/255, а не 0.08. Сравнение — с цветом, собранным из
 * тех же чисел constants.ts, и с байтом `round(cssAlpha * 255)`.
 */
private fun assertRgba(color: Color, red: Int, green: Int, blue: Int, alpha: Float, label: String) {
    val expected = Color(red / 255f, green / 255f, blue / 255f, alpha)
    assertEquals(expected, color, label)
    val argb = color.toArgb()
    assertEquals(red, (argb shr 16) and 0xFF, "$label red")
    assertEquals(green, (argb shr 8) and 0xFF, "$label green")
    assertEquals(blue, argb and 0xFF, "$label blue")
    val alphaByte = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    assertEquals(alphaByte, (argb ushr 24) and 0xFF, "$label alpha")
}

private fun paletteArgb(): Set<Int> = listOf(
    "#ffffff", "#fafafa", "#f0f0f0", "#1a1a1a", "#e5e5e5", "#ebebea", "#8a8a85",
    "#2e7740", "#f0f9f2", "#993333", "#fef2f2", "#c0c0c0",
    "#15130e", "#1c1a14", "#272420", "#f3f2ef", "#302d27", "#2a2822", "#a89f92",
    "#4a9a5a", "#a33535", "#3d3b35", "#d97706",
).map { hex ->
    val rgb = hex.removePrefix("#").toLong(16)
    (0xFF000000L or rgb).toInt()
}.toSet()

private fun slots(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
    "primary" to scheme.primary,
    "onPrimary" to scheme.onPrimary,
    "primaryContainer" to scheme.primaryContainer,
    "onPrimaryContainer" to scheme.onPrimaryContainer,
    "inversePrimary" to scheme.inversePrimary,
    "secondary" to scheme.secondary,
    "onSecondary" to scheme.onSecondary,
    "secondaryContainer" to scheme.secondaryContainer,
    "onSecondaryContainer" to scheme.onSecondaryContainer,
    "tertiary" to scheme.tertiary,
    "onTertiary" to scheme.onTertiary,
    "tertiaryContainer" to scheme.tertiaryContainer,
    "onTertiaryContainer" to scheme.onTertiaryContainer,
    "background" to scheme.background,
    "onBackground" to scheme.onBackground,
    "surface" to scheme.surface,
    "onSurface" to scheme.onSurface,
    "surfaceVariant" to scheme.surfaceVariant,
    "onSurfaceVariant" to scheme.onSurfaceVariant,
    "surfaceTint" to scheme.surfaceTint,
    "inverseSurface" to scheme.inverseSurface,
    "inverseOnSurface" to scheme.inverseOnSurface,
    "error" to scheme.error,
    "onError" to scheme.onError,
    "errorContainer" to scheme.errorContainer,
    "onErrorContainer" to scheme.onErrorContainer,
    "outline" to scheme.outline,
    "outlineVariant" to scheme.outlineVariant,
    "scrim" to scheme.scrim,
    "surfaceBright" to scheme.surfaceBright,
    "surfaceDim" to scheme.surfaceDim,
    "surfaceContainer" to scheme.surfaceContainer,
    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
    "surfaceContainerLow" to scheme.surfaceContainerLow,
    "surfaceContainerLowest" to scheme.surfaceContainerLowest,
    "primaryFixed" to scheme.primaryFixed,
    "primaryFixedDim" to scheme.primaryFixedDim,
    "onPrimaryFixed" to scheme.onPrimaryFixed,
    "onPrimaryFixedVariant" to scheme.onPrimaryFixedVariant,
    "secondaryFixed" to scheme.secondaryFixed,
    "secondaryFixedDim" to scheme.secondaryFixedDim,
    "onSecondaryFixed" to scheme.onSecondaryFixed,
    "onSecondaryFixedVariant" to scheme.onSecondaryFixedVariant,
    "tertiaryFixed" to scheme.tertiaryFixed,
    "tertiaryFixedDim" to scheme.tertiaryFixedDim,
    "onTertiaryFixed" to scheme.onTertiaryFixed,
    "onTertiaryFixedVariant" to scheme.onTertiaryFixedVariant,
)
