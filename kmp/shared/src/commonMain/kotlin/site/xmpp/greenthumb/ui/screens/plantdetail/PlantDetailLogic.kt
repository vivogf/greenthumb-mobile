package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto

/**
 * Чистая логика экрана деталей растения (Stage 7 п.6, фича screen-plant-detail)
 * — порт хелперов `app/plant/[id].tsx:37-67` и `saveSettings` ( RN
 * `app/plant/[id].tsx:262-331`). Без Compose: проверяется напрямую в jvmTest.
 */

/**
 * Состояние экрана от наблюдения репозитория: RN `isLoading` (первый кадр
 * списка не пришёл) → [Loading]; растение из кэша → [Found]; его нет
 * (удалено, чужой id, ошибка наблюдения) → [NotFound] (RN error-ветка,
 * VAL-DETAIL-006 — экран «не найдено», не краш).
 */
public sealed interface PlantState {
    public data object Loading : PlantState

    public data object NotFound : PlantState

    public data class Found(public val plant: PlantDto) : PlantState
}

/**
 * Дней до следующего ухода для дневной частоты (удобрение). Отрицательное —
 * просрочено; null — дата не ставилась (уход не настроен).
 * Порт `daysUntilCare` (addDays + differenceInCalendarDays).
 */
public fun daysUntilCare(lastDate: String?, frequencyDays: Int, today: LocalDate): Int? {
    val last = parseDate(lastDate) ?: return null
    val next = last.plus(frequencyDays, DateTimeUnit.DAY)
    return (next.toEpochDays() - today.toEpochDays()).toInt()
}

/**
 * Дней до следующего ухода для МЕСЯЧНОЙ частоты (пересадка/обрезка).
 * Календарная арифметика (kotlinx-datetime клампит день месяца: 31 янв + 1 мес
 * → 28/29 фев), НЕ «×30 дней» — баг 2026-03-09 не возвращать (parity-файл
 * «Даты и формы»; VAL-DETAIL-002). Порт `daysUntilMonthCare` (addMonths).
 */
public fun daysUntilMonthCare(lastDate: String?, frequencyMonths: Int, today: LocalDate): Int? {
    val last = parseDate(lastDate) ?: return null
    val next = last.plus(frequencyMonths, DateTimeUnit.MONTH)
    return (next.toEpochDays() - today.toEpochDays()).toInt()
}

private fun parseDate(raw: String?): LocalDate? =
    raw?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { LocalDate.parse(it.substringBefore('T')) }.getOrNull() }

/** Срочность ухода для цвета подписи карточки (порт careStatusColor). */
public enum class CareUrgency {
    /** Уход не настроен — mutedForeground. */
    NotSet,

    /** Просрочено — destructive. */
    Overdue,

    /** Сегодня — amber. */
    Today,

    /** Ещё не пора — primary. */
    Later,
}

/** Цвет-роль по срочности решает экран (темы); логика отдаёт категорию. */
public fun careUrgency(days: Int?): CareUrgency = when {
    days == null -> CareUrgency.NotSet
    days < 0 -> CareUrgency.Overdue
    days == 0 -> CareUrgency.Today
    else -> CareUrgency.Later
}

/**
 * Подпись «через N дней / просрочено / сегодня / не настроено» — порт
 * careDaysText. Ресурсы решает экран (plurals), логика — факт.
 */
public sealed interface CareDaysText {
    public data object NotSet : CareDaysText

    /** Просрочено; [days] — |сколько прошло|. */
    public data class Overdue(public val days: Int) : CareDaysText

    public data object Today : CareDaysText

    public data class DaysLeft(public val days: Int) : CareDaysText
}

public fun careDaysText(days: Int?): CareDaysText = when {
    days == null -> CareDaysText.NotSet
    days < 0 -> CareDaysText.Overdue(-days)
    days == 0 -> CareDaysText.Today
    else -> CareDaysText.DaysLeft(days)
}

/**
 * «Полили сегодня / вчера / N дн. назад» — порт lastWateredLabel
 * (isDateToday → today, daysAgo == 1 → yesterday, иначе daysAgo).
 */
public sealed interface WateredAgoLabel {
    public data object Today : WateredAgoLabel

    public data object Yesterday : WateredAgoLabel

    public data class DaysAgo(public val days: Int) : WateredAgoLabel
}

