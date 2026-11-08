package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.isHeldForReplay
import site.xmpp.greenthumb.season.SeasonContext

/**
 * Чистая логика экрана деталей растения (Stage 7 п.6, фича screen-plant-detail)
 * — порт хелперов `app/plant/[id].tsx:37-67` и `saveSettings` ( RN
 * `app/plant/[id].tsx:262-331`). Без Compose: проверяется напрямую в jvmTest.
 */

/**
 * Ветка отказа удаления (VAL-DETAIL-004, решение пользователя 2026-09-27):
 *
 * - [Queued] — Network/Timeout: репозиторий НЕ откатывает (журнал M4 хранит
 *   удаление и досылает при восстановлении сети), UI обязан честно сказать
 *   «удаление выполнится после подключения», а не «ошибка»;
 * - [Rejected] — определённый 4xx/5xx: репозиторий уже откатил снимок и снял
 *   журнал, UI показывает сообщение об ошибке (RN onError-паритет).
 *
 * Отделение веток обязано совпадать с `keepForReplay` репозитория — иначе
 * экран солжёт о судьбе очереди. Проверяется jvmTest-классификацией.
 */
public enum class DeleteFailureBranch {
    /** Сеть/таймаут: удаление в очереди, досылается после подключения. */
    Queued,

    /** Определённый отказ сервера: откат снимка, журнал пуст. */
    Rejected,
}

/** Какую ветку UI показывать для [error] из `repo.delete` (VAL-DETAIL-004). */
public fun deleteFailureBranch(error: Throwable): DeleteFailureBranch =
    if (isHeldForReplay(error)) DeleteFailureBranch.Queued else DeleteFailureBranch.Rejected

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

/**
 * Countdowns shown on plant detail. Only watering takes the season rule; the other kinds of care keep
 * their plain interval in every mode. A null countdown means that care is not set up.
 */
public data class PlantCareCountdowns(
    public val water: Int,
    public val fertilize: Int?,
    public val repot: Int?,
    public val prune: Int?,
)

public fun plantCareCountdowns(plant: PlantDto, today: LocalDate, season: SeasonContext): PlantCareCountdowns =
    PlantCareCountdowns(
        water = daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today, season),
        fertilize = plant.fertilizeFrequencyDays?.let { daysUntilCare(plant.lastFertilizedDate, it, today) },
        repot = plant.repotFrequencyMonths?.let { daysUntilMonthCare(plant.lastRepottedDate, it, today) },
        prune = plant.pruneFrequencyMonths?.let { daysUntilMonthCare(plant.lastPrunedDate, it, today) },
    )

public enum class AdvancedCare { Fertilize, Repot, Prune }

/** One fertilize/repot/prune card; [days] is null when the frequency is set but the last date is missing. */
public data class AdvancedCareCard(public val care: AdvancedCare, public val days: Int?)

/** A card for every kind of care that has a frequency, in the fixed order fertilize, repot, prune. */
public fun advancedCareCards(plant: PlantDto, countdowns: PlantCareCountdowns): List<AdvancedCareCard> = buildList {
    if (plant.fertilizeFrequencyDays != null) add(AdvancedCareCard(AdvancedCare.Fertilize, countdowns.fertilize))
    if (plant.repotFrequencyMonths != null) add(AdvancedCareCard(AdvancedCare.Repot, countdowns.repot))
    if (plant.pruneFrequencyMonths != null) add(AdvancedCareCard(AdvancedCare.Prune, countdowns.prune))
}

/** Срочность ухода для цвета подписи карточки (порт careStatusColor). */
public enum class CareUrgency {
    /** Частота есть, даты последнего раза нет — mutedForeground. */
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
 * Подпись «через N дней / просрочено / сегодня / укажите дату» — порт
 * careDaysText. Ресурсы решает экран (plurals), логика — факт.
 */
public sealed interface CareDaysText {
    /** The frequency is set but the last date is missing: the card asks for the date. */
    public data object NeedsDate : CareDaysText

    /** Просрочено; [days] — |сколько прошло|. */
    public data class Overdue(public val days: Int) : CareDaysText

    public data object Today : CareDaysText

