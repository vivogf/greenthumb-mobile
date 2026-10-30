package site.xmpp.greenthumb.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

/** VAL-FIX11-008: the shared paragraph style justifies text and never hyphenates it. */
class ParagraphStyleTest {

    @Test
    fun paragraphStyleIsJustifiedWithoutHyphenation() {
        for (base in listOf(GtTypeScale.bodySmall, GtTypeScale.bodyMedium, GtType.loginHint)) {
            val style = GtParagraph.of(base)
            assertEquals(TextAlign.Justify, style.textAlign)
            // None (not Auto, not Unspecified): hyphenation stays off on every device.
            assertEquals(Hyphens.None, style.hyphens)
            assertEquals(0.sp, style.letterSpacing, "justified lines must reach the right edge on Android")
        }
    }

    @Test
    fun paragraphStyleKeepsTheBaseTypography() {
        val base = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Color.Red)
        val style = GtParagraph.of(base)
        assertEquals(base.fontSize, style.fontSize)
        assertEquals(base.lineHeight, style.lineHeight)
        assertEquals(base.color, style.color)
        assertEquals(base.fontWeight, style.fontWeight)
    }
}
