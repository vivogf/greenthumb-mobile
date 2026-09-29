package site.xmpp.greenthumb.ui.screens.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.network.PlantDto

/**
 * M10-фикс F1 (scrutiny): во время снапа массового полива held-карточки
 * обязаны держать исходные позиции рендера, а stagger распада
 * (`index * Motion.ThanosStaggerMs` — развёртка слева направо) — идти в
 * исходном порядке. Прежний код дописывал snapHeldIds в хвост filteredPlants
 * (`filteredPlants + snapExtras`): карточки визуально прыгали вниз, распад шёл
 * не в том порядке. Тест воспроизводит прежний tail-append и проверяет
 * order-preserving сборку на трёх поверхностях: list, grid и фильтр
 * needs-water при переходе растений в healthy.
 */
class SnapRenderOrderTest {

    private val today = LocalDate.parse("2026-09-27")
    private val wateredToday = "2026-09-27"

    private fun plant(
        id: String,
        lastWatered: String,
        frequency: Int = 7,
    ): PlantDto = PlantDto(
        id = id,
        userId = "1",
        name = id.replaceFirstChar { it.uppercase() },
        location = "Living Room",
        photoUrl = "",
        waterFrequencyDays = frequency,
        lastWateredDate = lastWatered,
        createdAt = "2026-09-01T10:00:00.000Z",
    )

    // 2026-09-27, freq 7: fern -3, basil -1, cactus 0 (не-healthy → held),
    // ficus +2, monstera +4 (healthy). Порядок в Room намеренно НЕ совпадает
    // с порядком рендера (сортировка по срочности).
    private val fern = plant("fern", "2026-09-17")
    private val basil = plant("basil", "2026-09-19")
    private val cactus = plant("cactus", "2026-09-20")
    private val ficus = plant("ficus", "2026-09-22")
    private val monstera = plant("monstera", "2026-09-24")

    private val roomBefore = listOf(monstera, cactus, ficus, fern, basil)

    // Массовый полив: все три не-healthy политы сегодня (переход в healthy).
    private val heldIds = setOf("fern", "basil", "cactus")
    private val roomAfter = roomBefore.map { plant ->
        if (plant.id in heldIds) plant.copy(lastWateredDate = wateredToday) else plant
    }

    /** Прежняя сборка экрана до фикса: snapHeldIds дописывались в хвост. */
    private fun legacyTailAppend(
        filteredPlants: List<PlantDto>,
        allPlants: List<PlantDto>,
        snapHeldIds: Set<String>,
    ): List<PlantDto> {
        val snapExtras = allPlants.filter {
            it.id in snapHeldIds && filteredPlants.none { p -> p.id == it.id }
        }
        return if (snapExtras.isEmpty()) filteredPlants else filteredPlants + snapExtras
    }

    // ------------------------------------------------------------------
    // list: held-карточки держат исходные позиции (tail-append воспроизведён)
    // ------------------------------------------------------------------

    @Test
    fun list_heldCardsKeepOriginalPositions_reproducesLegacyTailAppend() {
        val preSnap = filterAndSortPlants(roomBefore, "", DashboardFilter.All, today)
        val filteredAfter = filterAndSortPlants(roomAfter, "", DashboardFilter.All, today)
        assertEquals(
            listOf("fern", "basil", "cactus", "ficus", "monstera"),
            preSnap.map { it.id },
        )

        // Прежний код: политые карточки уходят в хвост пост-мутационного
        // filteredPlants — «прыжок вниз» воспроизведен здесь.
        val legacy = legacyTailAppend(filteredAfter, roomAfter, heldIds)
        assertEquals(listOf("ficus", "monstera", "cactus", "fern", "basil"), legacy.map { it.id })
        assertEquals(heldIds, legacy.takeLast(3).map { it.id }.toSet())

        val render = renderPlantsDuringSnap(
            filteredPlants = filteredAfter,
            allPlants = roomAfter,
            snapHeldIds = heldIds,
            snapOrderIds = preSnap.map { it.id },
        )
        assertEquals(preSnap.map { it.id }, render.map { it.id })
        assertNotEquals(legacy.map { it.id }, render.map { it.id })
    }

