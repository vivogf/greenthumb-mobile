package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-LOOK-011 (jvm part): rows with and without an icon start their label at the same x, every row
 * is at least 56 dp high with a 24 dp icon slot, and «Cancel» is drawn in onSurfaceVariant, not error.
 */
@OptIn(ExperimentalTestApi::class)
class GtSheetRowTest {

    private var scheme: androidx.compose.material3.ColorScheme? = null

    private fun ComposeUiTest.sheet() {
        setContent {
            GreenThumbTheme(darkTheme = true) {
                scheme = MaterialTheme.colorScheme
                Column {
                    GtSheetTitle("Plant photo")
                    GtSheetRow(label = "Take a new photo", icon = { GtCameraMark(it) }, onClick = {})
                    GtSheetRow(
                        label = "Check for problems",
                        icon = { GtDiagnosisMark(it) },
                        supporting = "using this photo",
                        onClick = {},
                    )
                    GtSheetRow(label = "Cancel", icon = null, role = GtSheetRowRole.Cancel, onClick = {})
                }
            }
        }
    }

    private fun ComposeUiTest.row(label: String): SemanticsNodeInteraction =
        onNode(hasTestTag(GtSheetTags.ROW) and hasText(label, substring = true))

    private fun ComposeUiTest.labelOf(label: String): SemanticsNodeInteraction =
        onNode(hasTestTag(GtSheetTags.LABEL) and hasText(label), useUnmergedTree = true)

    @Test
    fun labels_share_one_left_edge_with_and_without_icon() = runDesktopComposeUiTest(width = 360, height = 640) {
        sheet()
        val xs = listOf("Take a new photo", "Check for problems", "Cancel").map {
            labelOf(it).getUnclippedBoundsInRoot().left
        }
        xs.forEach { assertEquals(xs.first().value, it.value, 0.5f, "label x $xs") }
    }

    @Test
    fun rows_are_at_least_56dp_with_a_24dp_icon_slot() = runDesktopComposeUiTest(width = 360, height = 640) {
        sheet()
        listOf("Take a new photo", "Check for problems", "Cancel").forEach {
            val h = row(it).getUnclippedBoundsInRoot().height
            assertTrue(h >= 56.dp, "row «$it» is $h high")
        }
        val slots = onAllNodes(hasTestTag(GtSheetTags.ICON_SLOT), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, slots.size, "every row keeps the slot, Cancel too")
        onAllNodes(hasTestTag(GtSheetTags.ICON_SLOT), useUnmergedTree = true).let { nodes ->
            repeat(3) { i ->
                val b = nodes[i].getUnclippedBoundsInRoot()
                assertEquals(24f, b.width.value, 0.5f)
                assertEquals(24f, b.height.value, 0.5f)
            }
        }
    }

    @Test
    fun cancel_label_is_neutral_not_error() = runDesktopComposeUiTest(width = 360, height = 640) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                GreenThumbTheme(darkTheme = true) {
                    scheme = MaterialTheme.colorScheme
                    GtSheetRow(label = "Cancel", icon = null, role = GtSheetRowRole.Cancel, onClick = {})
                }
            }
        }
        val s = scheme!!
        assertEquals(s.onSurfaceVariant, gtSheetRowLabelColor(GtSheetRowRole.Cancel, s))
        assertNotEquals(s.error, gtSheetRowLabelColor(GtSheetRowRole.Cancel, s))
        val pixels = labelOf("Cancel").captureToImage().toPixelMap()
        var neutral = 0
        var red = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val p = pixels[x, y]
            if (near(p, s.onSurfaceVariant)) neutral++
            if (near(p, s.error)) red++
        }
        assertTrue(neutral > 0, "Cancel text pixels near onSurfaceVariant")
        assertEquals(0, red, "no Cancel pixel near the error colour")
    }

    private fun near(a: Color, b: Color): Boolean =
        abs(a.red - b.red) * 255 <= 12 && abs(a.green - b.green) * 255 <= 12 && abs(a.blue - b.blue) * 255 <= 12
}
