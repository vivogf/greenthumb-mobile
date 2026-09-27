package site.xmpp.greenthumb.ui.screens.dashboard

import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.wateringStatus

/** Фильт-табы дашборда (RN `type Filter = 'all' | 'needsWater' | 'healthy'`). */
public enum class DashboardFilter {
    All,
    NeedsWater,
    Healthy,
}

/**
 * Порт `filteredPlants` из `app/(tabs)/index.tsx` (useMemo): поиск по имени И
 * локации (подстрока, без учёта регистра), затем фильтр по статусу, затем
 * сортировка по дням до полива по возрастанию — просроченные первыми, при
 * равенстве исходный порядок сохраняется (стабильная сортировка, как `[...result].sort`).
 *
 * [today] — локальный «сегодня» вызывающего (инжектируется в тестах).
 */
public fun filterAndSortPlants(
    plants: List<PlantDto>,
    searchQuery: String,
    filter: DashboardFilter,
    today: LocalDate,
): List<PlantDto> {
    var result = plants
    val query = searchQuery.trim().lowercase()
    if (query.isNotEmpty()) {
        result = result.filter { plant ->
            plant.name.lowercase().contains(query) ||
                plant.location.lowercase().contains(query)
        }
    }
    when (filter) {
        DashboardFilter.NeedsWater -> result = result.filter { plant ->
            wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today) !=
                WateringStatus.Healthy
        }
        DashboardFilter.Healthy -> result = result.filter { plant ->
            wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today) ==
                WateringStatus.Healthy
        }
        DashboardFilter.All -> {}
    }
    return result.sortedBy { plant ->
        daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today)
    }
}

/**
 * Счётчик нуждающихся в поливе (RN useMemo `needsWaterCount`): статус ≠ healthy.
 * Ведёт подпись чипа needsWater, видимость кнопок массовых действий и
 * pending-баннер массового полива.
 */
public fun needsWaterCount(plants: List<PlantDto>, today: LocalDate): Int =
    plants.count { plant ->
        wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today) !=
            WateringStatus.Healthy
    }
