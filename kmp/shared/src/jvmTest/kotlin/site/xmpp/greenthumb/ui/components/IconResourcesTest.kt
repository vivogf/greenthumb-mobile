package site.xmpp.greenthumb.ui.components

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Retro-cosmos icon set (D§3.4, VAL-LOOK-009): stroke 1.5, butt cap, miter join, black stroke
 * recoloured at runtime, no fill except the filled drop, and no corner accent dot.
 */
class IconResourcesTest {

    private val drawableDir = File("src/commonMain/composeResources/drawable")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val expected = setOf(
        "drop", "drop_filled", "fert", "pot", "scissors", "sun", "cal", "camera", "smartcam", "diag",
        "species", "journal", "photo", "loc", "edit", "check", "close", "leaf", "back", "chev", "plus", "lock",
    )

    private fun iconFiles(): List<File> =
        drawableDir.listFiles { f -> f.name.startsWith("gt_ic_") && f.name.endsWith(".xml") }
            .orEmpty()
            .sortedBy { it.name }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.attr(name: String): String? =
        getAttributeNS(androidNs, name).takeIf { hasAttributeNS(androidNs, name) }

    private fun Element.paths(): List<Element> {
        val nodes = getElementsByTagName("path")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun the_set_holds_exactly_the_22_design_icons() {
        val names = iconFiles().map { it.name.removePrefix("gt_ic_").removeSuffix(".xml") }.toSet()
        assertEquals(expected, names)
    }

    @Test
    fun every_icon_is_a_24_unit_vector() {
        for (file in iconFiles()) {
            val root = parse(file)
            assertEquals("vector", root.tagName, file.name)
            assertEquals("24", root.attr("viewportWidth"), "${file.name} viewportWidth")
            assertEquals("24", root.attr("viewportHeight"), "${file.name} viewportHeight")
            assertEquals("24dp", root.attr("width"), "${file.name} width")
            assertEquals("24dp", root.attr("height"), "${file.name} height")
        }
    }

    @Test
    fun every_path_is_a_thin_square_stroke_in_black() {
        for (file in iconFiles()) {
            val paths = parse(file).paths()
            assertTrue(paths.isNotEmpty(), "${file.name} has paths")
            for (path in paths) {
                assertEquals("1.5", path.attr("strokeWidth"), "${file.name} strokeWidth")
                assertEquals("butt", path.attr("strokeLineCap"), "${file.name} strokeLineCap")
                assertEquals("miter", path.attr("strokeLineJoin"), "${file.name} strokeLineJoin")
                assertEquals("#FF000000", path.attr("strokeColor"), "${file.name} strokeColor")
                assertTrue(!path.attr("pathData").isNullOrBlank(), "${file.name} pathData")
            }
        }
    }

    @Test
    fun only_the_filled_drop_has_a_fill() {
        for (file in iconFiles()) {
            val fills = parse(file).paths().mapNotNull { it.attr("fillColor") }
            if (file.name == "gt_ic_drop_filled.xml") {
                assertEquals(listOf("#FF000000"), fills, file.name)
            } else {
                assertTrue(fills.isEmpty(), "${file.name} must not have fillColor")
                assertTrue(!file.readText().contains("fillColor"), "${file.name} mentions fillColor")
            }
        }
    }

    @Test
    fun no_colour_other_than_black_appears_in_any_icon() {
        val colour = Regex("#[0-9A-Fa-f]{3,8}\\b")
        for (file in iconFiles()) {
            val others = colour.findAll(file.readText()).map { it.value }.filter { it != "#FF000000" }.toList()
            assertTrue(others.isEmpty(), "${file.name} has colours $others")
        }
    }

    /** The v2 yellow dot was a small circle near a corner; a circle sub-path is `M x y a r r 0 1 0 2r 0`. */
    @Test
    fun no_small_circle_sits_in_a_corner() {
        val circle = Regex("M\\s*(-?[\\d.]+)[ ,]+(-?[\\d.]+)\\s*a\\s*([\\d.]+)[ ,]+([\\d.]+)[ ,]+0[ ,]+1[ ,]+0")
        for (file in iconFiles()) {
            for (path in parse(file).paths()) {
                circle.findAll(path.attr("pathData").orEmpty()).forEach { match ->
                    val (x, y, r) = match.destructured.toList().take(3).map { it.toFloat() }
                    val cx = x + r
                    val nearX = cx < 5f || cx > 19f
                    val nearY = y < 5f || y > 19f
                    assertTrue(!(r <= 2f && nearX && nearY), "${file.name}: corner dot at ($cx, $y) r=$r")
                }
            }
        }
    }

    @Test
    fun path_coordinates_stay_inside_the_viewport() {
        val number = Regex("-?\\d*\\.?\\d+")
        for (file in iconFiles()) {
            for (path in parse(file).paths()) {
                val absoluteMoves = Regex("M\\s*(-?[\\d.]+)[ ,]+(-?[\\d.]+)").findAll(path.attr("pathData").orEmpty())
                absoluteMoves.forEach { m ->
                    val (x, y) = m.destructured.toList().map { it.toFloat() }
                    assertTrue(x in 0f..24f && y in 0f..24f, "${file.name}: move to ($x, $y)")
                }
                assertTrue(number.containsMatchIn(path.attr("pathData").orEmpty()), file.name)
            }
        }
    }
}
