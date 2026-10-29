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

/**
 * The next watering as plant detail says it (variant B line «Полить через N дней»).
 * Built from [daysUntilWatering], so the number always equals the dashboard's.
 */
public sealed interface WaterDue {
    public data class InDays(public val days: Int) : WaterDue

    public data object Today : WaterDue

    /** [days] since the watering fell due. */
    public data class Overdue(public val days: Int) : WaterDue
}

/** [progress] is the share of the (seasonal) interval that has passed, 0..1. */
public data class WaterDueState(public val due: WaterDue, public val progress: Float) {
    public val status: WateringStatus
        get() = when (due) {
            is WaterDue.Overdue -> WateringStatus.Overdue
            WaterDue.Today -> WateringStatus.Today
            is WaterDue.InDays -> WateringStatus.Healthy
        }
}

public fun waterDue(
    lastWateredDate: String,
    frequencyDays: Int,
    today: LocalDate,
    season: SeasonContext = SeasonContext.Off,
): WaterDueState {
    val days = daysUntilWatering(lastWateredDate, frequencyDays, today, season)
    val interval = season.effectiveWaterIntervalDays(frequencyDays, lastWateredDate)
    val progress = if (interval <= 0 || days <= 0) 1f else ((interval - days).toFloat() / interval).coerceIn(0f, 1f)
    val due = when {
        days < 0 -> WaterDue.Overdue(-days)
        days == 0 -> WaterDue.Today
        else -> WaterDue.InDays(days)
    }
    return WaterDueState(due, progress)
}
