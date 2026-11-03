package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import site.xmpp.greenthumb.ui.components.normalizePickerDate
import site.xmpp.greenthumb.ui.components.uiLanguage

/** Date column of a journal row: «12 окт.» / "Oct 12", and [year] only when it is not the current year. */
internal data class JournalDate(val dayMonth: String, val year: String?)

// Abbreviated month names in the genitive (CLDR ru "MMM" in a "d MMM" pattern) and CLDR en "MMM".
private val ruMonths = listOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")
private val enMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun dayMonth(day: Int, month: Int, languageTag: String): String =
    if (uiLanguage(languageTag) == "ru") "$day ${ruMonths[month - 1]}" else "${enMonths[month - 1]} $day"

internal fun journalDate(isoDate: String, languageTag: String, today: LocalDate): JournalDate {
    val normalized = normalizePickerDate(isoDate) ?: return JournalDate(isoDate, null)
    val date = LocalDate.parse(normalized)
    val year = date.year.takeIf { it != today.year }?.toString()
    return JournalDate(dayMonth(date.day, date.month.number, languageTag), year)
}

/** Every day of every month: the widest one sets the shared date column width. */
internal fun journalDateWidthSamples(languageTag: String): List<String> =
    (1..12).flatMap { month -> (1..31).map { day -> dayMonth(day, month, languageTag) } }
