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
}
