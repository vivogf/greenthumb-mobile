package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** Капля: острие сверху, круглое дно снизу (раньше рисовалась «v над чашей»). */
@OptIn(ExperimentalTestApi::class)
class GtWaterDropMarkTest {

    @Test
    fun filled_drop_is_narrow_at_the_top_and_round_at_the_bottom() = runDesktopComposeUiTest {
        setContent {
            GtWaterDropMark(
                tint = Color.Black,
                filled = true,
                modifier = Modifier.size(100.dp).testTag("drop"),
            )
        }
        val pixels = onNodeWithTag("drop").captureToImage().toPixelMap()
        val w = pixels.width
        val h = pixels.height

        fun painted(x: Float, y: Float) = pixels[(w * x).toInt(), (h * y).toInt()].alpha > 0.5f

        assertTrue(painted(0.5f, 0.5f), "body")
        assertTrue(painted(0.5f, 0.88f), "round bottom")
        assertTrue(painted(0.5f, 0.14f), "narrow tip above the body")
        assertTrue(!painted(0.3f, 0.14f), "tip is narrow: nothing at the side of the top")
        assertTrue(!painted(0.1f, 0.9f), "bottom corner is empty")
        assertTrue(!painted(0.9f, 0.9f), "bottom corner is empty")
    }

    @Test
    fun outline_drop_leaves_the_inside_empty() = runDesktopComposeUiTest {
        setContent {
            GtWaterDropMark(
                tint = Color.Black,
                modifier = Modifier.size(100.dp).testTag("drop"),
            )
        }
        val pixels = onNodeWithTag("drop").captureToImage().toPixelMap()
        val w = pixels.width
        val h = pixels.height
        assertTrue(pixels[w / 2, (h * 0.65f).toInt()].alpha < 0.1f, "inside of the outline is empty")
        val bottomDrawn = ((h * 0.9f).toInt()..(h * 0.98f).toInt()).any { pixels[w / 2, it].alpha > 0.3f }
        assertTrue(bottomDrawn, "bottom of the outline is drawn")
    }
}
