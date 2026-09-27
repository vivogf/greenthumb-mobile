package site.xmpp.greenthumb.ui.screens.dashboard

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import site.xmpp.greenthumb.core.network.PlantDto

/**
 * Stage 7 п.7 (VAL-DASH-002/003/004): порт useMemo `filteredPlants` и
 * `needsWaterCount` из `app/(tabs)/index.tsx` — поиск по имени и локации,
 * фильтры all/needsWater/healthy, счётчик needsWater, сортировка по дням до
 * полива по возрастанию (просроченные первыми).
 */
class DashboardLogicTest {

    private val today = LocalDate.parse("2026-09-27")

    private fun plant(
        id: String,
        name: String,
        location: String = "Living Room",
        lastWatered: String,
        frequency: Int = 7,
    ): PlantDto = PlantDto(
        id = id,
        userId = "1",
        name = name,
        location = location,
        photoUrl = "",
        waterFrequencyDays = frequency,
        lastWateredDate = lastWatered,
        createdAt = "2026-09-01T10:00:00.000Z",
    )

    // 2026-09-27: просрочен (-3), сегодня (0), здоровый (+4), здоровый (+11).
    private val overdue = plant("overdue", "Fern", location = "Bathroom", lastWatered = "2026-09-17")
    private val dueToday = plant("today", "Basil", lastWatered = "2026-09-20")
    private val healthySoon = plant("soon", "Monstera", lastWatered = "2026-09-24")
    private val healthyLate = plant("late", "Ficus", location = "Office", lastWatered = "2026-09-17", frequency = 21)

    private val all = listOf(healthyLate, overdue, healthySoon, dueToday)

    // ------------------------------------------------------------------
    // VAL-DASH-004: сортировка по days-until-watering по возрастанию
    // ------------------------------------------------------------------

    @Test
    fun sort_putsOverdueFirst_thenByDaysAscending() {
        val ids = filterAndSortPlants(all, "", DashboardFilter.All, today).map { it.id }
        // -3 → 0 → 4 → 11; входной порядок перемешан намеренно.
        assertEquals(listOf("overdue", "today", "soon", "late"), ids)
    }

    @Test
    fun sort_keepsStableOrderForEqualDays() {
        val a = plant("a", "A", lastWatered = "2026-09-24")
        val b = plant("b", "B", lastWatered = "2026-09-24")
        val ids = filterAndSortPlants(listOf(b, a), "", DashboardFilter.All, today).map { it.id }
        assertEquals(listOf("b", "a"), ids)
    }

    // ------------------------------------------------------------------
    // VAL-DASH-002: поиск по имени И локации, без учёта регистра
    // ------------------------------------------------------------------

    @Test
    fun search_matchesByNameIgnoringCase() {
        val ids = filterAndSortPlants(all, "ficus", DashboardFilter.All, today).map { it.id }
        assertEquals(listOf("late"), ids)
    }

    @Test
    fun search_matchesByLocation() {
        val ids = filterAndSortPlants(all, "bathroom", DashboardFilter.All, today).map { it.id }
        assertEquals(listOf("overdue"), ids)
    }

    @Test
    fun search_trimsWhitespace_andMissesAbsentSubstring() {
        assertEquals(1, filterAndSortPlants(all, "  basil  ", DashboardFilter.All, today).size)
        assertEquals(0, filterAndSortPlants(all, "cactus", DashboardFilter.All, today).size)
    }

    // ------------------------------------------------------------------
    // VAL-DASH-003: фильтры и счётчик needsWater
    // ------------------------------------------------------------------

    @Test
    fun filterNeedsWater_keepsOverdueAndToday() {
        val ids = filterAndSortPlants(all, "", DashboardFilter.NeedsWater, today).map { it.id }
        assertEquals(listOf("overdue", "today"), ids)
    }

    @Test
    fun filterHealthy_keepsOnlyHealthy() {
        val ids = filterAndSortPlants(all, "", DashboardFilter.Healthy, today).map { it.id }
        assertEquals(listOf("soon", "late"), ids)
    }

    @Test
    fun filterAll_keepsEverything() {
        assertEquals(4, filterAndSortPlants(all, "", DashboardFilter.All, today).size)
    }

    @Test
    fun needsWaterCount_countsNonHealthy() {
        assertEquals(2, needsWaterCount(all, today))
        assertEquals(0, needsWaterCount(listOf(healthySoon), today))
    }

    // ------------------------------------------------------------------
    // Комбинации: поиск + фильтр (RN применяет оба сразу)
    // ------------------------------------------------------------------

    @Test
    fun searchAndFilter_combine() {
        // Оба не-healthy, но поиск «fern» оставляет только просроченный.
        val ids = filterAndSortPlants(all, "fern", DashboardFilter.NeedsWater, today).map { it.id }
        assertEquals(listOf("overdue"), ids)
        // Поиск по локации + фильтр healthy — пусто (Office-растение healthy,
        // но не матчится локацией bathroom).
        assertEquals(0, filterAndSortPlants(all, "bathroom", DashboardFilter.Healthy, today).size)
    }
}
