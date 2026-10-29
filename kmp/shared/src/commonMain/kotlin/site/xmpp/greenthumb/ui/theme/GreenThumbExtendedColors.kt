package site.xmpp.greenthumb.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * «Лес» roles that have no Material3 slot (D§3.1).
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
    /** «Лес» page: the window, the navigation root and the system bars behind every screen. */
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
            cardBorder = rgb(0x2A3D2E),
            page = rgb(0x0A100B),
            accentText = rgb(0x86D69C),
            glow = rgb(0x7FE3A0),
            causeHigh = rgb(0xEE8E72),
            causeLow = rgb(0xA39B8C),
            chipSelected = rgb(0x4A9A5A).copy(alpha = 0.2f),
        )
    }
}

private val darkAmber = rgb(0xE0A25A)

/** Darker than the dark amber: #E0A25A on white is below 4.5:1. */
private val lightAmber = rgb(0xA86412)

val LocalGreenThumbExtendedColors = staticCompositionLocalOf { GreenThumbExtendedColors.light }
