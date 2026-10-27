package site.xmpp.greenthumb.season

import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.ui.res.Res
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * VAL-SEASON-008, Kotlin side: the ±15° tropics threshold and the zone table that the PWA runner
 * checks for `shared/season/rule.ts`. Each zone prints one `VAL-SEASON-008 zone=… expected=… kotlin=…`
 * line (kept in the JUnit XML system-out), so the zone → TS → Kotlin table can be built from the output.
 */
class SeasonHemisphereTest {

    private val bundled: TzLatitudes by lazy {
        runBlocking { decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString()) }
    }

    private fun checkZones(rule: SeasonRule, expected: Hemisphere, zones: List<String>, label: (String) -> String = { it }) {
        val failures = mutableListOf<String>()
        for (zone in zones) {
            val actual = rule.hemisphere(zone)
            println("VAL-SEASON-008 zone=\"${label(zone)}\" expected=${expected.wire} kotlin=${actual.wire}")
            if (actual != expected) failures += "zone \"${label(zone)}\": expected ${expected.wire}, got ${actual.wire}"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun threshold_plus_15_is_north_minus_15_is_south_and_14_99_is_tropical() {
        val latitudes = mapOf(
            "Test/Plus15" to 15.0,
            "Test/Minus15" to -15.0,
            "Test/Plus14_99" to 14.99,
            "Test/Minus14_99" to -14.99,
        )
        val rule = SeasonRule(TzLatitudes(latitudes))
        val expected = mapOf(
            "Test/Plus15" to Hemisphere.North,
            "Test/Minus15" to Hemisphere.South,
            "Test/Plus14_99" to Hemisphere.Tropical,
            "Test/Minus14_99" to Hemisphere.Tropical,
        )
        val failures = mutableListOf<String>()
        for ((zone, want) in expected) {
            val actual = rule.hemisphere(zone)
            println("VAL-SEASON-008 latitude=${latitudes.getValue(zone)} expected=${want.wire} kotlin=${actual.wire}")
            if (actual != want) failures += "latitude ${latitudes.getValue(zone)}: expected ${want.wire}, got ${actual.wire}"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun tropical_zones() = checkZones(
        SeasonRule(bundled),
        Hemisphere.Tropical,
        listOf("Asia/Singapore", "Singapore", "Africa/Nairobi", "America/Guayaquil", "Australia/Darwin"),
    )

    @Test
    fun north_zones() = checkZones(
        SeasonRule(bundled),
        Hemisphere.North,
        listOf("Europe/Moscow", "Asia/Calcutta", "US/Eastern", "Atlantic/Reykjavik", "Iceland"),
    )

    @Test
    fun south_zones() = checkZones(
        SeasonRule(bundled),
        Hemisphere.South,
        listOf("Australia/Sydney", "Australia/ACT", "America/Sao_Paulo", "Pacific/Auckland", "Antarctica/Syowa"),
    )

    @Test
    fun zones_without_latitude_give_none() = checkZones(
        SeasonRule(bundled),
        Hemisphere.None,
        listOf("UTC", "Etc/UTC", "Etc/GMT+5", "GMT", "", "Mars/Olympus"),
    )
}
