package site.xmpp.greenthumb.data

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * Статус полива — порт `lib/utils.ts` `getWateringStatus`.
 *
 * `YYYY-MM-DD` разбирается как локальная дата (не UTC-полночь):
 * `TimeZone.UTC` сдвинул бы статус на сутки. Отрицательное число дней —
 * просрочено, ноль — сегодня, положительное — ещё не пора (`healthy`).
 *
 * `waterAll` оптимистично трогает только статус ≠ [WateringStatus.Healthy]
 * (architecture.md §7; RN `getWateringStatus(...) !== 'healthy'`).
 */
public enum class WateringStatus {
    Overdue,
    Today,
    Healthy,
}

/**
 * Дней до следующего полива. Отрицательное — просрочено.
 * [today] — локальный «сегодня» вызывающего (инжектируется в тестах).
 */
public fun daysUntilWatering(
    lastWateredDate: String,
    frequencyDays: Int,
    today: LocalDate,
): Int {
    val last = LocalDate.parse(lastWateredDate)
    val next = last.plus(frequencyDays, DateTimeUnit.DAY)
    return (next.toEpochDays() - today.toEpochDays()).toInt()
}

public fun wateringStatus(
    lastWateredDate: String,
    frequencyDays: Int,
    today: LocalDate,
): WateringStatus {
    val days = daysUntilWatering(lastWateredDate, frequencyDays, today)
    return when {
        days < 0 -> WateringStatus.Overdue
        days == 0 -> WateringStatus.Today
        else -> WateringStatus.Healthy
    }
}
