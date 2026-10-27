package com.greenthumbplantcare.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.data.WidgetAccount
import site.xmpp.greenthumb.data.WidgetPlant
import site.xmpp.greenthumb.data.WidgetSnapshot
import site.xmpp.greenthumb.data.buildWidgetUi
import site.xmpp.greenthumb.season.SeasonContext
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.season.TzLatitudes

/**
 * VAL-WIDGET-012: fake rows and a fixed clock go through the widget's real pipeline — [buildWidgetUi]
 * picks and orders the rows, [PlantsWidgetContent] draws them — and the drawn texts are asserted.
 */
class PlantsWidgetMapperTest {

    private val texts = WidgetTexts(
        title = "Water today",
        allWatered = "All plants are watered",
        noPlants = "Add your first plant",
        signedOut = "Open the app",
        dueToday = "Today",
        overdue = { days -> "$days d overdue" },
        more = { count -> "+$count more" },
    )
    private val noAction = object : Action {}
    private val zoned: (SeasonMode) -> SeasonContext = { mode -> SeasonContext(mode, "Europe/Moscow", TzLatitudes.Empty) }

    private fun plant(id: String, name: String, lastWatered: String, frequency: Int = 7) =
        WidgetPlant(id = id, name = name, lastWateredDate = lastWatered, waterFrequencyDays = frequency)

    private fun snapshot(plants: List<WidgetPlant>, mode: SeasonMode = SeasonMode.Off) =
        WidgetSnapshot(language = null, account = WidgetAccount("24", mode, plants))

    private fun GlanceAppWidgetUnitTest.render(snapshot: WidgetSnapshot, today: String) {
        setAppWidgetSize(DpSize(250.dp, 320.dp))
        val ui = buildWidgetUi(snapshot, LocalDate.parse(today), zoned)
        provideComposable { PlantsWidgetContent(ui, texts, { noAction }, noAction) }
    }

    private val sevenDue = listOf(
        plant("o9", "Yucca", "2026-09-09"),
        plant("o5", "Monstera", "2026-09-13"),
        plant("o3a", "Aloe", "2026-09-15"),
        plant("o3b", "begonia", "2026-09-15"),
        plant("o1", "Pothos", "2026-09-17"),
        plant("t1", "Fern", "2026-09-18"),
        plant("t2", "Zamioculcas", "2026-09-18"),
        plant("healthy", "Cactus", "2026-09-24"),
    )

    @Test
    fun moreThanFiveDue_drawsFiveRowsMostOverdueFirst_andAMoreLine() = runGlanceAppWidgetUnitTest {
        render(snapshot(sevenDue), "2026-09-25")
        listOf("Yucca", "Monstera", "Aloe", "begonia", "Pothos").forEach { onNode(hasTextEqualTo(it)).assertExists() }
        onNode(hasTextEqualTo("9 d overdue")).assertExists()
        onNode(hasTextEqualTo("1 d overdue")).assertExists()
        onNode(hasTextEqualTo("Fern")).assertDoesNotExist()
        onNode(hasTextEqualTo("Zamioculcas")).assertDoesNotExist()
        onNode(hasTextEqualTo("Cactus")).assertDoesNotExist()
        onNode(hasTextEqualTo("Today")).assertDoesNotExist()
        onNode(hasTextEqualTo("+2 more")).assertExists()
    }

    private val fern = listOf(plant("p", "Fern", "2026-09-19"))

    @Test
    fun dayD_plantNotDue_drawsAllWatered() = runGlanceAppWidgetUnitTest {
        render(snapshot(fern), "2026-09-25")
        onNode(hasTextEqualTo("All plants are watered")).assertExists()
        onNode(hasTextEqualTo("Fern")).assertDoesNotExist()
    }

    @Test
    fun dayDPlusOne_samePlant_drawsItDueToday() = runGlanceAppWidgetUnitTest {
        render(snapshot(fern), "2026-09-26")
        onNode(hasTextEqualTo("Fern")).assertExists()
        onNode(hasTextEqualTo("Today")).assertExists()
        onNode(hasText("All plants")).assertDoesNotExist()
    }

    // North, January: 10 days x 1.5 = 15, so the next watering is 2026-01-25, not 2026-01-20.
    private val ficus = listOf(plant("p", "Ficus", "2026-01-10", frequency = 10))

    @Test
    fun dormantMonth_beforeSeasonalDueDate_drawsAllWatered() = runGlanceAppWidgetUnitTest {
        render(snapshot(ficus, SeasonMode.North), "2026-01-21")
        onNode(hasTextEqualTo("All plants are watered")).assertExists()
        onNode(hasTextEqualTo("Ficus")).assertDoesNotExist()
    }

    @Test
    fun dormantMonth_withSeasonOff_drawsThePlainIntervalOverdue() = runGlanceAppWidgetUnitTest {
        render(snapshot(ficus, SeasonMode.Off), "2026-01-21")
        onNode(hasTextEqualTo("Ficus")).assertExists()
        onNode(hasTextEqualTo("1 d overdue")).assertExists()
    }

    @Test
    fun dormantMonth_onSeasonalDueDate_drawsItDueToday() = runGlanceAppWidgetUnitTest {
        render(snapshot(ficus, SeasonMode.North), "2026-01-25")
        onNode(hasTextEqualTo("Ficus")).assertExists()
        onNode(hasTextEqualTo("Today")).assertExists()
    }
}
