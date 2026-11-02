package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbColors
import site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.screenTransitions

/** VAL-LOOK-001: the navigation root paints the page colour, also while a route draws nothing. */
@OptIn(ExperimentalTestApi::class)
class NavHostBackgroundTest {

    @Test
    fun navHostRootPaintsPageColour() = runDesktopComposeUiTest {
        setContent {
            GreenThumbTheme(darkTheme = true) {
                Box(Modifier.size(64.dp).testTag("root")) {
                    GtPageRoot { }
                }
            }
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        assertEquals(GreenThumbExtendedColors.dark.page.toArgb(), pixels[32, 32].toArgb())
        assertEquals(0xFF0D0E0F.toInt(), pixels[32, 32].toArgb())
    }

    @Test
    fun lightThemeRootPaintsLightPage() = runDesktopComposeUiTest {
        setContent {
            GreenThumbTheme(darkTheme = false) {
                Box(Modifier.size(64.dp).testTag("root")) {
                    GtPageRoot { }
                }
            }
        }
        val pixels = onNodeWithTag("root").captureToImage().toPixelMap()
        assertEquals(GreenThumbExtendedColors.light.page.toArgb(), pixels[32, 32].toArgb())
    }

    @Test
    fun midTransitionFrameIsNeverBrighterThanTheScreens() = runDesktopComposeUiTest {
        lateinit var nav: NavHostController
        setContent {
            GreenThumbTheme(darkTheme = true) {
                val transitions = screenTransitions(reduceMotion = false)
                Box(Modifier.size(64.dp).testTag("root")) {
                    GtPageRoot {
                        nav = rememberNavController()
                        NavHost(
                            navController = nav,
                            startDestination = "a",
                            enterTransition = { transitions.enter },
                            exitTransition = { transitions.exit },
                            popEnterTransition = { transitions.enter },
                            popExitTransition = { transitions.exit },
                        ) {
                            composable("a") { Screen() }
                            // A route that draws nothing while it loads.
                            composable("b") { }
                        }
                    }
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        runOnUiThread { nav.navigate("b") }
        val limit = luminance(GreenThumbColors.dark.background)
        for (step in 0 until 12) {
            mainClock.advanceTimeBy(20)
            val pixel = onNodeWithTag("root").captureToImage().toPixelMap()[32, 32]
            assertTrue(luminance(pixel) <= limit + 0.01f, "frame $step: ${pixel.toArgb().toUInt().toString(16)} brighter than the screen background")
        }
        mainClock.autoAdvance = true
        waitForIdle()
        val settled = onNodeWithTag("root").captureToImage().toPixelMap()[32, 32]
        assertEquals(GreenThumbExtendedColors.dark.page.toArgb(), settled.toArgb())
    }
}

@androidx.compose.runtime.Composable
private fun Screen() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

private fun luminance(color: Color): Float = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
