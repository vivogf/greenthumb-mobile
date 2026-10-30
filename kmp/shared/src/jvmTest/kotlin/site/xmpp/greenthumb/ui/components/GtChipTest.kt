package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VAL-FIX11-003: a [GtChip] label is always one line; a label wider than its parent is measured at the
 * chip's width with [TextOverflow.Ellipsis] (skiko does not report isLineEllipsized, so the test reads
 * the layout input instead).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)
class GtChipTest {

    private fun ComposeUiTest.showScaled(fontScale: Float, content: @Composable () -> Unit) {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                GreenThumbTheme(darkTheme = true) { content() }
            }
        }
    }

    private fun ComposeUiTest.layoutOf(label: String): TextLayoutResult {
        val node = onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    @Test
    fun label_stays_one_line_in_a_narrow_parent_at_font_scale_1_3() = runDesktopComposeUiTest {
        showScaled(1.3f) {
            Box(modifier = Modifier.width(90.dp)) {
                GtChip(label = "В порядке", selected = false, onClick = {})
            }
        }
        assertEquals(1, layoutOf("В порядке").lineCount, "a chip label never wraps")
    }

    @Test
    fun long_place_label_in_a_320dp_flow_row_is_one_line_and_ends_with_an_ellipsis() = runDesktopComposeUiTest {
        val long = "Подоконник в дальней комнате у большого окна на север"
        showScaled(1.3f) {
            FlowRow(modifier = Modifier.width(320.dp)) {
                GtChip(label = "Кухня", selected = false, onClick = {})
                GtChip(label = long, selected = true, onClick = {})
            }
        }
        assertEquals(1, layoutOf("Кухня").lineCount)
        assertTrue(!layoutOf("Кухня").isLineEllipsized(0), "a short preset keeps its full label")
        val longLayout = layoutOf(long)
        assertEquals(1, longLayout.lineCount, "a long label stays one line")
        assertEquals(TextOverflow.Ellipsis, longLayout.layoutInput.overflow, "a cut label ends with «…»")
        // The label is measured inside the chip, not at its natural width, so the «…» is drawn
        // at the chip's edge and the chip itself stays inside the 320 dp row.
        val rowWidthPx = with(density) { 320.dp.toPx() }
        assertTrue(longLayout.layoutInput.constraints.hasBoundedWidth, "label measured with the chip's width")
        assertTrue(longLayout.size.width < rowWidthPx, "label ${longLayout.size.width}px fits the row ${rowWidthPx}px")
        val chip = onNode(hasClickAction() and hasText(long)).fetchSemanticsNode().boundsInRoot
        assertTrue(chip.width <= rowWidthPx + 0.5f, "chip ${chip.width}px stays inside the row")
    }
}
