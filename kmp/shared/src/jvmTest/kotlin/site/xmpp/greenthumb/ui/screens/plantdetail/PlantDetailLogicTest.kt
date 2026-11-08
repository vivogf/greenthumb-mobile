package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import site.xmpp.greenthumb.core.network.ApiError

/**
 * Stage 7 п.6 (фича screen-plant-detail) — чистая логика экрана деталей.
 *
 * VAL-DETAIL-002: месячная математика календарная (addMonths-паритет,
 * не ×30) — jvmTest на фиксированных датах.
 * VAL-DETAIL-003 (сборка PATCH настроек ухода, явный null при выключении) —
 * в CareSettingsFormTest.
 */
class PlantDetailLogicTest {

    // ------------------------------------------------------------------
    // daysUntilMonthCare — календарные месяцы (VAL-DETAIL-002)
    // ------------------------------------------------------------------

    @Test
    fun monthCare_jan31_plus1_clampsToFeb28_not30days() {
        // addMonths: 2026-01-31 + 1 месяц → 2026-02-28 (календарный месяц
        // клампит день), не «2026-03-02» (31+30 дней).
        val today = LocalDate.parse("2026-02-28")
        assertEquals(
            0,
            daysUntilMonthCare("2026-01-31", 1, today),
            "31 янв + 1 мес = 28 фев (клампинг дня месяца, ×30 дал бы 3 мар)",
        )
    }

    @Test
    fun monthCare_monthBoundary_endOfFeb_plus1_goesToMar31() {
        // 2026-02-28 (не високосный) + 1 мес → 2026-03-28 — обычный сдвиг,
        // без клампинга: 28 дн., а не «×30».
        val today = LocalDate.parse("2026-03-28")
        assertEquals(0, daysUntilMonthCare("2026-02-28", 1, today))
        // А 12 мес вперёд от 31 янв → 31 янв следующего года.
        val next = LocalDate.parse("2027-01-31")
        assertEquals(
            0,
            daysUntilMonthCare("2026-01-31", 12, next),
            "12 месяцев сохраняют день, когда он существует",
        )
    }

    @Test
    fun monthCare_leapYear_feb29_plus1_goesToMar29() {
        // 2024 високосный: 2024-02-29 + 1 мес → 2024-03-29.
        val today = LocalDate.parse("2024-03-29")
        assertEquals(0, daysUntilMonthCare("2024-02-29", 1, today))
    }

    @Test
    fun monthCare_leapYear_jan31_plus2_goesToMar31_notMar2() {
        // 2024-01-31 + 2 мес: фев клампится к 29, мар вернёт 31.
        val today = LocalDate.parse("2024-03-31")
        assertEquals(0, daysUntilMonthCare("2024-01-31", 2, today))
    }

    @Test
    fun monthCare_negative_isOverdueDaysCount() {
        // Полили 2026-01-01, сегодня 2026-01-15, частота 1 мес → 2026-02-01
        // → +17 дней (просрочки ещё нет). А today 2026-02-05 → -4.
        val today = LocalDate.parse("2026-02-05")
        assertEquals(-4, daysUntilMonthCare("2026-01-01", 1, today))
    }

    @Test
    fun monthCare_zeroFrequency_isNoOp() {
        val today = LocalDate.parse("2026-06-10")
        assertEquals(0, daysUntilMonthCare("2026-06-10", 0, today))
    }

    @Test
    fun monthCare_noLastDate_isNull() {
        assertEquals(null, daysUntilMonthCare(null, 6, LocalDate.parse("2026-02-28")))
        assertEquals(null, daysUntilMonthCare("", 6, LocalDate.parse("2026-02-28")))
    }

    @Test
    fun dayCare_addDays_matchesRN() {
        // Дневная частота (удобрение): 2026-09-01 + 30 дней → 2026-10-01.
        val today = LocalDate.parse("2026-10-01")
        assertEquals(0, daysUntilCare("2026-09-01", 30, today))
        // 2026-02-20 + 7 дней → 2026-02-27.
        assertEquals(0, daysUntilCare("2026-02-20", 7, LocalDate.parse("2026-02-27")))
        // Просрочка.
        assertEquals(-2, daysUntilCare("2026-02-18", 7, LocalDate.parse("2026-02-27")))
    }

    @Test
    fun dayCare_noLastDate_isNull() {
        assertEquals(null, daysUntilCare(null, 14, LocalDate.parse("2026-02-27")))
    }

    // ------------------------------------------------------------------
    // careUrgency / careDaysText / wateredAgoLabel
    // ------------------------------------------------------------------

    @Test
    fun careUrgency_categories() {
        assertEquals(CareUrgency.NotSet, careUrgency(null))
        assertEquals(CareUrgency.Overdue, careUrgency(-3))
        assertEquals(CareUrgency.Today, careUrgency(0))
        assertEquals(CareUrgency.Later, careUrgency(5))
    }

