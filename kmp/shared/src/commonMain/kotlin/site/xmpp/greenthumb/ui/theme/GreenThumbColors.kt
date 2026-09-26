package site.xmpp.greenthumb.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Палитра 1:1 с `lib/constants.ts:24-60` (hex и rgba дословно, не «похоже»).
 *
 * В ColorScheme попадают роли, у которых есть слот Material3. Четыре роли без
 * слота — [GreenThumbExtendedColors]. Слоты, которых в RN нет (secondary,
 * tertiary, container, fixed), не получают нового hex: они переиспользуют
 * цвет из той же палитры, иначе Material3 подставит свой фиолетовый дефолт.
 *
 * Прямое соответствие (оба режима):
 * - background → background
 * - foreground → onBackground и onSurface (текст и на фоне, и на карточке)
 * - card → surface
 * - border → outline
 * - muted → surfaceVariant
 * - mutedForeground → onSurfaceVariant
 * - primary → primary
 * - primaryForeground → onPrimary
 * - destructive → error
 * - destructiveForeground → onError
 * - input → surfaceContainerHighest (фон поля, `login.tsx` inputStyle)
 * - ring → surfaceTint (в constants.ts равен primary; в RN `colors.ring` не читается)
 * - cardBorder → outlineVariant (именованный доступ — ещё и в extended)
 *
 * Обратные роли (inverse*) берутся из палитры другого режима — те же hex
 * из constants.ts, не вычисленные.
 *
 * scrim — не из constants.ts: подложка модалок RN `rgba(0,0,0,0.5)`
 * (AlertDialog, profile, HandoffKeyModal). См. [modalScrim].
 */
object GreenThumbColors {
    /**
     * Подложка модалок. Не токен constants.ts: `rgba(0,0,0,0.5)` в
     * `components/AlertDialog.tsx`, `app/(tabs)/profile.tsx`,
     * `components/HandoffKeyModal.tsx`.
     * Объявлен до схем: [scheme] читает его в инициализаторе.
     */
    val modalScrim: Color = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.5f)

    /**
     * `#22c55e` — успех показа ключа (RN `app/(auth)/login.tsx`: иконка
     * checkmark-circle и её фон `#22c55e22`). Не токен constants.ts —
     * литерал RN-экрана; портирован дословно как роль темы, чтобы экран не
     * собирал `Color(` в обход K5 (тот же приём, что [modalScrim]).
     * В light и dark один и тот же.
     */
    val success: Color = Color(red = 0x22 / 255f, green = 0xC5 / 255f, blue = 0x5E / 255f)

    val light: ColorScheme = scheme(LightPalette, DarkPalette, isLight = true)
    val dark: ColorScheme = scheme(DarkPalette, LightPalette, isLight = false)
}

private data class Palette(
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val foreground: Color,
    val border: Color,
    val muted: Color,
    val mutedForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val input: Color,
    val ring: Color,
)

private val LightPalette = Palette(
    background = rgb(0xFFFFFF),
    card = rgb(0xFAFAFA),
    cardBorder = rgb(0xF0F0F0),
    foreground = rgb(0x1A1A1A),
    border = rgb(0xE5E5E5),
    muted = rgb(0xEBEBEA),
    mutedForeground = rgb(0x8A8A85),
    primary = rgb(0x2E7740),
    primaryForeground = rgb(0xF0F9F2),
    destructive = rgb(0x993333),
    destructiveForeground = rgb(0xFEF2F2),
    input = rgb(0xC0C0C0),
    ring = rgb(0x2E7740),
)

private val DarkPalette = Palette(
    background = rgb(0x15130E),
    card = rgb(0x1C1A14),
    cardBorder = rgb(0x272420),
    foreground = rgb(0xF3F2EF),
    border = rgb(0x302D27),
    muted = rgb(0x2A2822),
    mutedForeground = rgb(0xA89F92),
    primary = rgb(0x4A9A5A),
    primaryForeground = rgb(0xF0F9F2),
    destructive = rgb(0xA33535),
    destructiveForeground = rgb(0xFEF2F2),
    input = rgb(0x3D3B35),
    ring = rgb(0x4A9A5A),
)

/**
 * Все слоты заданы явно: дефолты `lightColorScheme`/`darkColorScheme` не
 * используются. Фабрика light/dark одна и та же, потому что аргументы полные.
 */
private fun scheme(p: Palette, inverse: Palette, isLight: Boolean): ColorScheme = lightColorScheme(
    primary = p.primary,
    onPrimary = p.primaryForeground,
    primaryContainer = p.muted,
    onPrimaryContainer = p.foreground,
    inversePrimary = inverse.primary,
    secondary = p.primary,
    onSecondary = p.primaryForeground,
    secondaryContainer = p.muted,
    onSecondaryContainer = p.foreground,
    tertiary = p.primary,
    onTertiary = p.primaryForeground,
    tertiaryContainer = p.muted,
    onTertiaryContainer = p.foreground,
    background = p.background,
    onBackground = p.foreground,
    surface = p.card,
    onSurface = p.foreground,
    surfaceVariant = p.muted,
    onSurfaceVariant = p.mutedForeground,
    surfaceTint = p.ring,
    inverseSurface = inverse.card,
    inverseOnSurface = inverse.foreground,
    error = p.destructive,
    onError = p.destructiveForeground,
    errorContainer = p.destructive,
    onErrorContainer = p.destructiveForeground,
    outline = p.border,
    outlineVariant = p.cardBorder,
    scrim = GreenThumbColors.modalScrim,
    surfaceBright = if (isLight) p.background else p.input,
    surfaceDim = if (isLight) p.muted else p.background,
    surfaceContainerLowest = p.background,
    surfaceContainerLow = p.card,
    surfaceContainer = p.card,
    surfaceContainerHigh = p.muted,
    surfaceContainerHighest = p.input,
    primaryFixed = p.primary,
    primaryFixedDim = p.primary,
    onPrimaryFixed = p.primaryForeground,
    onPrimaryFixedVariant = p.foreground,
    secondaryFixed = p.primary,
    secondaryFixedDim = p.primary,
    onSecondaryFixed = p.primaryForeground,
    onSecondaryFixedVariant = p.foreground,
    tertiaryFixed = p.primary,
    tertiaryFixedDim = p.primary,
    onTertiaryFixed = p.primaryForeground,
    onTertiaryFixedVariant = p.foreground,
)

/** `#RRGGBB` из constants.ts → непрозрачный sRGB. */
private fun rgb(hex: Int): Color = Color(
    red = ((hex shr 16) and 0xFF) / 255f,
    green = ((hex shr 8) and 0xFF) / 255f,
    blue = (hex and 0xFF) / 255f,
)
