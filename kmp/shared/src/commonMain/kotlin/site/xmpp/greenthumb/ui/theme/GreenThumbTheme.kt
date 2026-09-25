package site.xmpp.greenthumb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Формы Material3 из [Radii]. Слоты `*Increased` / `extraExtraLarge` в RN нет:
 * туда кладётся [Radii.xl] (16), а не дефолт Material (20/28/32), чтобы тема
 * не приносила радиус вне шкалы. Пилюли (половина стороны) — не эти слоты.
 */
val GreenThumbShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.sm),
    small = RoundedCornerShape(Radii.md),
    medium = RoundedCornerShape(Radii.lg),
    large = RoundedCornerShape(Radii.xl),
    extraLarge = RoundedCornerShape(Radii.xl),
    largeIncreased = RoundedCornerShape(Radii.xl),
    extraLargeIncreased = RoundedCornerShape(Radii.xl),
    extraExtraLarge = RoundedCornerShape(Radii.xl),
)

/**
 * Тема приложения: Material3 [ColorScheme] плюс [LocalGreenThumbExtendedColors].
 *
 * [darkTheme] приходит снаружи. Переключатель light/dark/auto (AppSettings,
 * LocalAppTheme) — Stage 6; до него вызывающий передаёт флаг сам.
 */
@Composable
fun GreenThumbTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) GreenThumbExtendedColors.dark else GreenThumbExtendedColors.light
    val scheme = if (darkTheme) GreenThumbColors.dark else GreenThumbColors.light
    CompositionLocalProvider(LocalGreenThumbExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = GreenThumbShapes,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun greenThumbExtendedColors(): GreenThumbExtendedColors = LocalGreenThumbExtendedColors.current
