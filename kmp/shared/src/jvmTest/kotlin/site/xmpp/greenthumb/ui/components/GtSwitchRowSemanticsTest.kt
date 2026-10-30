package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A switch row ([gtSwitchRow] + [GtSwitchIndicator]) is ONE accessible node: role Switch, the toggle
 * state, the click action and the label text sit on the same node, and no second node carries the label.
 */
@OptIn(ExperimentalTestApi::class)
class GtSwitchRowSemanticsTest {

    private val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test
    fun label_state_and_action_are_one_node() = runDesktopComposeUiTest {
        var checked by mutableStateOf(false)
        var callbacks = 0
        setContent {
            GreenThumbTheme(darkTheme = false) {
                Row(modifier = Modifier.gtSwitchRow(checked = checked) { callbacks++; checked = it }) {
                    Column {
                        Text("Pruning")
                        Text("Off")
                    }
                    GtSwitchIndicator(checked = checked)
                }
            }
        }

        assertEquals(1, onAllNodes(isSwitch).fetchSemanticsNodes().size, "one switch node")
        assertEquals(1, onAllNodes(hasText("Pruning")).fetchSemanticsNodes().size, "the label is not a second node")
        assertEquals(1, onAllNodes(isSwitch, useUnmergedTree = true).fetchSemanticsNodes().size, "the indicator has no own switch node")
        val node = onNode(isSwitch and hasText("Pruning") and hasClickAction())
        assertEquals(ToggleableState.Off, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))

        node.performClick()
        waitForIdle()
        assertEquals(1, callbacks)
        assertTrue(checked)
        assertEquals(ToggleableState.On, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(true, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test
    fun disabled_row_does_not_toggle() = runDesktopComposeUiTest {
        var callbacks = 0
        setContent {
            GreenThumbTheme(darkTheme = false) {
                Row(modifier = Modifier.gtSwitchRow(checked = false, enabled = false) { callbacks++ }) {
                    Text("Watering Reminders")
                    GtSwitchIndicator(checked = false)
                }
            }
        }
        onNode(isSwitch and hasText("Watering Reminders")).performClick()
        waitForIdle()
        assertEquals(0, callbacks)
    }
}