    // ------------------------------------------------------------------
    // grid: индекс для stagger = исходная позиция (index * ThanosStaggerMs)
    // ------------------------------------------------------------------

    @Test
    fun grid_staggerIndicesFollowOriginalOrder() {
        // Расширенный набор: ещё и частота 21 (healthy c 2026-09-17 → +11),
        // чтобы пост-мутационная сортировка перемешала середину списка.
        val late = plant("late", "2026-09-17", frequency = 21)
        val roomBefore6 = roomBefore + late
        val roomAfter6 = roomAfter + late
        val preSnap = filterAndSortPlants(roomBefore6, "", DashboardFilter.All, today)
        val filteredAfter = filterAndSortPlants(roomAfter6, "", DashboardFilter.All, today)
        assertEquals(
            listOf("fern", "basil", "cactus", "ficus", "monstera", "late"),
            preSnap.map { it.id },
        )

        val render = renderPlantsDuringSnap(
            filteredPlants = filteredAfter,
            allPlants = roomAfter6,
            snapHeldIds = heldIds,
            snapOrderIds = preSnap.map { it.id },
        )

        // Развёртка слева направо (RN delay={index * 70}): индекс каждой
        // карточки в сетке равен её индексу в исходном порядке рендера.
        for (plant in preSnap) {
            assertEquals(
                preSnap.indexOfFirst { it.id == plant.id },
                render.indexOfFirst { it.id == plant.id },
                "grid-позиция ${plant.id} должна совпадать с исходной",
            )
        }
        // Прежний код держал held на индексах 2..4 — stagger шёл не в
        // исходном порядке (0,1,2).
        val legacy = legacyTailAppend(filteredAfter, roomAfter6, heldIds)
        assertEquals(listOf(2, 3, 4), legacy.filter { it.id in heldIds }.map { legacy.indexOfFirst { p -> p.id == it.id } })
    }

    // ------------------------------------------------------------------
    // needs-water: фильтр пуст после перехода в healthy — призраки в
    // исходном порядке срочности, не в порядке Room
    // ------------------------------------------------------------------

    @Test
    fun needsWaterFilter_ghostsKeepOriginalUrgencyOrder() {
        val preSnap = filterAndSortPlants(roomBefore, "", DashboardFilter.NeedsWater, today)
        assertEquals(listOf("fern", "basil", "cactus"), preSnap.map { it.id })

        // Все политы → фильтр needs-water не отдаёт ничего; призраки
        // собираются из Room-порядка.
        val filteredAfter = filterAndSortPlants(roomAfter, "", DashboardFilter.NeedsWater, today)
        assertEquals(emptyList(), filteredAfter)

        val legacy = legacyTailAppend(filteredAfter, roomAfter, heldIds)
        assertEquals(listOf("cactus", "fern", "basil"), legacy.map { it.id })

        val render = renderPlantsDuringSnap(
            filteredPlants = filteredAfter,
            allPlants = roomAfter,
            snapHeldIds = heldIds,
            snapOrderIds = preSnap.map { it.id },
        )
        assertEquals(listOf("fern", "basil", "cactus"), render.map { it.id })
    }

    // ------------------------------------------------------------------
    // Неуспех мутации снимает held — ghost-карточек не остаётся
    // ------------------------------------------------------------------

    @Test
    fun mutationFailure_clearsHeld_noGhostCardsLeft() {
        val filteredAfter = filterAndSortPlants(roomAfter, "", DashboardFilter.NeedsWater, today)
        val render = renderPlantsDuringSnap(
            filteredPlants = filteredAfter,
            allPlants = roomAfter,
            snapHeldIds = emptySet(),
            snapOrderIds = emptyList(),
        )
        assertEquals(filteredAfter, render)
    }
}
