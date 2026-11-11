package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * The journal's trash sits on the row with no square around it: [GtIconButton] with `plain = true` paints no
 * background and no border, while the boxed variant still paints its container. Both keep the 48 dp touch target
 * and the click.
 */
@OptIn(ExperimentalTestApi::class)
class GtIconButtonPlainTest {

    private fun render(plain: Boolean, clicks: () -> Unit): PixelMap {
        lateinit var pixels: PixelMap
        runDesktopComposeUiTest {
            setContent {
                GreenThumbTheme(darkTheme = true) {
                    GtIconButton(
                        onClick = clicks,
                        contentDescription = "Delete entry",
                        plain = plain,
                        modifier = Modifier.testTag("button"),
                    ) {
                        Box(Modifier.size(4.dp))
                    }
                }
            }
            pixels = onNodeWithTag("button").captureToImage().toPixelMap()
        }
        return pixels
    }

    /** Alpha at a point given as fractions of the button box: 0 means nothing was painted there. */
    private fun PixelMap.alphaAt(x: Float, y: Float): Float {
        val px = (width * x).toInt().coerceIn(0, width - 1)
        val py = (height * y).toInt().coerceIn(0, height - 1)
        return this[px, py].alpha
    }

    @Test
    fun plainButtonPaintsNoContainer() {
        val pixels = render(plain = true) {}
        // Just inside the top and left edges: where the container's background and its hairline border used to be.
        assertTrue(pixels.alphaAt(0.5f, 0.02f) < 0.01f, "no background above the icon")
        assertTrue(pixels.alphaAt(0.02f, 0.5f) < 0.01f, "no border left of the icon")
        assertTrue(pixels.alphaAt(0.98f, 0.5f) < 0.01f, "no border right of the icon")
    }

    @Test
    fun boxedButtonStillPaintsItsContainer() {
        val pixels = render(plain = false) {}
        assertTrue(pixels.alphaAt(0.5f, 0.02f) > 0.9f, "the boxed button keeps its background")
    }

    @Test
    fun plainButtonKeepsTheTouchTargetAndTheClick() {
        var clicks = 0
        runDesktopComposeUiTest {
            setContent {
                GreenThumbTheme(darkTheme = true) {
                    Row {
                        GtIconButton(
                            onClick = { clicks++ },
                            contentDescription = "Delete entry",
                            plain = true,
                            modifier = Modifier.testTag("button"),
                        ) {
                            Box(Modifier.size(4.dp))
                        }
                    }
                }
            }
            onNodeWithTag("button").performClick()
            assertEquals(1, clicks)
            val width = onNodeWithTag("button").fetchSemanticsNode().size.width
            val height = onNodeWithTag("button").fetchSemanticsNode().size.height
            assertTrue(width >= 44 && height >= 44, "48 dp touch target: $width x $height px at this density")
        }
    }
}
