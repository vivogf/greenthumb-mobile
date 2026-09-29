package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpMethod
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import site.xmpp.greenthumb.ui.components.pickerToday

/**
 * Stage 11 п.2: часовой пояс зафиксирован явно.
 *
 * `lib/utils.ts:8-19` разбирает `YYYY-MM-DD` как локальную дату и сравнивает
 * с локальной полуночью, `lib/utils.ts:38-40` `todayString()` — тоже локальный.
 * Порт обязан использовать `TimeZone.currentSystemDefault()`; `TimeZone.UTC`
 * сдвинул бы статус полива на сутки у большинства пользователей.
 *
 * Отдельный тест плана: одна и та же дата при часовом поясе UTC+3 и UTC-8
 * даёт тот же статус, что в текущем приложении.
 */
class WateringStatusTimezoneTest {

    private val plus3: TimeZone = TimeZone.of("UTC+03:00")
    private val minus8: TimeZone = TimeZone.of("UTC-08:00")

    /**
     * Растение: полито 2026-09-18, раз в 7 дней → следующий полив 2026-09-25.
     * RN `getWateringStatus` при today == 2026-09-25 даёт `today` (daysLeft 0) —
     * не `healthy` и не `overdue`.
     */
    private fun rnReferenceStatus(today: LocalDate): WateringStatus =
        wateringStatus("2026-09-18", 7, today)

    private fun millisAt(localTime: LocalDateTime, zone: TimeZone): Long =
        localTime.toInstant(zone).toEpochMilliseconds()

    private fun clocksAt(localTime: LocalDateTime, zone: TimeZone): PlantClocks {
        val millis = millisAt(localTime, zone)
        return PlantClocks(nowMillis = { millis }, zone = zone)
    }

    @Test
    fun same_local_date_gives_same_status_in_utc_plus3_and_utc_minus8() {
        // Одна локальная дата 2026-09-25 — в РАЗНЫЕ моменты реального времени.
        // Моменты выбраны у границ суток, где UTC-даты уже другие (24.09 и 26.09):
        // будь «сегодня» посчитано в UTC, статусы разъехались бы на сутки.
        val wallTimes = listOf(
            LocalDateTime(2026, 9, 25, 0, 30),
            LocalDateTime(2026, 9, 25, 12, 0),
            LocalDateTime(2026, 9, 25, 23, 30),
        )
        for (wall in wallTimes) {
            val inPlus3 = clocksAt(wall, plus3)
            val inMinus8 = clocksAt(wall, minus8)

            assertEquals(LocalDate(2026, 9, 25), inPlus3.today(), "локальный «сегодня» в UTC+3, $wall")
            assertEquals(LocalDate(2026, 9, 25), inMinus8.today(), "локальный «сегодня» в UTC-8, $wall")

            // Тот же статус, что в текущем приложении (эталон — RN-бакеты).
            val expected = rnReferenceStatus(LocalDate(2026, 9, 25))
            assertEquals(WateringStatus.Today, expected, "RN при today == 2026-09-25 даёт today")
            assertEquals(expected, wateringStatus("2026-09-18", 7, inPlus3.today()), "UTC+3, $wall")
            assertEquals(expected, wateringStatus("2026-09-18", 7, inMinus8.today()), "UTC-8, $wall")
        }
    }

    @Test
    fun utc_today_shifts_status_by_a_day_and_is_forbidden() {
        // Документирует запрет TimeZone.UTC для «сегодня»: на граничных моментах
        // UTC-даты — 24.09 и 26.09, статус уезжает в healthy/overdue — ровно
        // тот сдвиг на сутки, о котором предупреждает Stage 11 п.2.
        val earlyWall = LocalDateTime(2026, 9, 25, 0, 30) // локально 25.09 в UTC+3 → 24.09 21:30Z
        val lateWall = LocalDateTime(2026, 9, 25, 23, 30) // локально 25.09 в UTC-8 → 26.09 07:30Z

        // Те же моменты реального времени, что у app-часов выше, но «сегодня»
        // посчитано в UTC — как раз то, что запрещено.
        val utcEarly = PlantClocks(
            nowMillis = { millisAt(earlyWall, plus3) },
            zone = TimeZone.UTC,
        )
        val utcLate = PlantClocks(
            nowMillis = { millisAt(lateWall, minus8) },
            zone = TimeZone.UTC,
        )

        assertEquals(LocalDate(2026, 9, 24), utcEarly.today())
        assertEquals(LocalDate(2026, 9, 26), utcLate.today())

        val appStatus = rnReferenceStatus(LocalDate(2026, 9, 25))
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-09-18", 7, utcEarly.today()))
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-09-18", 7, utcLate.today()))
        assertNotEquals(appStatus, wateringStatus("2026-09-18", 7, utcEarly.today()))
        assertNotEquals(appStatus, wateringStatus("2026-09-18", 7, utcLate.today()))
    }

    @Test
    fun production_clocks_use_current_system_default() {
        // Порт обязан использовать TimeZone.currentSystemDefault() (план Stage 11 п.2).
        val zone = TimeZone.currentSystemDefault()
        assertEquals(zone, PlantClocks().zone, "дефолт PlantClocks — системный пояс, не UTC")
        assertEquals(
            Clock.System.now().toLocalDateTime(zone).date,
            PlantClocks().today(),
            "today() следует системному поясу",
        )
        assertEquals(
            Clock.System.now().toLocalDateTime(zone).date,
            pickerToday(),
            "pickerToday() — тоже системный пояс",
        )
    }

    @Test
    fun water_patch_carries_local_date_not_utc_date() = runBlocking<Unit> {
        // Нормализация дат в мутации: 25.09 локального дня в UTC+3 (момент ещё
        // 24.09 по UTC) и в UTC-8 (момент уже 26.09 по UTC) → одна дата «2026-09-25».
        for ((zone, wall) in listOf(
            plus3 to LocalDateTime(2026, 9, 25, 1, 0),
            minus8 to LocalDateTime(2026, 9, 25, 23, 0),
        )) {
            val clocks = clocksAt(wall, zone)
            var body: String? = null
            val handler: MockRequestHandler = { request ->
                if (request.method == HttpMethod.Get) {
                    ok(plantsJson(listOf(overduePlant())))
                } else {
                    body = requestBody(request.body)
                    ok(plantJson(overduePlant().copy(lastWateredDate = "2026-09-25")))
                }
            }
            val harness = openHarness(
                nowMillis = { clocks.nowMillis() },
                zone = zone,
                handler = handler,
            )
            harness.use { box ->
                box.repo.refresh()
                box.repo.water("overdue")
                assertEquals(
                    """{"last_watered_date":"2026-09-25"}""",
                    body,
                    "PATCH несёт локальную дату ($zone, $wall), не UTC-дату",
                )
                assertEquals("2026-09-25", box.repo.currentPlants().single().lastWateredDate)
            }
        }
    }
}