    @Test
    fun careDaysText_mapsCategories() {
        assertEquals(CareDaysText.NeedsDate, careDaysText(null))
        assertIs<CareDaysText.Overdue>(careDaysText(-4)).let { assertEquals(4, it.days) }
        assertEquals(CareDaysText.Today, careDaysText(0))
        assertIs<CareDaysText.DaysLeft>(careDaysText(9)).let { assertEquals(9, it.days) }
    }

    @Test
    fun advancedCareCards_frequencyWithoutDate_keepsTheCard_withNullDays() {
        val plant = site.xmpp.greenthumb.core.network.PlantDto(
            id = "p", userId = "1", name = "Fern", location = "", photoUrl = "",
            waterFrequencyDays = 7, lastWateredDate = "2026-09-20", notes = "", createdAt = "2026-09-01T10:00:00.000Z",
            fertilizeFrequencyDays = 30, lastFertilizedDate = null,
            repotFrequencyMonths = 12, lastRepottedDate = "",
            pruneFrequencyMonths = null, lastPrunedDate = "2026-03-15",
        )
        val today = LocalDate.parse("2026-09-25")
        val cards = advancedCareCards(plant, plantCareCountdowns(plant, today, site.xmpp.greenthumb.season.SeasonContext.Off))
        assertEquals(
            listOf(AdvancedCareCard(AdvancedCare.Fertilize, null), AdvancedCareCard(AdvancedCare.Repot, null)),
            cards,
        )
        assertEquals(CareDaysText.NeedsDate, careDaysText(cards.first().days))
    }

    @Test
    fun advancedCareCards_withDates_countDown_andSkipCareWithoutFrequency() {
        val plant = site.xmpp.greenthumb.core.network.PlantDto(
            id = "p", userId = "1", name = "Fern", location = "", photoUrl = "",
            waterFrequencyDays = 7, lastWateredDate = "2026-09-20", notes = "", createdAt = "2026-09-01T10:00:00.000Z",
            fertilizeFrequencyDays = null, lastFertilizedDate = "2026-09-01",
            pruneFrequencyMonths = 1, lastPrunedDate = "2026-09-01",
        )
        val today = LocalDate.parse("2026-09-25")
        val cards = advancedCareCards(plant, plantCareCountdowns(plant, today, site.xmpp.greenthumb.season.SeasonContext.Off))
        assertEquals(listOf(AdvancedCareCard(AdvancedCare.Prune, 6)), cards)
    }

    @Test
    fun wateredAgoLabel_todayYesterdayDaysAgo() {
        val today = LocalDate.parse("2026-09-25")
        assertIs<WateredAgoLabel.Today>(wateredAgoLabel("2026-09-25", today))
        assertIs<WateredAgoLabel.Yesterday>(wateredAgoLabel("2026-09-24", today))
        assertIs<WateredAgoLabel.DaysAgo>(wateredAgoLabel("2026-09-01", today)).let {
            assertEquals(24, it.days)
        }
        // Будущая дата (кривые часы) — RN isToday false, daysAgo<0 → не yesterday,
        // но и не «дн. назад»; паритет решения — трактуем как сегодня.
        assertIs<WateredAgoLabel.Today>(wateredAgoLabel("2026-09-26", today))
    }

    // ------------------------------------------------------------------
    // deleteFailureBranch — две ветки отказа удаления (VAL-DETAIL-004)
    // ------------------------------------------------------------------

    @Test
    fun deleteFailureBranch_networkAndTimeout_areQueued() {
        // Network/Timeout: журнал M4 хранит удаление и досошлёт его после
        // подключения — UI обязан показывать честный статус, не «ошибку».
        assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(ApiError.Network))
        assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(ApiError.Timeout))
    }

    @Test
    fun deleteFailureBranch_definitiveRejection_isRejected() {
        // Определённый 4xx/5xx: репозиторий откатил снимок и снял журнал —
        // UI показывает сообщение об ошибке (RN onError-паритет).
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Server(500, "boom")))
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Client(404, "gone")))
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Unauthorized))
    }

    @Test
    fun deleteFailureBranch_matchesRepositoryReplayRule() {
        // Ветка UI обязана совпадать с keepForReplay репозитория: всё, что
        // остаётся в очереди, — Queued; всё, что откатывается, — Rejected.
        listOf(ApiError.Network, ApiError.Timeout).forEach { error ->
            assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(error), "$error")
        }
        listOf(
            ApiError.Server(500, "x"),
            ApiError.Client(400, "x"),
            ApiError.Unauthorized,
            IllegalStateException("boom"),
        ).forEach { error ->
            assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(error), "$error")
        }
    }

    // CareSettingsForm / CareSettingsPatch: CareSettingsFormTest (typed steppers, M10 item 7a).
}
