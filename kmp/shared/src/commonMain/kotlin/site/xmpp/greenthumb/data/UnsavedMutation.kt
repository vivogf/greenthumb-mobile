package site.xmpp.greenthumb.data

import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.storage.PendingMutationEntity

/**
 * Метка «не сохранено» для экранов (Stage 4 п.4 и п.6, VAL-OFF-002).
 *
 * Строка журнала `pending_mutations` значит: локальная правка ещё не
 * подтверждена сервером. Экраны M7 не хардкодят текст — берут [LABEL]
 * и множество id из [plantIds] (или [PlantRepository.observeUnsavedPlantIds]).
 *
 * В множество входят `plant_id` строки и, для `water_all`, id из payload:
 * у массового полива `plant_id` пустой. Битый payload не роняет список —
 * помечается только то, что разобралось.
 */
public object UnsavedMutation {
    public const val LABEL: String = "не сохранено"

    public fun plantIds(pending: List<PendingMutationEntity>): Set<String> {
        val ids = linkedSetOf<String>()
        for (entry in pending) {
            val plantId = entry.plantId
            if (!plantId.isNullOrBlank()) {
                ids.add(plantId)
            }
            if (entry.type == MutationType.WATER_ALL) {
                val decoded = runCatching {
                    ApiClient.json.decodeFromString<WaterAllPayload>(entry.payload)
                }.getOrNull()
                if (decoded != null) {
                    for (id in decoded.plantIds) {
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
            }
        }
        return ids
    }
}

/** То же, что [UnsavedMutation.plantIds]: удобный вызов из репозитория и тестов. */
public fun unsavedPlantIds(pending: List<PendingMutationEntity>): Set<String> =
    UnsavedMutation.plantIds(pending)
