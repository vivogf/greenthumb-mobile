package site.xmpp.greenthumb.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette roles that have no Material3 slot (D§3.1): dark «Графит», light «Лес».
 *
 * Read through [LocalGreenThumbExtendedColors] inside [GreenThumbTheme]. The local defaults to the
 * light set, so a read outside the theme does not crash.
 *
 * Alphas are given as in CSS; Compose packs them into 8 bits, so the stored alpha is the nearest byte.
 */
data class GreenThumbExtendedColors(
    /** Due today, medium likelihood. */
    val amber: Color,
    val amberBg: Color,
    val amberBorder: Color,
    /** Same value as `colorScheme.outlineVariant`, kept for named access. */
    val cardBorder: Color,
    /** Page: the window, the navigation root and the system bars behind every screen. */
    val page: Color,
    /** Links and accent text (`--acct`); equals `colorScheme.tertiary`. */
    val accentText: Color,
    /** Smart camera glow border (`--glow`). */
    val glow: Color,
    /** High likelihood and overdue; equals `colorScheme.error`. */
    val causeHigh: Color,
    /** Low likelihood. */
    val causeLow: Color,
    /** Fill of a selected chip. */
    val chipSelected: Color,
) {
    companion object {
        val light = GreenThumbExtendedColors(
            amber = lightAmber,
            amberBg = lightAmber.copy(alpha = 0.08f),
            amberBorder = lightAmber.copy(alpha = 0.20f),
            cardBorder = rgb(0xCBD6C7),
            page = rgb(0xF3F6F1),
            accentText = rgb(0x1F6B36),
            glow = rgb(0x3DA35B),
            causeHigh = rgb(0xB4472A),
            causeLow = rgb(0x6F685C),
            chipSelected = rgb(0x277A40).copy(alpha = 0.12f),
        )
        val dark = GreenThumbExtendedColors(
            amber = darkAmber,
            amberBg = darkAmber.copy(alpha = 0.12f),
            amberBorder = darkAmber.copy(alpha = 0.25f),
            cardBorder = rgb(0x2C2E32),
            page = rgb(0x0D0E0F),
            accentText = rgb(0x8CCB9F),
            // Graphite has no separate glow: the camera border uses the accent green.
            glow = rgb(0x5FA877),
            causeHigh = rgb(0xE58A74),
            causeLow = rgb(0xA3A6AB),
            chipSelected = rgb(0x5FA877).copy(alpha = 0.16f),
        )
    }
}

private val darkAmber = rgb(0xD9A15F)

/** Darker than the dark amber: #D9A15F on white is below 4.5:1. */
private val lightAmber = rgb(0xA86412)

val LocalGreenThumbExtendedColors = staticCompositionLocalOf { GreenThumbExtendedColors.light }
