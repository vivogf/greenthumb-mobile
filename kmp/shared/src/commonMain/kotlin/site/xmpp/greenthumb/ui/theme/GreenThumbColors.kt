package site.xmpp.greenthumb.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * App palette: dark «Графит» (M14, architecture §13) and light «Лес» (M10, D§3.1).
 *
 * Dark = the Graphite colours of `kmp/design/dark-theme-palettes.html` (commit 3a75c02); it replaced
 * the M10 dark «Лес» mockup tokens. Light is derived from the «Лес» greens (architecture §9.3) and every
 * text role keeps a contrast of at least 4.5:1 (ThemeTokensTest checks it).
 *
 * Token → Material3 role:
 * - `--bg` → background, surfaceContainerLowest
 * - `--card` → surface, surfaceContainer, surfaceContainerLow
 * - `--card2` → surfaceVariant, surfaceContainerHigh/Highest (fields, progress track, stepper buttons)
 * - `--cardb` → outline, outlineVariant (card borders, dividers)
 * - `--fg` → onBackground, onSurface
 * - `--mut` → onSurfaceVariant (hints, labels, Cancel)
 * - `--btn` → primary (filled buttons). Never a text colour on dark: it reads at about 3:1 on a card
 * - `--acc` → secondary (accent icons, progress fill, selected chip border). No white text on it
 * - `--acct` → tertiary (links and accent text)
 * - cause hi → error
 * - `--page`, `--amb`, `--glow`, cause lo, chip on → [GreenThumbExtendedColors]
 *
 * Fixed roles reuse the same tokens, so Material3 never brings its purple defaults.
 */
object GreenThumbColors {
    /** Light-theme sheet and dialog scrim: black at 50 %. */
    val modalScrim: Color = Color(red = 0f, green = 0f, blue = 0f, alpha = 0.5f)

    /** Alpha of a row that is shown but cannot be used now (Material disabled content level). */
    const val disabledContentAlpha: Float = 0.38f

    /**
     * `#22c55e` — успех показа ключа (RN `app/(auth)/login.tsx`: иконка
     * checkmark-circle и её фон `#22c55e22`). Не токен constants.ts —
     * литерал RN-экрана; портирован дословно как роль темы, чтобы экран не
     * собирал `Color(` в обход K5 (тот же приём, что [modalScrim]).
     * В light и dark один и тот же.
     */
    val success: Color = Color(red = 0x22 / 255f, green = 0xC5 / 255f, blue = 0x5E / 255f)

    /**
     * Dark scrim: the page colour at 60 %, so a sheet never lifts the screen towards grey. The
     * light theme keeps [modalScrim]: a light scrim over a light screen would hide nothing.
     */
    val darkScrim: Color = Color(red = 0x0D / 255f, green = 0x0E / 255f, blue = 0x0F / 255f, alpha = 0.6f)

    val light: ColorScheme = scheme(LightForest, DarkGraphite, isLight = true, scrim = modalScrim)
    val dark: ColorScheme = scheme(DarkGraphite, LightForest, isLight = false, scrim = darkScrim)
}

private data class Palette(
    val background: Color,
    val card: Color,
    val card2: Color,
    val cardBorder: Color,
    val foreground: Color,
    val mutedForeground: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val onAccentText: Color,
    val error: Color,
    val onError: Color,
)

/**
 * Dark «Графит» (M14, architecture §13): `graphite.colors` of `kmp/design/dark-theme-palettes.html`
 * at commit 3a75c02. Neutral large surfaces; green stays on actions, selection and accent text.
 */
private val DarkGraphite = Palette(
    background = rgb(0x121314),
    card = rgb(0x1A1B1D),
    card2 = rgb(0x24262A),
    cardBorder = rgb(0x2C2E32),
    foreground = rgb(0xECEDEE),
    mutedForeground = rgb(0xA3A6AB),
    primary = rgb(0x2F7A47),
    onPrimary = rgb(0xF2F7F3),
    accent = rgb(0x5FA877),
    onAccent = rgb(0x0D0E0F),
    accentText = rgb(0x8CCB9F),
    onAccentText = rgb(0x0D0E0F),
    error = rgb(0xE58A74),
    onError = rgb(0x0D0E0F),
)

/**
 * Derived light «Лес». D§3.1 proposal for bg/card/fg/mut/btn/acc/error; card2, cardBorder and
 * accentText are added here. accentText is darker than acc, because #2E8A4A on white is only 4.3:1.
 */
private val LightForest = Palette(
    background = rgb(0xF3F6F1),
    card = rgb(0xFFFFFF),
    card2 = rgb(0xE7EDE4),
    cardBorder = rgb(0xCBD6C7),
    foreground = rgb(0x142016),
    mutedForeground = rgb(0x4E5E4D),
    primary = rgb(0x277A40),
    onPrimary = rgb(0xF0F9F2),
    accent = rgb(0x2E8A4A),
    onAccent = rgb(0x0A100B),
    accentText = rgb(0x1F6B36),
    onAccentText = rgb(0xFFFFFF),
    error = rgb(0xB4472A),
    onError = rgb(0xFFFFFF),
)

/** Every slot is set, so the Material3 defaults never leak in. */
private fun scheme(p: Palette, inverse: Palette, isLight: Boolean, scrim: Color): ColorScheme = lightColorScheme(
    primary = p.primary,
    onPrimary = p.onPrimary,
    primaryContainer = p.card2,
    onPrimaryContainer = p.foreground,
    inversePrimary = inverse.primary,
    secondary = p.accent,
    onSecondary = p.onAccent,
    secondaryContainer = p.card2,
    onSecondaryContainer = p.foreground,
    tertiary = p.accentText,
    onTertiary = p.onAccentText,
    tertiaryContainer = p.card2,
    onTertiaryContainer = p.foreground,
    background = p.background,
    onBackground = p.foreground,
    surface = p.card,
    onSurface = p.foreground,
    surfaceVariant = p.card2,
    onSurfaceVariant = p.mutedForeground,
    surfaceTint = p.primary,
    inverseSurface = inverse.card,
    inverseOnSurface = inverse.foreground,
    error = p.error,
    onError = p.onError,
    errorContainer = p.error,
    onErrorContainer = p.onError,
    outline = p.cardBorder,
    outlineVariant = p.cardBorder,
    scrim = scrim,
    surfaceBright = if (isLight) p.card else p.card2,
    surfaceDim = if (isLight) p.card2 else p.background,
    surfaceContainerLowest = p.background,
    surfaceContainerLow = p.card,
    surfaceContainer = p.card,
    surfaceContainerHigh = p.card2,
    surfaceContainerHighest = p.card2,
    primaryFixed = p.primary,
    primaryFixedDim = p.primary,
    onPrimaryFixed = p.onPrimary,
    onPrimaryFixedVariant = p.foreground,
    secondaryFixed = p.accent,
    secondaryFixedDim = p.accent,
    onSecondaryFixed = p.onAccent,
    onSecondaryFixedVariant = p.foreground,
    tertiaryFixed = p.accentText,
    tertiaryFixedDim = p.accentText,
    onTertiaryFixed = p.onAccentText,
    onTertiaryFixedVariant = p.foreground,
)

/** `#RRGGBB` → opaque sRGB. */
internal fun rgb(hex: Int): Color = Color(
    red = ((hex shr 16) and 0xFF) / 255f,
    green = ((hex shr 8) and 0xFF) / 255f,
    blue = (hex and 0xFF) / 255f,
)
