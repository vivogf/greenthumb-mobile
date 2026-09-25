package site.xmpp.greenthumb.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Роли, которых нет в Material3 ColorScheme. Значения дословно из
 * `lib/constants.ts` (amber / amberBg / amberBorder / cardBorder).
 *
 * Читается через [LocalGreenThumbExtendedColors] внутри [GreenThumbTheme].
 * Дефолт локала — светлая схема, чтобы чтение вне темы не падало; экран
 * обязан быть обёрнут в [GreenThumbTheme], иначе тёмная схема не приедет.
 *
 * Альфа передаётся как в CSS (`0.08` / `0.12` / `0.25` / `0.20`). Compose
 * пакует sRGB в 8 бит, поэтому хранимый альфа — ближайший байт, не сам float.
 */
data class GreenThumbExtendedColors(
    val amber: Color,
    val amberBg: Color,
    val amberBorder: Color,
    val cardBorder: Color,
) {
    companion object {
        val light = GreenThumbExtendedColors(
            amber = amber,
            amberBg = rgba(217, 119, 6, 0.08f),
            amberBorder = rgba(217, 119, 6, 0.20f),
            cardBorder = Color(red = 0xF0 / 255f, green = 0xF0 / 255f, blue = 0xF0 / 255f),
        )
        val dark = GreenThumbExtendedColors(
            amber = amber,
            amberBg = rgba(217, 119, 6, 0.12f),
            amberBorder = rgba(217, 119, 6, 0.25f),
            cardBorder = Color(red = 0x27 / 255f, green = 0x24 / 255f, blue = 0x20 / 255f),
        )
    }
}

/** `#d97706` — один и тот же в light и dark (`lib/constants.ts`). */
private val amber = Color(red = 217 / 255f, green = 119 / 255f, blue = 6 / 255f)

private fun rgba(red: Int, green: Int, blue: Int, alpha: Float): Color = Color(
    red = red / 255f,
    green = green / 255f,
    blue = blue / 255f,
    alpha = alpha,
)

val LocalGreenThumbExtendedColors = staticCompositionLocalOf { GreenThumbExtendedColors.light }
