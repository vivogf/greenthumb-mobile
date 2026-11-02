package com.greenthumbplantcare

import androidx.compose.ui.graphics.toArgb
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element
import site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors

/** VAL-LOOK-001: the window behind every screen is the dark page colour, never the white platform default. */
class ThemeResourceTest {

    private val res = File("src/main/res")

    @Test
    fun manifestUsesTheAppWindowTheme() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:theme=\"@style/Theme.GreenThumb\""), "MainActivity uses Theme.GreenThumb")
        assertFalse(manifest.contains("Theme.Material.Light"), "no Light platform theme in the manifest")
    }

    @Test
    fun windowThemeIsDarkWithThePageBackground() {
        val style = styles().single { it.getAttribute("name") == "Theme.GreenThumb" }
        val parent = style.getAttribute("parent")
        assertEquals("android:Theme.Material.NoActionBar", parent)
        assertFalse(parent.contains("Light"))
        val items = items(style)
        val page = colors().getValue("gt_page")
        for (attr in listOf("android:windowBackground", "android:colorBackground", "android:statusBarColor", "android:navigationBarColor", "android:windowSplashScreenBackground")) {
            assertEquals("@color/gt_page", items[attr], attr)
        }
        assertEquals("#FF0D0E0F", page.uppercase())
        assertEquals(GreenThumbExtendedColors.dark.page.toArgb(), page.removePrefix("#").toLong(16).toInt())
    }

    @Test
    fun api31WindowThemesFollowTheAppNightMode() {
        val light = items(styleIn("values-v31"))
        val dark = items(styleIn("values-night-v31"))
        for (attr in listOf("android:windowBackground", "android:colorBackground", "android:statusBarColor", "android:navigationBarColor", "android:windowSplashScreenBackground", "android:windowSplashScreenIconBackgroundColor")) {
            assertEquals("@color/gt_page_light", light[attr], "values-v31 $attr")
            assertEquals("@color/gt_page", dark[attr], "values-night-v31 $attr")
        }
        assertEquals("true", light["android:windowLightStatusBar"])
        assertEquals("false", dark["android:windowLightStatusBar"])
        val pageLight = colors().getValue("gt_page_light")
        assertEquals(GreenThumbExtendedColors.light.page.toArgb(), pageLight.removePrefix("#").toLong(16).toInt())
    }

    private fun styleIn(dir: String): Element =
        elements(File(res, "$dir/themes.xml"), "style").single { it.getAttribute("name") == "Theme.GreenThumb" }
    private fun styles(): List<Element> = elements(File(res, "values/themes.xml"), "style")

    private fun items(style: Element): Map<String, String> {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun colors(): Map<String, String> = elements(File(res, "values/colors.xml"), "color")
        .associate { it.getAttribute("name") to it.textContent.trim() }

    private fun elements(file: File, tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
