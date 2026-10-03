package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.Motion

@OptIn(ExperimentalTestApi::class)
class GtWaterButtonTest {

    @Test
    fun tap_shows_success_label_at_once_calls_onWater_once_and_then_resets() = runDesktopComposeUiTest {
        var watered = 0
        setContent {
            GreenThumbTheme(darkTheme = true) {
                GtWaterButton(
                    onWater = { watered++ },
                    isWatering = false,
                    label = "Water Plant",
                    successLabel = "Watered!",
                    contentDescription = "Water button",
                )
            }
        }
        onNodeWithText("Water Plant").assertExists()

        mainClock.autoAdvance = false
        onNodeWithContentDescription("Water button").performClick()
        mainClock.advanceTimeByFrame()
        // Optimistic: the success state shows before the mutation callback runs.
        onNodeWithText("Watered!").assertExists()
        assertEquals(0, watered, "mutation waits for the press lead")

        mainClock.advanceTimeBy(Motion.WaterPressLeadMs + 100L)
        assertEquals(1, watered)
        // A second tap during the celebration is ignored.
        onNodeWithContentDescription("Water button").performClick()
        mainClock.advanceTimeBy(100L)
        assertEquals(1, watered)
        onNodeWithText("Watered!").assertExists()

        mainClock.advanceTimeBy(Motion.WaterParticlesHoldMs + 500L)
        onNodeWithText("Water Plant").assertExists()
        assertEquals(1, watered)
    }

    @Test
    fun in_flight_request_shows_success_label_not_a_spinner() = runDesktopComposeUiTest {
        setContent {
            GreenThumbTheme(darkTheme = true) {
                GtWaterButton(
                    onWater = {},
                    isWatering = true,
                    label = "Water Plant",
                    successLabel = "Watered!",
                )
            }
        }
        onNodeWithText("Watered!").assertExists()
    }
}
