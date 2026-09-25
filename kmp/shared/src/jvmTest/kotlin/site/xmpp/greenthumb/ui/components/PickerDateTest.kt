package site.xmpp.greenthumb.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * VAL-DS-005: подпись даты по языку UI, не хардкод `ru-RU` из RN.
 * Верхняя граница пикера — сегодня (календарный день, UTC-полночь Material3).
 */
class PickerDateTest {

    @Test
    fun en_format_is_not_ru_ru_hardcode() {
        val iso = "2026-09-25"
        val ruHardcode = "25.09.2026"
        assertEquals(ruHardcode, formatPickerDate(iso, "ru"))
        assertEquals("09/25/2026", formatPickerDate(iso, "en"))
        assertNotEqualsRu(iso)
    }

    @Test
    fun en_and_ru_swap_day_and_month() {
        val iso = "2026-01-09"
        assertEquals("01/09/2026", formatPickerDate(iso, "en"))
        assertEquals("09.01.2026", formatPickerDate(iso, "ru"))
        assertNotEqualsRu(iso)
    }

    @Test
    fun language_tag_ignores_region_and_case() {
        assertEquals("09/25/2026", formatPickerDate("2026-09-25", "en-US"))
        assertEquals("09/25/2026", formatPickerDate("2026-09-25", "EN"))
        assertEquals("25.09.2026", formatPickerDate("2026-09-25", "ru-RU"))
        assertEquals("25.09.2026", formatPickerDate("2026-09-25", "ru_RU"))
        assertEquals("09/25/2026", formatPickerDate("2026-09-25", ""))
    }

    @Test
    fun iso_timestamp_uses_date_part_without_zone_shift() {
        assertEquals("09/25/2026", formatPickerDate("2026-09-25T23:30:00Z", "en"))
        assertEquals("2026-09-25", normalizePickerDate("2026-09-25T12:00:00"))
        assertNull(normalizePickerDate(null))
        assertNull(normalizePickerDate("   "))
        assertNull(normalizePickerDate("not-a-date"))
        assertEquals("not-a-date", formatPickerDate("not-a-date", "en"))
    }

    @Test
    fun future_calendar_day_is_not_selectable() {
        val today = LocalDate(2026, 9, 25)
        assertTrue(isSelectablePickerDate(pickerMillis(today), today))
        assertTrue(isSelectablePickerDate(pickerMillis(LocalDate(2026, 9, 24)), today))
        assertFalse(isSelectablePickerDate(pickerMillis(LocalDate(2026, 9, 26)), today))
        assertEquals(today, dateFromPickerMillis(pickerMillis(today)))
    }

    @Test
    fun future_year_is_not_selectable() {
        val today = LocalDate(2026, 9, 25)
        assertTrue(isSelectablePickerYear(2026, today))
        assertTrue(isSelectablePickerYear(2025, today))
        assertFalse(isSelectablePickerYear(2027, today))
    }

    private fun assertNotEqualsRu(iso: String) {
        val ruHardcode = formatPickerDate(iso, "ru")
        kotlin.test.assertNotEquals(
            ruHardcode,
            formatPickerDate(iso, "en"),
            "en must not use the RN ru-RU hardcode",
        )
    }
}
