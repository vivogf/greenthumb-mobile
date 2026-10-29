package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every public mark renders in a 24 dp box and paints something (smoke, D§3.4). */
@OptIn(ExperimentalTestApi::class)
class GtMarksRenderTest {

    private val marks: List<Pair<String, @Composable (Color, Modifier) -> Unit>> = listOf(
        "Calendar" to { t, m -> GtCalendarMark(t, m) },
        "ChevronDown" to { t, m -> GtChevronMark(t, m, GtChevronDirection.Down) },
        "ChevronLeft" to { t, m -> GtChevronMark(t, m, GtChevronDirection.Left) },
        "ChevronUp" to { t, m -> GtChevronMark(t, m, GtChevronDirection.Up) },
        "ChevronRight" to { t, m -> GtChevronMark(t, m, GtChevronDirection.Right) },
        "Camera" to { t, m -> GtCameraMark(t, m) },
        "Leaf" to { t, m -> GtLeafMark(t, m) },
        "Person" to { t, m -> GtPersonMark(t, m) },
        "WaterDrop" to { t, m -> GtWaterDropMark(t, m) },
        "WaterDropFilled" to { t, m -> GtWaterDropMark(t, m, filled = true) },
        "Bell" to { t, m -> GtBellMark(t, m) },
        "PersonAdd" to { t, m -> GtPersonAddMark(t, m) },
        "Key" to { t, m -> GtKeyMark(t, m) },
        "Lock" to { t, m -> GtLockMark(t, m) },
        "Warning" to { t, m -> GtWarningMark(t, m) },
        "Check" to { t, m -> GtCheckMark(t, m) },
        "Tick" to { t, m -> GtTickMark(t, m) },
        "Copy" to { t, m -> GtCopyMark(t, m) },
        "BellOutline" to { t, m -> GtBellOutlineMark(t, m) },
        "Clock" to { t, m -> GtClockMark(t, m) },
        "Send" to { t, m -> GtSendMark(t, m) },
        "Refresh" to { t, m -> GtRefreshMark(t, m) },
        "Globe" to { t, m -> GtGlobeMark(t, m) },
        "Palette" to { t, m -> GtPaletteMark(t, m) },
        "SignOut" to { t, m -> GtSignOutMark(t, m) },
        "Contrast" to { t, m -> GtContrastMark(t, m) },
        "Sun" to { t, m -> GtSunMark(t, m) },
        "Moon" to { t, m -> GtMoonMark(t, m) },
        "Pencil" to { t, m -> GtPencilMark(t, m) },
        "Location" to { t, m -> GtLocationMark(t, m) },
        "Pot" to { t, m -> GtPotMark(t, m) },
        "Scissors" to { t, m -> GtScissorsMark(t, m) },
        "Gear" to { t, m -> GtGearMark(t, m) },
        "Trash" to { t, m -> GtTrashMark(t, m) },
        "Gallery" to { t, m -> GtGalleryMark(t, m) },
        "Search" to { t, m -> GtSearchMark(t, m) },
        "Close" to { t, m -> GtCloseMark(t, m) },
        "Plus" to { t, m -> GtPlusMark(t, m) },
        "Funnel" to { t, m -> GtFunnelMark(t, m) },
        "CloudOff" to { t, m -> GtCloudOffMark(t, m) },
        "ListView" to { t, m -> GtListViewMark(t, m) },
        "CardView" to { t, m -> GtCardViewMark(t, m) },
        "GridView" to { t, m -> GtGridViewMark(t, m) },
        "Fertilize" to { t, m -> GtFertilizeMark(t, m) },
        "SmartCamera" to { t, m -> GtSmartCameraMark(t, m) },
        "Diagnosis" to { t, m -> GtDiagnosisMark(t, m) },
        "Species" to { t, m -> GtSpeciesMark(t, m) },
        "Journal" to { t, m -> GtJournalMark(t, m) },
    )

    @Test
    fun every_public_mark_renders_and_paints_in_a_24dp_box() = runDesktopComposeUiTest {
        setContent {
            // A grid keeps every box inside the test window; off-screen nodes cannot be captured.
            Column {
                marks.chunked(8).forEach { row ->
                    Row {
                        row.forEach { (name, mark) -> mark(Color.Black, Modifier.size(24.dp).testTag(name)) }
                    }
                }
            }
        }
        val blank = marks.map { it.first }.filter { name ->
            val pixels = onNodeWithTag(name).captureToImage().toPixelMap()
            (0 until pixels.width).none { x -> (0 until pixels.height).any { y -> pixels[x, y].alpha > 0.3f } }
        }
        assertTrue(blank.isEmpty(), "marks that paint nothing: $blank")
    }

    @Test
    fun resource_marks_take_the_tint_colour() = runDesktopComposeUiTest {
        setContent { GtLeafMark(tint = Color.Red, modifier = Modifier.size(48.dp).testTag("leaf")) }
        val pixels = onNodeWithTag("leaf").captureToImage().toPixelMap()
        var red = 0
        var other = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.alpha < 0.9f) continue
            if (c.red > 0.9f && c.green < 0.1f && c.blue < 0.1f) red++ else other++
        }
        assertTrue(red > 0, "the stroke is painted in the tint")
        assertTrue(other == 0, "opaque pixels in a non-tint colour: $other")
    }
}
