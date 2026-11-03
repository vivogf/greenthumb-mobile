package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** Short journal dates (M15): «12 окт.» / "Oct 12"; the year only for an earlier or later year. */
class JournalDateTest {
    private val today = LocalDate(2026, 11, 3)

    @Test
    fun current_year_shows_day_and_short_month() {
        assertEquals(JournalDate("12 окт.", null), journalDate("2026-10-12", "ru", today))
        assertEquals(JournalDate("Oct 12", null), journalDate("2026-10-12", "en", today))
        assertEquals(JournalDate("3 мая", null), journalDate("2026-05-03", "ru-RU", today))
        assertEquals(JournalDate("May 3", null), journalDate("2026-05-03", "en-US", today))
        assertEquals(JournalDate("1 сент.", null), journalDate("2026-09-01T23:30:00Z", "ru", today))
    }

    @Test
    fun other_year_adds_the_year() {
        assertEquals(JournalDate("28 дек.", "2025"), journalDate("2025-12-28", "ru", today))
        assertEquals(JournalDate("Dec 28", "2025"), journalDate("2025-12-28", "en", today))
    }

    @Test
    fun all_months_have_short_names_and_bad_input_passes_through() {
        val ru = (1..12).map { journalDate("2026-%02d-28".format(it), "ru", today).dayMonth }
        assertEquals(
            listOf("28 янв.", "28 февр.", "28 мар.", "28 апр.", "28 мая", "28 июн.", "28 июл.", "28 авг.", "28 сент.", "28 окт.", "28 нояб.", "28 дек."),
            ru,
        )
        val en = (1..12).map { journalDate("2026-%02d-28".format(it), "en", today).dayMonth }
        assertEquals(listOf("Jan 28", "Feb 28", "Mar 28", "Apr 28", "May 28", "Jun 28", "Jul 28", "Aug 28", "Sep 28", "Oct 28", "Nov 28", "Dec 28"), en)
        assertEquals(JournalDate("not-a-date", null), journalDate("not-a-date", "en", today))
        // The width sample covers every month, so one column width fits each row.
        assertTrue(journalDateWidthSamples("ru").containsAll(ru))
        assertTrue(journalDateWidthSamples("en").containsAll(en))
    }
}
