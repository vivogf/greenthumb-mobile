package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** The chevron is drawn tip-down; the direction parameter rotates it. */
@OptIn(ExperimentalTestApi::class)
class GtChevronMarkTest {

    private fun render(direction: GtChevronDirection): PixelMap {
        lateinit var pixels: PixelMap
        runDesktopComposeUiTest {
            setContent {
                GtChevronMark(
                    tint = Color.Black,
                    direction = direction,
                    modifier = Modifier.size(100.dp).testTag("chevron"),
                )
            }
            pixels = onNodeWithTag("chevron").captureToImage().toPixelMap()
        }
        return pixels
    }

    /** Any painted pixel within 3 px of the point (x, y given as fractions of the box). */
    private fun PixelMap.paintedNear(x: Float, y: Float): Boolean {
        val cx = (width * x).toInt()
        val cy = (height * y).toInt()
        for (dy in -3..3) for (dx in -3..3) {
            val px = (cx + dx).coerceIn(0, width - 1)
            val py = (cy + dy).coerceIn(0, height - 1)
            if (this[px, py].alpha > 0.4f) return true
        }
        return false
    }

    @Test
    fun down_tip_is_below_the_centre() {
        val p = render(GtChevronDirection.Down)
        assertTrue(p.paintedNear(0.5f, 0.62f), "tip below")
        assertTrue(!p.paintedNear(0.5f, 0.38f), "nothing above the tip on the axis")
    }

    @Test
    fun left_tip_is_left_of_the_centre() {
        val p = render(GtChevronDirection.Left)
        assertTrue(p.paintedNear(0.38f, 0.5f), "tip on the left")
        assertTrue(!p.paintedNear(0.62f, 0.5f), "nothing right of the tip on the axis")
    }

    @Test
    fun right_tip_is_right_of_the_centre() {
        val p = render(GtChevronDirection.Right)
        assertTrue(p.paintedNear(0.62f, 0.5f), "tip on the right")
        assertTrue(!p.paintedNear(0.38f, 0.5f), "nothing left of the tip on the axis")
    }

    @Test
    fun up_tip_is_above_the_centre() {
        val p = render(GtChevronDirection.Up)
        assertTrue(p.paintedNear(0.5f, 0.38f), "tip above")
        assertTrue(!p.paintedNear(0.5f, 0.62f), "nothing below the tip on the axis")
    }
}
