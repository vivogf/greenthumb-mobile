package site.xmpp.greenthumb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * VAL-LOOK-005 / VAL-LOOK-006: dark «Лес» = the D§3.1 token table of the approved mockup
 * (`body[data-style=solar]`), light «Лес» = the derived variant of architecture §9.3.
 * Expected values are literals here, not read from the implementation.
 */
class ThemeTokensTest {

    @Test
    fun dark_scheme_matches_forest_tokens() {
        val scheme = GreenThumbColors.dark
        assertHex(scheme.background, "#0e1610")
        assertHex(scheme.surfaceContainerLowest, "#0e1610")
        assertHex(scheme.surface, "#152018")
        assertHex(scheme.surfaceContainer, "#152018")
        assertHex(scheme.surfaceVariant, "#1c2a1f")
        assertHex(scheme.surfaceContainerHigh, "#1c2a1f")
        assertHex(scheme.outline, "#2a3d2e")
        assertHex(scheme.outlineVariant, "#2a3d2e")
        assertHex(scheme.onBackground, "#eef4ec")
        assertHex(scheme.onSurface, "#eef4ec")
        assertHex(scheme.onSurfaceVariant, "#a8b9a6")
        assertHex(scheme.primary, "#277a40")
        assertHex(scheme.onPrimary, "#f0f9f2")
        assertHex(scheme.secondary, "#3da35b")
        assertHex(scheme.tertiary, "#86d69c")
        assertHex(scheme.error, "#ee8e72")
    }

    @Test
    fun dark_extended_matches_forest_tokens() {
        val dark = GreenThumbExtendedColors.dark
        assertHex(dark.page, "#0a100b")
        assertHex(dark.accentText, "#86d69c")
        assertHex(dark.amber, "#e0a25a")
        assertHex(dark.glow, "#7fe3a0")
        assertHex(dark.causeHigh, "#ee8e72")
        assertHex(dark.causeLow, "#a39b8c")
        assertHex(dark.cardBorder, "#2a3d2e")
        assertRgba(dark.amberBg, 0xE0, 0xA2, 0x5A, 0.12f, "dark.amberBg")
        assertRgba(dark.amberBorder, 0xE0, 0xA2, 0x5A, 0.25f, "dark.amberBorder")
        assertRgba(dark.chipSelected, 0x4A, 0x9A, 0x5A, 0.2f, "dark.chipSelected")
    }

    @Test
    fun light_scheme_is_the_derived_forest_variant() {
        val scheme = GreenThumbColors.light
        assertHex(scheme.background, "#f3f6f1")
        assertHex(scheme.surface, "#ffffff")
        assertHex(scheme.surfaceVariant, "#e7ede4")
        assertHex(scheme.outline, "#cbd6c7")
        assertHex(scheme.onBackground, "#142016")
        assertHex(scheme.onSurface, "#142016")
        assertHex(scheme.onSurfaceVariant, "#4e5e4d")
        assertHex(scheme.primary, "#277a40")
        assertHex(scheme.onPrimary, "#f0f9f2")
        assertHex(scheme.secondary, "#2e8a4a")
        assertHex(scheme.tertiary, "#1f6b36")
        assertHex(scheme.error, "#b4472a")

        val light = GreenThumbExtendedColors.light
        assertHex(light.page, "#f3f6f1")
        assertHex(light.amber, "#a86412")
        assertHex(light.causeHigh, "#b4472a")
        assertHex(light.accentText, "#1f6b36")
    }

    @Test
    fun light_differs_from_dark_and_drops_the_old_palettes() {
        val old = listOf(
            "#ffffff" to "background", "#fafafa" to "surface", "#2e7740" to "primary", "#1a1a1a" to "onSurface",
            "#15130e" to "background", "#1c1a14" to "surface", "#4a9a5a" to "primary",
        )
        val light = GreenThumbColors.light
        val dark = GreenThumbColors.dark
        for (role in listOf("background", "surface", "onSurface", "secondary", "tertiary")) {
            assertTrue(role(light, role) != role(dark, role), "light and dark share $role")
        }
        for ((hex, role) in old) {
            for (scheme in listOf(light, dark)) {
                assertTrue(role(scheme, role).toArgb() != argb(hex), "$role still uses the old $hex")
            }
        }
    }

