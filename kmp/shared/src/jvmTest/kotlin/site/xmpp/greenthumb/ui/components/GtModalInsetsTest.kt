package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** VAL-APP-015: a tall modal keeps its header and footer actions inside the system-bar safe area. */
@OptIn(ExperimentalTestApi::class)
class GtModalInsetsTest {

    private val statusBar = 48.dp
    private val navigationBar = 32.dp

    private fun ComposeUiTest.bounds(tag: String): DpRect =
        onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.rootHeight(): Dp {
        val px = onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height }
        return with(density) { px.toDp() }
    }

    @Test
    fun tallModalKeepsHeaderAndFooterActionsInsideSafeArea() = runDesktopComposeUiTest(width = 400, height = 700) {
        setContent {
            GreenThumbTheme(darkTheme = false) {
                GtModal(
                    onDismissRequest = {},
                    safeInsets = WindowInsets(top = statusBar, bottom = navigationBar),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Cancel", Modifier.testTag("modal_header_cancel"))
                        Text("Save", Modifier.testTag("modal_header_save"))
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(3000.dp))
                    }
                    Text("Done", Modifier.testTag("modal_footer_done"))
                }
            }
        }
        waitForIdle()
        val windowBottom = rootHeight()
        val safeBottom = windowBottom - navigationBar
        for (tag in listOf("modal_header_cancel", "modal_header_save")) {
            val b = bounds(tag)
            assertTrue(b.top >= statusBar, "$tag top ${b.top} is above the status-bar bottom $statusBar")
        }
        val footer = bounds("modal_footer_done")
        assertTrue(
            footer.bottom <= safeBottom,
            "footer bottom ${footer.bottom} is below the navigation-bar top $safeBottom (window $windowBottom)",
        )
    }
}
