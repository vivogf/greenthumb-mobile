package site.xmpp.greenthumb.ui.components

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Подпись и граница [GtDatePickerField].
 *
 * Формат — по языку UI (`en` / `ru`), не по локали JVM и не хардкодом
 * `ru-RU` из `components/DatePickerInput.tsx`. Язык без региона: `en` →
 * `MM/dd/yyyy` (как `toLocaleDateString('en-US', {day,month:2-digit, year:numeric})`),
 * `ru` → `dd.MM.yyyy`. Пустой тег — en, чтобы отсутствие языка не вернуло баг.
 *
 * Миллисекунды пикера — UTC-полночь календарного дня (контракт Material3),
 * не локальный момент. Сравнивать их через локальную зону нельзя: в UTC−8
 * «сегодня» стало бы вчера.
 */
fun uiLanguage(languageTag: String): String =
    languageTag.trim().lowercase().substringBefore('-').substringBefore('_').ifEmpty { "en" }

/** `YYYY-MM-DD` или null. Время после `T` отбрасывается, как `normalizeDate` в RN. */
fun normalizePickerDate(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { LocalDate.parse(raw.substringBefore('T')).toString() }.getOrNull()
}

fun formatPickerDate(isoDate: String, languageTag: String): String {
    val normalized = normalizePickerDate(isoDate) ?: return isoDate
    val date = LocalDate.parse(normalized)
    val day = date.day.toString().padStart(2, '0')
    val month = date.month.number.toString().padStart(2, '0')
    val year = date.year.toString()
    return if (uiLanguage(languageTag) == "ru") "$day.$month.$year" else "$month/$day/$year"
}

fun pickerToday(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    clock: Clock = Clock.System,
): LocalDate = clock.now().toLocalDateTime(zone).date

/** UTC-полночь календарного дня — то, что ждёт Material3 DatePicker. */
fun pickerMillis(date: LocalDate): Long =
    date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

fun dateFromPickerMillis(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date

fun isSelectablePickerDate(utcMillis: Long, today: LocalDate): Boolean =
    dateFromPickerMillis(utcMillis) <= today

fun isSelectablePickerYear(year: Int, today: LocalDate): Boolean = year <= today.year
