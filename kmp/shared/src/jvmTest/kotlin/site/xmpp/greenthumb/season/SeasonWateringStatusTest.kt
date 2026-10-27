package site.xmpp.greenthumb.season

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.data.PlantClocks
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.ok
import site.xmpp.greenthumb.data.openHarness
import site.xmpp.greenthumb.data.plant
import site.xmpp.greenthumb.data.plantsJson
import site.xmpp.greenthumb.data.requestBody
import site.xmpp.greenthumb.data.wateringStatus
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.screens.dashboard.DashboardFilter
import site.xmpp.greenthumb.ui.screens.dashboard.filterAndSortPlants
import site.xmpp.greenthumb.ui.screens.dashboard.needsWaterCount
import site.xmpp.greenthumb.ui.screens.plantdetail.plantCareCountdowns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VAL-SEASON-017 and VAL-SEASON-025 logic: the production status functions with the season applied. */
class SeasonWateringStatusTest {

    private val latitudes: TzLatitudes = runBlocking {
        decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString())
    }

    private fun clocksAt(date: String, zone: String): PlantClocks {
        val tz = TimeZone.of(zone)
        val millis = LocalDateTime.parse("${date}T12:00").toInstant(tz).toEpochMilliseconds()
        return PlantClocks(nowMillis = { millis }, zone = tz)
    }

    private fun season(mode: SeasonMode, zone: String, date: String = "2026-12-12"): Pair<SeasonContext, LocalDate> {
        val clocks = clocksAt(date, zone)
        return clocks.season(mode, latitudes) to clocks.today()
    }

    @Test
    fun dec_1_base_10_on_dec_12_per_mode() {
        val (north, today) = season(SeasonMode.North, "Asia/Singapore")
        assertEquals(4, daysUntilWatering("2026-12-01", 10, today, north))
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-12-01", 10, today, north))

        val (off, _) = season(SeasonMode.Off, "Europe/Moscow")
        assertEquals(-1, daysUntilWatering("2026-12-01", 10, today, off))
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-12-01", 10, today, off))

        val (autoSingapore, _) = season(SeasonMode.Auto, "Asia/Singapore")
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-12-01", 10, today, autoSingapore))

        val (autoMoscow, _) = season(SeasonMode.Auto, "Europe/Moscow")
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-12-01", 10, today, autoMoscow))
    }

    @Test
    fun base_11_effective_17_is_today_under_north() {
        val (north, today) = season(SeasonMode.North, "Europe/Moscow", "2026-12-18")
        assertEquals(17, north.effectiveWaterIntervalDays(11, "2026-12-01"))
        assertEquals(0, daysUntilWatering("2026-12-01", 11, today, north))
        assertEquals(WateringStatus.Today, wateringStatus("2026-12-01", 11, today, north))
    }

    @Test
    fun other_care_countdowns_do_not_depend_on_the_mode() {
        val today = LocalDate.parse("2026-12-12")
        // Watered in December (north-dormant), so north and auto in Moscow stretch the watering interval.
        val fixture = plant("care", "2026-12-01", frequency = 10).copy(
            fertilizeFrequencyDays = 14,
            lastFertilizedDate = "2026-12-01",
            repotFrequencyMonths = 3,
            lastRepottedDate = "2026-11-01",
            pruneFrequencyMonths = 2,
            lastPrunedDate = "2026-10-15",
        )
        val byMode = SeasonMode.entries.associateWith { mode ->
            plantCareCountdowns(fixture, today, season(mode, "Europe/Moscow").first)
        }

        assertEquals(4, byMode.getValue(SeasonMode.North).water, "north stretches watering: $byMode")
        assertEquals(4, byMode.getValue(SeasonMode.Auto).water, "auto in Moscow stretches watering: $byMode")
        assertEquals(-1, byMode.getValue(SeasonMode.Off).water, "off keeps the base interval: $byMode")
        for ((mode, countdowns) in byMode) {
            assertEquals(3, countdowns.fertilize, "fertilize in mode $mode")
            assertEquals(51, countdowns.repot, "repot in mode $mode")
            assertEquals(3, countdowns.prune, "prune in mode $mode")
        }
    }
    @Test
    fun winter_hint_applies_only_when_adjusted() {
        val (north, _) = season(SeasonMode.North, "Asia/Singapore")
        assertTrue(north.isAdjusted("2026-12-01"))
        assertFalse(north.isAdjusted("2026-10-01"))
        val (off, _) = season(SeasonMode.Off, "Europe/Moscow")
        assertFalse(off.isAdjusted("2026-12-01"))
        val (autoSingapore, _) = season(SeasonMode.Auto, "Asia/Singapore")
        assertFalse(autoSingapore.isAdjusted("2026-12-01"))
        val (south, _) = season(SeasonMode.South, "Europe/Moscow")
        assertTrue(south.isAdjusted("2026-07-01"))
        assertFalse(south.isAdjusted("2026-12-01"))
    }

    @Test
    fun dashboard_filter_counts_and_sort_use_the_seasonal_status() {
        val today = LocalDate.parse("2026-12-12")
        val adjusted = plant("adjusted", "2026-12-01", frequency = 10) // north: +4, off: -1
        val overdue = plant("overdue", "2026-11-20", frequency = 7) // north: E=11 → Dec 1, -11
        val fresh = plant("fresh", "2026-12-10", frequency = 7) // north: E=11 → Dec 21, +9
        val plants = listOf(fresh, adjusted, overdue)
        val (north, _) = season(SeasonMode.North, "Asia/Singapore")
        val (off, _) = season(SeasonMode.Off, "Asia/Singapore")

        assertEquals(1, needsWaterCount(plants, today, north))
        assertEquals(2, needsWaterCount(plants, today, off))
        assertEquals(
            listOf("overdue"),
            filterAndSortPlants(plants, "", DashboardFilter.NeedsWater, today, north).map { it.id },
        )
        assertEquals(
            listOf("overdue", "adjusted"),
            filterAndSortPlants(plants, "", DashboardFilter.NeedsWater, today, off).map { it.id },
        )
        assertEquals(
            listOf("adjusted", "fresh"),
            filterAndSortPlants(plants, "", DashboardFilter.Healthy, today, north).map { it.id },
        )
        assertEquals(
            listOf("overdue", "adjusted", "fresh"),
            filterAndSortPlants(plants, "", DashboardFilter.All, today, north).map { it.id },
        )
    }

    /**
     * Same fixture as the water-all case in PWA `server/care/schedule.test.ts`: Berlin, D = 2026-01-20,
     * mode north → P2 and P3; mode off → P1, P2 and P3.
     */
    @Test
    fun optimistic_water_all_selects_the_same_plants_as_the_server_schedule() = runBlocking<Unit> {
        val fixture = listOf(
            plant("P1", "2026-01-13", frequency = 7),
            plant("P2", "2026-01-13", frequency = 4),
            plant("P3", "2025-10-31", frequency = 81),
        )
        for ((mode, expected) in listOf(
            SeasonMode.North to listOf("P2", "P3"),
            SeasonMode.Off to listOf("P1", "P2", "P3"),
        )) {
            val clocks = clocksAt("2026-01-20", "Europe/Berlin")
            var body: String? = null
            openHarness(nowMillis = clocks.nowMillis, zone = clocks.zone, handler = { request ->
                if (request.method == HttpMethod.Get) {
                    ok(plantsJson(fixture))
                } else {
                    body = requestBody(request.body)
                    ok("""{"success":true,"count":${expected.size}}""")
                }
            }).use { box ->
                box.repo.refresh()
                runCatching { box.repo.waterAll(clocks.season(mode, latitudes)) }
                val watered = box.repo.currentPlants().filter { it.lastWateredDate == "2026-01-20" }.map { it.id }
                assertEquals(expected, watered.sorted(), "mode $mode, request body $body")
            }
        }
    }

    @Test
    fun season_mode_round_trips_through_the_cached_user_json() {
        val user = ApiClient.json.decodeFromString(
            UserDto.serializer(),
            """{"id":7,"recovery_key":"changeme-key","created_at":"2026-01-01","season_mode":"south"}""",
        )
        assertEquals("south", user.seasonMode)
        assertEquals(SeasonMode.South, seasonModeOf(user.seasonMode))
        val again = ApiClient.json.decodeFromString(UserDto.serializer(), ApiClient.json.encodeToString(UserDto.serializer(), user))
        assertEquals("south", again.seasonMode)
    }
}