public fun wateredAgoLabel(lastWateredDate: String, today: LocalDate): WateredAgoLabel {
    val last = parseDate(lastWateredDate)
    val diff = last?.let { (today.toEpochDays() - it.toEpochDays()).toInt() } ?: 0
    return when {
        diff <= 0 -> WateredAgoLabel.Today
        diff == 1 -> WateredAgoLabel.Yesterday
        else -> WateredAgoLabel.DaysAgo(diff)
    }
}

/**
 * Состояние формы модалки настроек ухода — порт `settingsForm` (RN
 * `app/plant/[id].tsx:66-76`) и `openSettings` (строки в поля ввода,
 * null-частота → ''). Даты — `YYYY-MM-DD` или null.
 */
public data class CareSettingsForm(
    public val waterFrequencyText: String,
    public val lastWateredDate: String?,
    public val fertilizeFrequencyText: String,
    public val lastFertilizedDate: String?,
    public val repotFrequencyText: String,
    public val lastRepottedDate: String?,
    public val pruneFrequencyText: String,
    public val lastPrunedDate: String?,
) {
    public companion object {
        /** Порт RN openSettings (`app/plant/[id].tsx:232-247`). */
        public fun of(plant: PlantDto): CareSettingsForm = CareSettingsForm(
            waterFrequencyText = plant.waterFrequencyDays.toString(),
            lastWateredDate = plant.lastWateredDate,
            fertilizeFrequencyText = plant.fertilizeFrequencyDays?.toString() ?: "",
            lastFertilizedDate = plant.lastFertilizedDate,
            repotFrequencyText = plant.repotFrequencyMonths?.toString() ?: "",
            lastRepottedDate = plant.lastRepottedDate,
            pruneFrequencyText = plant.pruneFrequencyMonths?.toString() ?: "",
            lastPrunedDate = plant.lastPrunedDate,
        )
    }
}

/**
 * Тело PATCH из модалки настроек — 1:1 порт RN saveSettings
 * (`app/plant/[id].tsx:262-331`):
 * - частота полива уходит ВСЕГДА (Value), fallback — текущее значение
 *   растения (`parse(...) ?? plant.water_frequency_days`);
 * - дата уходит только если задана (if (settingsForm.last_watered_date));
 * - очищенная частота удобрения/пересадки/обрезки → ЯВНЫЙ null
 *   ([Patch.Null]), только если у растения она была (else — поле не трогаем);
 * - имя/локация/фото/заметки модалкой не правятся (Absent).
 *
 * VAL-DETAIL-003: сброс частоты = серверная колонка null → уход отключён.
 */
public object CareSettingsPatch {
    /** RN parse: не-число или <1 → undefined. */
    private fun parseFrequency(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it >= 1 }

    private fun parseDate(date: String?): String? = date?.trim()?.takeIf { it.isNotEmpty() }

    public fun build(plant: PlantDto, form: CareSettingsForm): PatchPlantDto {
        var patch = PatchPlantDto(
            waterFrequencyDays = Patch.Value(
                parseFrequency(form.waterFrequencyText) ?: plant.waterFrequencyDays,
            ),
        )
        parseDate(form.lastWateredDate)?.let {
            patch = patch.copy(lastWateredDate = Patch.Value(it))
        }

        val fertilize = parseFrequency(form.fertilizeFrequencyText)
        patch = when {
            fertilize != null -> patch.copy(fertilizeFrequencyDays = Patch.Value(fertilize))
            plant.fertilizeFrequencyDays != null -> patch.copy(fertilizeFrequencyDays = Patch.Null)
            else -> patch
        }
        parseDate(form.lastFertilizedDate)?.let {
            patch = patch.copy(lastFertilizedDate = Patch.Value(it))
        }

        val repot = parseFrequency(form.repotFrequencyText)
        patch = when {
            repot != null -> patch.copy(repotFrequencyMonths = Patch.Value(repot))
            plant.repotFrequencyMonths != null -> patch.copy(repotFrequencyMonths = Patch.Null)
            else -> patch
        }
        parseDate(form.lastRepottedDate)?.let {
            patch = patch.copy(lastRepottedDate = Patch.Value(it))
        }

        val prune = parseFrequency(form.pruneFrequencyText)
        patch = when {
            prune != null -> patch.copy(pruneFrequencyMonths = Patch.Value(prune))
            plant.pruneFrequencyMonths != null -> patch.copy(pruneFrequencyMonths = Patch.Null)
            else -> patch
        }
        parseDate(form.lastPrunedDate)?.let {
            patch = patch.copy(lastPrunedDate = Patch.Value(it))
        }
        return patch
    }
}
