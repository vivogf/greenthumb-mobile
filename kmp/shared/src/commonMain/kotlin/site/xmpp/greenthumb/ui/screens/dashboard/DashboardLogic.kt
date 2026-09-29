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
 * Порядок рендера списка/сетки во время снапа массового полива (M10-фикс F1
 * после scrutiny: ghost-карточки дописывались в хвост).
 *
 * M4-мутация пишет данные немедленно, поэтому пост-мутационный [filteredPlants]
 * либо сортирует политые карточки в хвост (daysUntil = frequency), либо не
 * отдаёт их вовсе (фильтр needs-water после перехода в healthy). Прежняя сборка
 * `filteredPlants + snapExtras` дописывала карточки-призраки в хвост: карточки
 * визуально прыгали вниз, а stagger распада (`index * Motion.ThanosStaggerMs`,
 * развёртка слева направо) шёл не в исходном порядке. RN удерживал их на
 * прежних местах (запись кэша шла после эффекта).
 *
 * Один order-preserving проход по [snapOrderIds] — порядку рендера на момент
 * клика: каждая видимая карточка занимает исходную позицию. Совпадения,
 * которых не было в исходном порядке (поиск/фильтр сменился во время снапа),
 * дописываются хвостом в порядке [visible]. Видимый набор не меняется:
 * фильтр + карточки-призраки из [snapHeldIds] (эффект владеет их видимостью).
 * Без снапа (held снят, в том числе после неуспеха мутации) — [filteredPlants]
 * без изменений: ghost-карточек не остаётся.
 */
public fun renderPlantsDuringSnap(
    filteredPlants: List<PlantDto>,
    allPlants: List<PlantDto>,
    snapHeldIds: Set<String>,
    snapOrderIds: List<String>,
): List<PlantDto> {
    if (snapHeldIds.isEmpty()) return filteredPlants
    val snapExtras = allPlants.filter {
        it.id in snapHeldIds && filteredPlants.none { p -> p.id == it.id }
    }
    val visible = if (snapExtras.isEmpty()) filteredPlants else filteredPlants + snapExtras
    if (snapOrderIds.isEmpty()) return visible
    val visibleById = HashMap<String, PlantDto>(visible.size)
    for (plant in visible) visibleById[plant.id] = plant
    val rendered = ArrayList<PlantDto>(visible.size)
    val emitted = HashSet<String>(visible.size)
    for (id in snapOrderIds) {
        val plant = visibleById[id] ?: continue
        if (emitted.add(id)) rendered.add(plant)
    }
    for (plant in visible) {
        if (emitted.add(plant.id)) rendered.add(plant)
    }
    return rendered
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
