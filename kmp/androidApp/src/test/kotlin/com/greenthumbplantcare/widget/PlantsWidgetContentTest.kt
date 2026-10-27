package com.greenthumbplantcare.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.Action
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import kotlin.test.Test
import kotlin.test.assertEquals
import site.xmpp.greenthumb.data.WidgetRow
import site.xmpp.greenthumb.data.WidgetUi

/** What the widget draws for each state (architecture §5.8); rows and order come from buildWidgetUi. */
class PlantsWidgetContentTest {

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

    @Test
    fun dueRows_showNamesStatusesAndMoreLine() = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(DpSize(250.dp, 200.dp))
        val ui = WidgetUi.Due(
            rows = listOf(WidgetRow("a", "Monstera", -3), WidgetRow("b", "Fern", 0)),
            moreCount = 2,
        )
        provideComposable { PlantsWidgetContent(ui, texts, { noAction }, noAction) }
        onNode(hasTextEqualTo("Water today")).assertExists()
        onNode(hasTextEqualTo("Monstera")).assertExists()
        onNode(hasTextEqualTo("3 d overdue")).assertExists()
        onNode(hasTextEqualTo("Fern")).assertExists()
        onNode(hasTextEqualTo("Today")).assertExists()
        onNode(hasTextEqualTo("+2 more")).assertExists()
    }

    @Test
    fun noMoreLine_whenNothingIsHidden() = runGlanceAppWidgetUnitTest {
        provideComposable {
            PlantsWidgetContent(WidgetUi.Due(listOf(WidgetRow("a", "Fern", 0)), 0), texts, { noAction }, noAction)
        }
        onNode(hasText("more")).assertDoesNotExist()
    }

    @Test
    fun allWatered() = runGlanceAppWidgetUnitTest {
        provideComposable { PlantsWidgetContent(WidgetUi.AllWatered, texts, { noAction }, noAction) }
        onNode(hasTextEqualTo("All plants are watered")).assertExists()
    }

    @Test
    fun noPlants() = runGlanceAppWidgetUnitTest {
        provideComposable { PlantsWidgetContent(WidgetUi.NoPlants, texts, { noAction }, noAction) }
        onNode(hasTextEqualTo("Add your first plant")).assertExists()
    }

    @Test
    fun signedOut() = runGlanceAppWidgetUnitTest {
        provideComposable { PlantsWidgetContent(WidgetUi.SignedOut, texts, { noAction }, noAction) }
        onNode(hasTextEqualTo("Open the app")).assertExists()
        onNode(hasTextEqualTo("Monstera")).assertDoesNotExist()
    }

    @Test
    fun everyPlantGetsItsOwnLaunchUri() {
        val ids = listOf("1", "2", "plant-9", "a/b", "a%2Fb")
        assertEquals(ids.size, ids.map(::widgetPlantUri).toSet().size)
        assertEquals("greenthumb-widget://plant/plant-9", widgetPlantUri("plant-9"))
    }
}
