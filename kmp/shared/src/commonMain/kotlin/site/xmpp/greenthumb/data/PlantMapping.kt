package site.xmpp.greenthumb.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.storage.PlantEntity

/**
 * Типы журнала `pending_mutations.type`. Досылка (следующая фича) читает
 * их как есть: не переименовывать без миграции уже записанных строк.
 *
 * Снимок — JSON [PlantDto] (или массив для [MutationType.WATER_ALL]).
 * У [MutationType.ADD] снимка нет: отката нет, строку просто удаляют.
 * Полезная нагрузка — то, что надо повторить на сервере, не «сегодня»
 * на момент досылки (дата полива уже зафиксирована в payload).
 */
public object MutationType {
    public const val WATER: String = "water"
    public const val WATER_ALL: String = "water_all"
    public const val ADD: String = "add"
    public const val UPDATE: String = "update"
    public const val DELETE: String = "delete"
}

/** Тело досылки `water`: PATCH той же даты, что уже записана локально. */
@Serializable
public data class WaterPayload(
    @SerialName("last_watered_date") val lastWateredDate: String,
)

/**
 * Тело досылки `water_all`. Повтор — `POST /api/plants/water-all`
 * (сервер сам выбирает должные), затем refresh. [plantIds] нужны откату
 * и пометке «не сохранено», не второму локальному проставлению даты.
 */
@Serializable
public data class WaterAllPayload(
    @SerialName("plant_ids") val plantIds: List<String>,
    @SerialName("last_watered_date") val lastWateredDate: String,
)

public fun PlantDto.toEntity(): PlantEntity = PlantEntity(
    id = id,
    userId = userId,
    name = name,
    location = location,
    photoUrl = photoUrl,
    waterFrequencyDays = waterFrequencyDays,
    lastWateredDate = lastWateredDate,
    fertilizeFrequencyDays = fertilizeFrequencyDays,
    lastFertilizedDate = lastFertilizedDate,
    repotFrequencyMonths = repotFrequencyMonths,
    lastRepottedDate = lastRepottedDate,
    pruneFrequencyMonths = pruneFrequencyMonths,
    lastPrunedDate = lastPrunedDate,
    notes = notes,
    createdAt = createdAt,
)

public fun PlantEntity.toDto(): PlantDto = PlantDto(
    id = id,
    userId = userId,
    name = name,
    location = location,
    photoUrl = photoUrl,
    waterFrequencyDays = waterFrequencyDays,
    lastWateredDate = lastWateredDate,
    fertilizeFrequencyDays = fertilizeFrequencyDays,
    lastFertilizedDate = lastFertilizedDate,
    repotFrequencyMonths = repotFrequencyMonths,
    lastRepottedDate = lastRepottedDate,
    pruneFrequencyMonths = pruneFrequencyMonths,
    lastPrunedDate = lastPrunedDate,
    notes = notes ?: "",
    createdAt = createdAt,
)

public fun InsertPlantDto.toOptimisticEntity(
    id: String,
    userId: String,
    createdAt: String,
): PlantEntity = PlantEntity(
    id = id,
    userId = userId,
    name = name,
    location = location,
    photoUrl = photoUrl,
    waterFrequencyDays = waterFrequencyDays,
    lastWateredDate = lastWateredDate,
    fertilizeFrequencyDays = fertilizeFrequencyDays,
    lastFertilizedDate = lastFertilizedDate,
    repotFrequencyMonths = repotFrequencyMonths,
    lastRepottedDate = lastRepottedDate,
    pruneFrequencyMonths = pruneFrequencyMonths,
    lastPrunedDate = lastPrunedDate,
    notes = notes,
    createdAt = createdAt,
)

/**
 * Локальное применение PATCH. Обязательные колонки не принимают [Patch.Null]
 * (в Room они NOT NULL) — такое поле остаётся как было. Nullable-уход
 * сбрасывается в null.
 */
public fun PlantEntity.applying(patch: PatchPlantDto): PlantEntity = copy(
    name = patch.name.keepRequired(name),
    location = patch.location.keepRequired(location),
    photoUrl = patch.photoUrl.keepRequired(photoUrl),
    waterFrequencyDays = patch.waterFrequencyDays.keepRequired(waterFrequencyDays),
    lastWateredDate = patch.lastWateredDate.keepRequired(lastWateredDate),
    fertilizeFrequencyDays = patch.fertilizeFrequencyDays.keepNullable(fertilizeFrequencyDays),
    lastFertilizedDate = patch.lastFertilizedDate.keepNullable(lastFertilizedDate),
    repotFrequencyMonths = patch.repotFrequencyMonths.keepNullable(repotFrequencyMonths),
    lastRepottedDate = patch.lastRepottedDate.keepNullable(lastRepottedDate),
    pruneFrequencyMonths = patch.pruneFrequencyMonths.keepNullable(pruneFrequencyMonths),
    lastPrunedDate = patch.lastPrunedDate.keepNullable(lastPrunedDate),
    notes = patch.notes.keepNullable(notes),
)

private fun <T> Patch<T>.keepRequired(current: T): T = when (this) {
    is Patch.Value -> value
    else -> current
}

private fun <T> Patch<T>.keepNullable(current: T?): T? = when (this) {
    is Patch.Value -> value
    is Patch.Null -> null
    is Patch.Absent -> current
}