    @Test
    fun text_roles_have_at_least_4_5_contrast() {
        for ((name, scheme) in listOf("light" to GreenThumbColors.light, "dark" to GreenThumbColors.dark)) {
            val pairs = listOf(
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onBackground/background" to (scheme.onBackground to scheme.background),
                "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
                "onSurfaceVariant/background" to (scheme.onSurfaceVariant to scheme.background),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "tertiary/surface" to (scheme.tertiary to scheme.surface),
                "tertiary/background" to (scheme.tertiary to scheme.background),
                "error/surface" to (scheme.error to scheme.surface),
                "onError/error" to (scheme.onError to scheme.error),
            )
            for ((label, pair) in pairs) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue(ratio >= 4.5, "$name $label contrast $ratio < 4.5")
            }
            // D§3.1: acc (secondary) never carries text; onSecondary only marks icons on it (non-text, 3:1).
            val onAccent = contrast(scheme.onSecondary, scheme.secondary)
            assertTrue(onAccent >= 3.0, "$name onSecondary/secondary contrast $onAccent < 3")
        }
        for ((name, ext, scheme) in listOf(
            Triple("light", GreenThumbExtendedColors.light, GreenThumbColors.light),
            Triple("dark", GreenThumbExtendedColors.dark, GreenThumbColors.dark),
        )) {
            for ((label, c) in listOf("amber" to ext.amber, "causeHigh" to ext.causeHigh, "causeLow" to ext.causeLow)) {
                val ratio = contrast(c, scheme.surface)
                assertTrue(ratio >= 4.5, "$name $label on surface contrast $ratio < 4.5")
            }
        }
    }

    @Test
    fun extended_card_border_is_the_outline_variant() {
        assertEquals(GreenThumbColors.light.outlineVariant.toArgb(), GreenThumbExtendedColors.light.cardBorder.toArgb())
        assertEquals(GreenThumbColors.dark.outlineVariant.toArgb(), GreenThumbExtendedColors.dark.cardBorder.toArgb())
    }

    @Test
    fun scheme_slots_stay_inside_palette_or_modal_scrim() {
        val allowed = paletteArgb() + GreenThumbColors.modalScrim.toArgb() + GreenThumbColors.darkScrim.toArgb()
        for (scheme in listOf(GreenThumbColors.light, GreenThumbColors.dark)) {
            for ((name, color) in slots(scheme)) {
                assertTrue(color.toArgb() in allowed, "$name ${color.toArgb().toUInt().toString(16)} is not a «Лес» token or the modal scrim")
            }
        }
        assertRgba(GreenThumbColors.modalScrim, 0, 0, 0, 0.5f, "modalScrim")
        assertEquals(GreenThumbColors.modalScrim, GreenThumbColors.light.scrim)
        assertRgba(GreenThumbColors.darkScrim, 0x0A, 0x10, 0x0B, 0.6f, "darkScrim")
        assertEquals(GreenThumbColors.darkScrim, GreenThumbColors.dark.scrim)
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
    fun layout_sizes_match_design() {
        assertEquals(48.dp, Sizes.touch)
        assertEquals(56.dp, Sizes.sheetRow)
        assertEquals(24.dp, Sizes.iconSlot)
        assertEquals(Spacing.lg, Sizes.screenPadding)
        assertEquals(Spacing.lg, Sizes.cardPadding)
        assertEquals(Spacing.sm, Sizes.cardGap)
    }

    @Test
    fun radii_are_the_rn_scale() {
        assertEquals(8.dp, Radii.sm)
        assertEquals(10.dp, Radii.md)
        assertEquals(12.dp, Radii.lg)
        assertEquals(16.dp, Radii.xl)
        assertEquals(24.dp, Radii.card)
        assertEquals(16.dp, Radii.field)
        assertEquals(RoundedCornerShape(percent = 50), Radii.pill)
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
        assertEquals(1100, Motion.WaterParticlesMs)
        assertEquals(200, Motion.WaterParticleStaggerMaxMs)
        assertEquals(400, Motion.WaterPressLeadMs)
        assertEquals(1100, Motion.WaterParticlesHoldMs)
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
        assertHex(seen.darkPrimary ?: error("dark primary not composed"), "#277a40")
        assertHex(seen.darkAmber ?: error("dark amber not composed"), "#e0a25a")
        assertHex(seen.darkCardBorder ?: error("dark cardBorder not composed"), "#2a3d2e")
        assertEquals(RoundedCornerShape(Radii.md), seen.darkShape)
        assertHex(seen.lightBackground ?: error("light background not composed"), "#f3f6f1")
        assertRgba(seen.lightAmberBg ?: error("light amberBg not composed"), 0xA8, 0x64, 0x12, 0.08f, "composed amberBg")
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

private fun argb(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

private fun paletteArgb(): Set<Int> = listOf(
    // dark «Лес»
    "#0e1610", "#152018", "#1c2a1f", "#2a3d2e", "#eef4ec", "#a8b9a6", "#277a40", "#f0f9f2",
    "#3da35b", "#86d69c", "#ee8e72", "#0a100b",
    // light «Лес»
    "#f3f6f1", "#ffffff", "#e7ede4", "#cbd6c7", "#142016", "#4e5e4d", "#2e8a4a", "#1f6b36", "#b4472a",
).map(::argb).toSet()

private fun role(scheme: ColorScheme, name: String): Color = when (name) {
    "background" -> scheme.background
    "surface" -> scheme.surface
    "onSurface" -> scheme.onSurface
    "primary" -> scheme.primary
    "secondary" -> scheme.secondary
    "tertiary" -> scheme.tertiary
    else -> error(name)
}

/** WCAG 2 contrast ratio of two opaque colours. */
private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
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
