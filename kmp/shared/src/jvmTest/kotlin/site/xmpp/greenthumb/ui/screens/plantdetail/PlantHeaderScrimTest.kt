package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dark header scrim exists for legibility over a photo; Light without a photo keeps the light placeholder. */
class PlantHeaderScrimTest {

    @Test
    fun light_theme_without_photo_has_no_scrim() {
        assertFalse(headerNeedsScrim(photoUrl = "", darkTheme = false))
        assertFalse(headerNeedsScrim(photoUrl = "   ", darkTheme = false))
    }

    @Test
    fun light_theme_with_photo_keeps_scrim() {
        assertTrue(headerNeedsScrim(photoUrl = "https://greenthumb.xmpp.site/photos/a.jpg", darkTheme = false))
        assertTrue(headerNeedsScrim(photoUrl = "data:image/jpeg;base64,AAAA", darkTheme = false))
    }

    @Test
    fun dark_theme_always_keeps_scrim() {
        assertTrue(headerNeedsScrim(photoUrl = "", darkTheme = true))
        assertTrue(headerNeedsScrim(photoUrl = "https://greenthumb.xmpp.site/photos/a.jpg", darkTheme = true))
    }
}