    public data class DaysLeft(public val days: Int) : CareDaysText
}

public fun careDaysText(days: Int?): CareDaysText = when {
    days == null -> CareDaysText.NeedsDate
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

/** What the detail screen can say about a plant's species. */
public sealed interface PlantSpeciesInfo {
    public data object None : PlantSpeciesInfo

    /** Known catalog entry: name, light level and tip. */
    public data class Catalog(public val entry: SpeciesEntry) : PlantSpeciesInfo

    /** Only a saved scientific name (not in the catalog, or catalog not loaded yet). */
    public data class NameOnly(public val scientificName: String) : PlantSpeciesInfo
}

public fun plantSpeciesInfo(plant: PlantDto, catalog: CatalogRepository?): PlantSpeciesInfo {
    catalog?.byId(plant.speciesId)?.let { return PlantSpeciesInfo.Catalog(it) }
    val name = plant.speciesName?.takeIf { it.isNotBlank() }
    return if (name != null) PlantSpeciesInfo.NameOnly(name) else PlantSpeciesInfo.None
}

/**
 * The four care types of the care settings sheet (M10 D§1 item 7a) with the stepper range of each.
 *
 * The ranges follow the server validator `GreenThumbPWA/server/plants/plantInput.ts`: water 1..365,
 * fertilize `optionalInt(3650)`, repot and prune `optionalInt(120)`. `optionalInt` also accepts 0, but
 * the server schedule and the web client treat 0 as "no reminder" (`!!days`, `|| null`); here that is the
 * switch (null), so a switched-on stepper starts at 1. [default] is the first value of a switch turned on
 * for a type that never had one.
 */
public enum class CareType(public val range: IntRange, public val default: Int, public val inMonths: Boolean) {
    Water(1..365, default = 7, inMonths = false),
    Fertilize(1..3650, default = 30, inMonths = false),
    Repot(1..120, default = 12, inMonths = true),
    Prune(1..120, default = 6, inMonths = true),
    ;

    /** The server maximum; one more is a 400 (VAL-CARD-009). */
    public val serverMax: Int get() = range.last

    /** Watering has no switch: every plant has a watering interval. */
    public val switchable: Boolean get() = this != Water
}

/** On/off and the number of one care type; [value] is kept while the type is off. */
public data class CareSetting(public val on: Boolean, public val value: Int)

/**
 * Care settings sheet state: one [CareSetting] per [CareType], the last-done dates (`YYYY-MM-DD` or null)
 * and the species. Values are always inside [CareType.range]; there is no free text to parse.
 */
public data class CareSettingsForm(
    public val water: Int,
    public val fertilize: CareSetting,
    public val repot: CareSetting,
    public val prune: CareSetting,
    public val lastWateredDate: String?,
    public val lastFertilizedDate: String?,
    public val lastRepottedDate: String?,
    public val lastPrunedDate: String?,
    public val speciesId: String? = null,
    public val speciesName: String? = null,
) {
    public fun setting(type: CareType): CareSetting = when (type) {
        CareType.Water -> CareSetting(on = true, value = water)
        CareType.Fertilize -> fertilize
        CareType.Repot -> repot
        CareType.Prune -> prune
    }

    public fun isOn(type: CareType): Boolean = setting(type).on

    public fun value(type: CareType): Int = setting(type).value

    /** The value the server should hold: null when the type is off. */
    public fun effective(type: CareType): Int? = setting(type).let { if (it.on) it.value else null }

    private fun with(type: CareType, setting: CareSetting): CareSettingsForm = when (type) {
        CareType.Water -> copy(water = setting.value)
        CareType.Fertilize -> copy(fertilize = setting)
        CareType.Repot -> copy(repot = setting)
        CareType.Prune -> copy(prune = setting)
    }

    /** One − or + press, clamped to [CareType.range]. */
    public fun step(type: CareType, delta: Int): CareSettingsForm {
        val current = setting(type)
        return with(type, current.copy(value = (current.value + delta).coerceIn(type.range)))
    }

    public fun setOn(type: CareType, on: Boolean): CareSettingsForm =
        if (!type.switchable) this else with(type, setting(type).copy(on = on))

    public fun withDate(type: CareType, date: String): CareSettingsForm = when (type) {
        CareType.Water -> copy(lastWateredDate = date)
        CareType.Fertilize -> copy(lastFertilizedDate = date)
        CareType.Repot -> copy(lastRepottedDate = date)
        CareType.Prune -> copy(lastPrunedDate = date)
    }

    public fun date(type: CareType): String? = when (type) {
        CareType.Water -> lastWateredDate
        CareType.Fertilize -> lastFertilizedDate
        CareType.Repot -> lastRepottedDate
        CareType.Prune -> lastPrunedDate
    }

    /** Picks another catalog species; intervals are left to the user. */
    public fun withSpecies(species: SpeciesEntry): CareSettingsForm =
        copy(speciesId = species.id, speciesName = species.scientificName)

    public fun withoutSpecies(): CareSettingsForm = copy(speciesId = null, speciesName = null)

    public companion object {
        private fun settingOf(type: CareType, stored: Int?): CareSetting =
            if (stored == null || stored <= 0) {
                CareSetting(on = false, value = type.default)
            } else {
                CareSetting(on = true, value = stored.coerceIn(type.range))
            }

        public fun of(plant: PlantDto): CareSettingsForm = CareSettingsForm(
            water = plant.waterFrequencyDays.coerceIn(CareType.Water.range),
            fertilize = settingOf(CareType.Fertilize, plant.fertilizeFrequencyDays),
            repot = settingOf(CareType.Repot, plant.repotFrequencyMonths),
            prune = settingOf(CareType.Prune, plant.pruneFrequencyMonths),
            lastWateredDate = plant.lastWateredDate,
            lastFertilizedDate = plant.lastFertilizedDate,
            lastRepottedDate = plant.lastRepottedDate,
            lastPrunedDate = plant.lastPrunedDate,
            speciesId = plant.speciesId,
            speciesName = plant.speciesName,
        )
    }
}

/**
 * PATCH body of the care settings sheet: only what differs from the plant. A type switched off is an
 * explicit null (VAL-DETAIL-003); a stored 0 and "off" are the same thing, so that is no change. Name,
 * place, photo and notes are never touched. An unchanged form gives an empty patch, and nothing is sent.
 */
public object CareSettingsPatch {
    private fun stored(value: Int?): Int? = value?.takeIf { it > 0 }

    private fun frequency(old: Int?, new: Int?): Patch<Int> = when {
        stored(old) == new -> Patch.Absent
        new == null -> Patch.Null
        else -> Patch.Value(new)
    }

    private fun date(old: String?, new: String?): Patch<String> =
        new?.trim()?.takeIf { it.isNotEmpty() && it != old }?.let { Patch.Value(it) } ?: Patch.Absent

    public fun build(plant: PlantDto, form: CareSettingsForm): PatchPlantDto {
        var patch = PatchPlantDto(
            waterFrequencyDays = frequency(plant.waterFrequencyDays, form.water),
            lastWateredDate = date(plant.lastWateredDate, form.lastWateredDate),
            fertilizeFrequencyDays = frequency(plant.fertilizeFrequencyDays, form.effective(CareType.Fertilize)),
            lastFertilizedDate = date(plant.lastFertilizedDate, form.lastFertilizedDate),
            repotFrequencyMonths = frequency(plant.repotFrequencyMonths, form.effective(CareType.Repot)),
            lastRepottedDate = date(plant.lastRepottedDate, form.lastRepottedDate),
            pruneFrequencyMonths = frequency(plant.pruneFrequencyMonths, form.effective(CareType.Prune)),
            lastPrunedDate = date(plant.lastPrunedDate, form.lastPrunedDate),
        )
        // Both species fields travel together so the server never holds an
        // id from one species and a name from another.
        if (form.speciesId != plant.speciesId || form.speciesName != plant.speciesName) {
            patch = patch.copy(
                speciesId = form.speciesId?.let { Patch.Value(it) } ?: Patch.Null,
                speciesName = form.speciesName?.let { Patch.Value(it) } ?: Patch.Null,
            )
        }
        return patch
    }
}

/** True when the patch would send no field at all. */
public fun PatchPlantDto.isEmpty(): Boolean = this == PatchPlantDto()

/** Why a care settings save failed; the sheet stays open with the edited values and shows the reason. */
public enum class CareSaveError {
    /** 4xx: the server rejected the values. */
    Rejected,

    /** 5xx or an unexpected failure. */
    Server,

    /** No connection and the change could not be queued. */
    Offline,
}

public fun careSaveError(error: Throwable): CareSaveError = when {
    isHeldForReplay(error) -> CareSaveError.Offline
    error is ApiError.Client -> CareSaveError.Rejected
    else -> CareSaveError.Server
}

/**
 * The dark header gradient keeps white header text readable over a photo. In the light theme a plant
 * without a photo shows only the light placeholder: a scrim there would turn it grey and darken the screen.
 */
internal fun headerNeedsScrim(photoUrl: String, darkTheme: Boolean): Boolean = darkTheme || photoUrl.isNotBlank()
