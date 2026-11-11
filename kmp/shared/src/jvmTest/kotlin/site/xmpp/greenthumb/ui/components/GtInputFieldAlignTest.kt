package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * The journal composer shows its hint in the middle of the field: [GtInputField] with
 * `placeholderTextAlign = Center` centres the placeholder, and the default `Start` keeps it on the left (every
 * other form field). The typed text is left-aligned in both cases.
 */
@OptIn(ExperimentalTestApi::class)
class GtInputFieldAlignTest {

    private fun render(textAlign: TextAlign): Pair<PixelMap, Color> {
        lateinit var pixels: PixelMap
        var container: Color = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                GreenThumbTheme(darkTheme = true) {
                    container = MaterialTheme.colorScheme.surfaceContainerHighest
                    Box(Modifier.width(320.dp)) {
                        GtInputField(
                            value = "",
                            onValueChange = {},
                            placeholder = "Add a note",
                            placeholderTextAlign = textAlign,
                            modifier = Modifier.fillMaxWidth().testTag("field"),
                        )
                    }
                }
            }
            pixels = onNodeWithTag("field").captureToImage().toPixelMap()
        }
        return pixels to container
    }

    /** Centre of the drawn hint as a fraction of the field width, measured in the band the single-line hint occupies. */
    private fun PixelMap.hintCentreX(container: Color): Float {
        val top = (height * 0.30f).toInt()
        val bottom = (height * 0.70f).toInt()
        // Skip the border and the rounded corners: the hint never reaches them in a 320 dp field.
        val left = (width * 0.06f).toInt()
        val right = (width * 0.94f).toInt()
        var first = -1
        var last = -1
        for (x in left until right) {
            var painted = false
            for (y in top until bottom) {
                val px = this[x, y]
                val distance = abs(px.red - container.red) + abs(px.green - container.green) + abs(px.blue - container.blue)
                if (distance > 0.10f) {
                    painted = true
                    break
                }
            }
            if (painted) {
                if (first < 0) first = x
                last = x
            }
        }
        assertTrue(first >= 0, "the hint is drawn")
        return ((first + last) / 2f) / width
    }

    @Test
    fun centredHintSitsInTheMiddleOfTheField() {
        val (pixels, container) = render(TextAlign.Center)
        val centre = pixels.hintCentreX(container)
        assertTrue(abs(centre - 0.5f) < 0.08f, "the hint is centred, centre x = $centre")
    }

    @Test
    fun defaultHintStaysOnTheLeft() {
        val (pixels, container) = render(TextAlign.Start)
        val centre = pixels.hintCentreX(container)
        assertTrue(centre < 0.35f, "the hint starts at the left edge, centre x = $centre")
    }
}
