package site.xmpp.greenthumb.data

import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.season.SeasonContext

/**
 * Статус полива — порт `lib/utils.ts` `getWateringStatus`.
 *
 * `YYYY-MM-DD` разбирается как локальная дата (не UTC-полночь):
 * `TimeZone.UTC` сдвинул бы статус на сутки. Отрицательное число дней —
 * просрочено, ноль — сегодня, положительное — ещё не пора (`healthy`).
 *
 * `waterAll` оптимистично трогает только статус ≠ [WateringStatus.Healthy]
 * (architecture.md §7; RN `getWateringStatus(...) !== 'healthy'`).
 *
 * Интервал — сезонный ([SeasonContext], architecture §2.9): в спящий месяц
 * последнего полива он ×1,5 с округлением вверх. [SeasonContext.Off] —
 * прежний интервал без поправки.
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
    season: SeasonContext = SeasonContext.Off,
): Int {
    val next = season.nextWaterDueDate(lastWateredDate, frequencyDays)
    return (next.toEpochDays() - today.toEpochDays()).toInt()
}

public fun wateringStatus(
    lastWateredDate: String,
    frequencyDays: Int,
    today: LocalDate,
    season: SeasonContext = SeasonContext.Off,
): WateringStatus {
    val days = daysUntilWatering(lastWateredDate, frequencyDays, today, season)
    return when {
        days < 0 -> WateringStatus.Overdue
        days == 0 -> WateringStatus.Today
        else -> WateringStatus.Healthy
    }
}
