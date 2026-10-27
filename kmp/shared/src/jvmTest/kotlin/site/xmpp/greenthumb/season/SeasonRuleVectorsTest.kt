package site.xmpp.greenthumb.season

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.ui.res.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VAL-SEASON-010: every case of the vector file shared with the PWA (`shared/season/season-vectors.json`,
 * byte-identical copy) runs against the commonMain [SeasonRule] with the bundled `tz-latitudes.json`.
 */
class SeasonRuleVectorsTest {

    private val latitudesSource = File("src/commonMain/composeResources/files/tz-latitudes.json")

    private fun bundledLatitudes(): TzLatitudes = runBlocking {
        decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString())
    }

    @Test
    fun bundled_resource_is_the_source_file() = runBlocking {
        val bundled = Res.readBytes(TZ_LATITUDES_RESOURCE)
        assertTrue(bundled.contentEquals(latitudesSource.readBytes()), "Res bytes differ from the source file")
    }

    @Test
    fun every_shared_vector_passes() {
        val text = checkNotNull(javaClass.getResource("/season-vectors.json")) { "season-vectors.json missing" }
            .readText()
        val vectors = Json.parseToJsonElement(text).jsonArray
        val latitudes = bundledLatitudes()
        val failures = mutableListOf<String>()
        var ran = 0
        for ((i, element) in vectors.withIndex()) {
            val v = element.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            val tz = v.getValue("tz").jsonPrimitive.content
            val mode = checkNotNull(SeasonMode.fromWire(v.getValue("mode").jsonPrimitive.content)) { "mode in $name" }
            val last = v.getValue("lastWatered").jsonPrimitive.content
            val base = v.getValue("baseDays").jsonPrimitive.int
            val expectedHemisphere = v.getValue("hemisphere").jsonPrimitive.content
            val expectedDays = v.getValue("effectiveDays").jsonPrimitive.int
            val expectedDue = v.getValue("nextDue").jsonPrimitive.content
            val rule = SeasonRule(latitudes)
            val hemisphere = rule.hemisphere(tz).wire
            val days = rule.effectiveWaterIntervalDays(base, last, mode, tz)
            val due = rule.nextWaterDue(last, base, mode, tz)
            ran++
            if (hemisphere != expectedHemisphere || days != expectedDays || due != expectedDue) {
                failures += "vector $i ($name): hemisphere=$hemisphere/$expectedHemisphere " +
                    "effectiveDays=$days/$expectedDays nextDue=$due/$expectedDue"
            }
        }
        println("season-vectors.json cases run: $ran of ${vectors.size}")
        assertEquals(vectors.size, ran)
        assertTrue(vectors.size >= 40, "only ${vectors.size} vectors")
        assertEquals(emptyList(), failures)
    }

    @Test
    fun rule_source_has_no_platform_imports() {
        val source = File("src/commonMain/kotlin/site/xmpp/greenthumb/season/SeasonRule.kt").readText()
        val imports = source.lines().filter { it.startsWith("import ") }
        val banned = imports.filter {
            it.startsWith("import java.") || it.startsWith("import android.") ||
                it.contains("Clock") || it.contains("TimeZone") || it.contains("compose")
        }
        assertEquals(emptyList(), banned)
    }

    @Test
    fun aliases_and_fixed_zones_resolve_like_the_server() {
        val rule = SeasonRule(bundledLatitudes())
        assertEquals(Hemisphere.North, rule.hemisphere("Asia/Calcutta"))
        assertEquals(Hemisphere.South, rule.hemisphere("Antarctica/Syowa"))
        assertEquals(Hemisphere.Tropical, rule.hemisphere("Asia/Singapore"))
        for (zone in listOf("UTC", "Etc/UTC", "Etc/GMT+5", "Mars/Olympus", "", null)) {
            assertEquals(Hemisphere.None, rule.hemisphere(zone), "zone $zone")
        }
    }

    @Test
    fun mode_wire_values_and_fallback() {
        assertEquals(SeasonMode.North, SeasonMode.fromWire("north"))
        assertEquals(null, SeasonMode.fromWire("bogus"))
        assertEquals(SeasonMode.Auto, seasonModeOf(null))
        assertEquals(SeasonMode.Auto, seasonModeOf("bogus"))
        assertEquals(SeasonMode.Off, seasonModeOf("off"))
    }
}
