package site.xmpp.greenthumb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import site.xmpp.greenthumb.core.storage.ThemePreference

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
 * Effective-схема из предпочтения и системы — порт RN contexts/ThemeContext.tsx
 * (`effective = preference === 'auto' ? system dark : preference`). null
 * («ключ ещё не прочитан / не задан») — дефолт RN auto: следует системе.
 * Явные light/dark перекрывают систему (VAL-THEME-002); auto следует за ней
 * (VAL-THEME-001).
 */
public fun resolveDarkTheme(preference: ThemePreference?, systemDark: Boolean): Boolean =
    when (preference) {
        ThemePreference.Dark -> true
        ThemePreference.Light -> false
        ThemePreference.Auto, null -> systemDark
    }

/**
 * Тема приложения: Material3 [ColorScheme] плюс [LocalGreenThumbExtendedColors].
 *
 * [darkTheme] обычно приходит из [resolveDarkTheme]: предпочтение light/dark/auto
 * из AppSettings (`greenthumb_theme`, Stage 6 п.3) + системная схема через
 * [site.xmpp.greenthumb.core.platform.isSystemDarkTheme] (androidMain —
 * LocalConfiguration/UI_MODE_NIGHT_MASK; jvmMain — LocalSystemTheme).
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
