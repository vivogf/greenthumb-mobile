package site.xmpp.greenthumb.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The public privacy page, the Data Safety notes and the manifest must describe the in-app camera
 * the same way (M11): the camera permission only serves the in-app viewfinder, a photo stays on the
 * phone until «Узнать вид» / «Проверить растение», the gallery is the system photo picker without a
 * media permission, and outgoing photos lose EXIF/GPS.
 */
class PrivacyDocsCameraTest {

    private val privacy = File("../../docs/privacy.html").readText()
    private val dataSafety = File("../docs/data-safety.md").readText()
    private val manifest = File("../androidApp/src/main/AndroidManifest.xml").readText()

    private fun flat(text: String) = text.replace(Regex("""\s+"""), " ")

    @Test
    fun privacy_page_no_longer_says_taking_a_photo_sends_it() {
        val page = flat(privacy)
        assertFalse(page.contains("take or select a photo"), "old smart-camera sentence still present")
        assertFalse(page.contains("pick the <em>Plant</em>"), "old mode-choice wording still present")
    }

    @Test
    fun privacy_page_describes_the_in_app_camera() {
        val page = flat(privacy)
        val facts = listOf(
            "camera permission",
            "viewfinder",
            "shutter",
            "stays on your phone",
            "<em>Identify species</em> [<em>Узнать вид</em>]",
            "<em>Check the plant</em> [<em>Проверить растение</em>]",
            "<em>Find species by photo</em> [<em>Узнать вид по фото</em>]",
            "system photo picker",
            "EXIF",
            "GPS",
            "greenthumbapp@proton.me",
        )
        for (fact in facts) assertTrue(page.contains(fact), "privacy.html misses \"$fact\"")
    }

    @Test
    fun data_safety_agrees_with_the_privacy_page() {
        val notes = flat(dataSafety)
        for (fact in listOf("camera frames", "system photo picker", "only the camera permission", "EXIF")) {
            assertTrue(notes.contains(fact), "data-safety.md misses \"$fact\"")
        }
        val pageDate = Regex("""Effective date: (\d{4}-\d{2}-\d{2})""").find(privacy)?.groupValues?.get(1)
        val notesDate = Regex("""Last checked against the code and the server configuration: (\d{4}-\d{2}-\d{2})""")
            .find(dataSafety)?.groupValues?.get(1)
        assertEquals(pageDate, notesDate, "both documents carry the same revision date")
    }

    @Test
    fun manifest_asks_only_for_the_camera_for_photos() {
        assertTrue(manifest.contains("android.permission.CAMERA"), "camera permission declared")
        for (permission in listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VISUAL_USER_SELECTED", "READ_EXTERNAL_STORAGE")) {
            assertFalse(manifest.contains("android.permission.$permission"), "$permission must not be declared")
        }
    }
}
