package site.xmpp.greenthumb.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Порт `lib/utils.ts` getWateringStatus: healthy только когда следующий
 * полив строго в будущем. waterAll трогает всё остальное.
 */
class WateringStatusTest {

    private val today = LocalDate.parse(TODAY)

    @Test
    fun overdue_today_and_healthy_match_rn_buckets() {
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-09-01", 7, today))
        assertEquals(WateringStatus.Today, wateringStatus("2026-09-18", 7, today))
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-09-24", 7, today))
        assertEquals(0, daysUntilWatering("2026-09-18", 7, today))
        assertEquals(6, daysUntilWatering("2026-09-24", 7, today))
    }

    @Test
    fun overdue_reports_negative_days() {
        // RN `getDaysUntilWatering`: просрочка — отрицательное число дней
        // (next 2026-09-08 против today 2026-09-25 → −17).
        assertEquals(-17, daysUntilWatering("2026-09-01", 7, today))
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-09-01", 7, today))
        // Граничный случай: просрочка ровно на один день.
        assertEquals(-1, daysUntilWatering("2026-09-18", 7, LocalDate.parse("2026-09-26")))
    }

    @Test
    fun one_day_frequency_boundaries_match_rn_buckets() {
        // Частота 1 день: вчера → today (0), сегодня → healthy (+1), позавчера → overdue (−1).
        assertEquals(WateringStatus.Today, wateringStatus("2026-09-24", 1, today))
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-09-25", 1, today))
        assertEquals(WateringStatus.Overdue, wateringStatus("2026-09-23", 1, today))
    }

    @Test
    fun next_watering_crosses_month_and_year_calendarily() {
        // Дни считаются по календарю (epoch-дни), не кусками по 30: смена месяца
        // и года не даёт сдвига. 2026-09-28 + 7 = 2026-10-05 (+10 к today 25.09);
        // 2026-12-29 + 7 = 2027-01-05 (+102 к today 25.09).
        assertEquals(10, daysUntilWatering("2026-09-28", 7, today))
        assertEquals(WateringStatus.Healthy, wateringStatus("2026-09-28", 7, today))
        assertEquals(102, daysUntilWatering("2026-12-29", 7, today))
    }
}
