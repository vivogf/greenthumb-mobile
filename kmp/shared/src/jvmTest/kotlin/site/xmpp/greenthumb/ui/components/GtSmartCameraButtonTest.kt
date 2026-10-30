package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.LocalReduceMotion
import site.xmpp.greenthumb.ui.theme.Sizes

/** VAL-CAM-001/002: the icon-only smart camera button and its glow. */
@OptIn(ExperimentalTestApi::class)
class GtSmartCameraButtonTest {

    @Test
    fun reduceMotionOrInactiveGivesTheStaticGlow() {
        assertEquals(GlowSpec.Static, glowSpec(reduceMotion = true, active = true))
        assertEquals(GlowSpec.Static, glowSpec(reduceMotion = false, active = false))
        assertEquals(GlowSpec.Static, glowSpec(reduceMotion = true, active = false))
        val animated = assertIs<GlowSpec.Animated>(glowSpec(reduceMotion = false, active = true))
        assertTrue(animated.rotationMs > 0 && animated.pulseMs > 0 && animated.minAlpha < 1f)
    }

    private val noText = SemanticsMatcher("has no text") { node ->
        !node.config.contains(SemanticsProperties.Text) || node.config[SemanticsProperties.Text].all { it.text.isEmpty() }
    }

    @Test
    fun oneClickableIconOnlyNodeWithItsNameAndTouchSize() = runDesktopComposeUiTest {
        var clicks = 0
        setContent {
            GreenThumbTheme(darkTheme = true) {
                GtSmartCameraButton(onClick = { clicks++ }, contentDescription = "Smart camera")
            }
        }
        val node = onNode(hasContentDescription("Smart camera") and hasClickAction(), useUnmergedTree = false)
        node.assertExists()
        node.assertWidthIsAtLeast(Sizes.touch)
        node.assertHeightIsAtLeast(Sizes.touch)
        onNode(hasContentDescription("Smart camera") and noText).assertExists()
        onNode(hasTestTag("smartCamera_glow_animated"), useUnmergedTree = true).assertExists()
        node.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun reduceMotionRendersTheStaticRing() = runDesktopComposeUiTest {
        setContent {
            GreenThumbTheme(darkTheme = true) {
                CompositionLocalProvider(LocalReduceMotion provides true) {
                    GtSmartCameraButton(onClick = {}, contentDescription = "Умная камера")
                }
            }
        }
        onNode(hasTestTag("smartCamera_glow_static"), useUnmergedTree = true).assertExists()
        onNode(hasTestTag("smartCamera_glow_animated"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun inactiveButtonDoesNotAnimate() = runDesktopComposeUiTest {
        setContent {
            GreenThumbTheme(darkTheme = true) {
                GtSmartCameraButton(onClick = {}, contentDescription = "Smart camera", active = false)
            }
        }
        onNode(hasTestTag("smartCamera_glow_static"), useUnmergedTree = true).assertExists()
    }
}
