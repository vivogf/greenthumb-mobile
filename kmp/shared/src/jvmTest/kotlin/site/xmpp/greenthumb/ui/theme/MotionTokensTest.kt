package site.xmpp.greenthumb.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.storage.ThemePreference

/** VAL-LOOK-001 / VAL-LOOK-003: short fade-only screen transitions, none under reduce motion. */
class MotionTokensTest {

    @Test
    fun screenTransitionsAreShortAndDisabledByReduceMotion() {
        assertEquals(200, Motion.ScreenEnterMs)
        assertEquals(160, Motion.ScreenExitMs)
        assertTrue(Motion.ScreenEnterMs <= 200 && Motion.ScreenExitMs <= 200)

        val normal = screenTransitions(reduceMotion = false)
        assertEquals(fadeIn(tween(200)), normal.enter)
        assertEquals(fadeOut(tween(160)), normal.exit)

        val reduced = screenTransitions(reduceMotion = true)
        assertEquals(EnterTransition.None, reduced.enter)
        assertEquals(ExitTransition.None, reduced.exit)
    }

    @Test
    fun reduceMotionFollowsTheSystemAnimationScales() {
        assertFalse(isReduceMotion(animatorDurationScale = 1f, transitionAnimationScale = 1f))
        assertFalse(isReduceMotion(animatorDurationScale = 0.5f, transitionAnimationScale = 2f))
        assertTrue(isReduceMotion(animatorDurationScale = 0f, transitionAnimationScale = 1f))
        assertTrue(isReduceMotion(animatorDurationScale = 1f, transitionAnimationScale = 0f))
        assertTrue(isReduceMotion(animatorDurationScale = 0f, transitionAnimationScale = 0f))
    }

    @Test
    fun startupUsesTheLastWindowThemeUntilThePreferenceIsRead() {
        // Saved Dark on a light system: the first frames must not draw the light theme.
        assertTrue(resolveDarkTheme(null, systemDark = false, preferenceRead = false, lastWindowDark = true))
        assertFalse(resolveDarkTheme(null, systemDark = true, preferenceRead = false, lastWindowDark = false))
        // Nothing saved yet (first launch): follow the system.
        assertFalse(resolveDarkTheme(null, systemDark = false, preferenceRead = false, lastWindowDark = null))
        // Once read, the preference wins over the saved window theme.
        assertFalse(resolveDarkTheme(ThemePreference.Light, systemDark = true, preferenceRead = true, lastWindowDark = true))
        assertFalse(resolveDarkTheme(null, systemDark = false, preferenceRead = true, lastWindowDark = true))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun themeProvidesReduceMotion() = runDesktopComposeUiTest {
        var system: Boolean? = null
        var byDefault: Boolean? = null
        var forced: Boolean? = null
        setContent {
            system = systemReduceMotion()
            GreenThumbTheme(darkTheme = true) { byDefault = LocalReduceMotion.current }
            GreenThumbTheme(darkTheme = true, reduceMotion = true) { forced = LocalReduceMotion.current }
        }
        waitForIdle()
        assertEquals(false, system)
        assertEquals(false, byDefault)
        assertEquals(true, forced)
    }
}
