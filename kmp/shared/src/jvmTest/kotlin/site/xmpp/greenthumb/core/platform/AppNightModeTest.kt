package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import site.xmpp.greenthumb.core.storage.ThemePreference

/** VAL-LOOK-004: the system starting window follows the in-app Light/Dark/Auto choice. */
class AppNightModeTest {

    @Test
    fun explicitChoicesPinTheSystemNightMode() {
        assertEquals(AppNightMode.Light, appNightMode(ThemePreference.Light))
        assertEquals(AppNightMode.Dark, appNightMode(ThemePreference.Dark))
    }

    @Test
    fun autoAndUnsetFollowTheSystem() {
        assertEquals(AppNightMode.FollowSystem, appNightMode(ThemePreference.Auto))
        assertEquals(AppNightMode.FollowSystem, appNightMode(null))
    }
}
