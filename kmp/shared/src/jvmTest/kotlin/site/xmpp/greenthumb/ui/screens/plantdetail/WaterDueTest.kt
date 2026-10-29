package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.semantics.SemanticsProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.data.WaterDue
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.waterDue
import site.xmpp.greenthumb.season.SeasonContext
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.season.TzLatitudes
import site.xmpp.greenthumb.ui.components.GtWaterDueLine
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/** VAL-CARD-001: the variant B watering line «Полить через N дней» and its progress. */
@OptIn(ExperimentalTestApi::class)
class WaterDueTest {

    private val today = LocalDate(2026, 10, 29)

    /** Last watering [daysAgo] days before [today]. */
    private fun ago(daysAgo: Int): String = LocalDate.fromEpochDays(today.toEpochDays() - daysAgo).toString()

    @Test
    fun inElevenDays() {
        val state = waterDue(ago(3), 14, today, SeasonContext.Off)
        assertEquals(WaterDue.InDays(11), state.due)
        assertEquals(3f / 14f, state.progress)
        assertEquals(WateringStatus.Healthy, state.status)
    }

    @Test
    fun inOneDay() {
        assertEquals(WaterDue.InDays(1), waterDue(ago(6), 7, today, SeasonContext.Off).due)
    }

    @Test
    fun dueToday_progressFull() {
        val state = waterDue(ago(7), 7, today, SeasonContext.Off)
        assertEquals(WaterDue.Today, state.due)
        assertEquals(1f, state.progress)
        assertEquals(WateringStatus.Today, state.status)
    }

    @Test
    fun overdueThree_andTwentyOne() {
        val three = waterDue(ago(10), 7, today, SeasonContext.Off)
        assertEquals(WaterDue.Overdue(3), three.due)
        assertEquals(1f, three.progress)
        assertEquals(WateringStatus.Overdue, three.status)
        assertEquals(WaterDue.Overdue(21), waterDue(ago(28), 7, today, SeasonContext.Off).due)
    }

    @Test
    fun frequencyZero_doesNotCrash() {
        assertEquals(WaterDue.Today, waterDue(today.toString(), 0, today, SeasonContext.Off).due)
        val overdue = waterDue(ago(2), 0, today, SeasonContext.Off)
        assertEquals(WaterDue.Overdue(2), overdue.due)
        assertEquals(1f, overdue.progress)
        assertEquals(1f, waterDue(today.toString(), -3, today, SeasonContext.Off).progress)
    }

    @Test
    fun wateredInTheFuture_clampsProgressToZero() {
        val state = waterDue(LocalDate(2026, 11, 2).toString(), 7, today, SeasonContext.Off)
        assertEquals(WaterDue.InDays(11), state.due)
        assertEquals(0f, state.progress)
    }

    @Test
    fun winterRule_stretchesTheIntervalLikeTheDashboard() {
        val north = SeasonContext(SeasonMode.North, null, TzLatitudes.Empty)
        // Watered on 1 December, base 10 days → winter interval 15 days → due 16 December.
        val winterToday = LocalDate(2026, 12, 5)
        val state = waterDue("2026-12-01", 10, winterToday, north)
        assertEquals(WaterDue.InDays(11), state.due)
        assertEquals(4f / 15f, state.progress)
        assertEquals(WaterDue.InDays(6), waterDue("2026-12-01", 10, winterToday, SeasonContext.Off).due)
    }

    @Test
    fun dueNumberEqualsTheDashboardNumber() {
        val north = SeasonContext(SeasonMode.North, null, TzLatitudes.Empty)
        val cases = listOf(
            Triple("2026-12-01", 10, north),
            Triple(ago(3), 14, SeasonContext.Off),
            Triple(ago(28), 7, SeasonContext.Off),
            Triple(ago(7), 7, north),
        )
        for ((last, freq, season) in cases) {
            val dashboard = daysUntilWatering(last, freq, today, season)
            val shown = when (val due = waterDue(last, freq, today, season).due) {
                is WaterDue.InDays -> due.days
                WaterDue.Today -> 0
                is WaterDue.Overdue -> -due.days
            }
            assertEquals(dashboard, shown, "$last/$freq")
        }
    }

    private fun renderedTexts(locale: String, cases: List<WaterDue>): List<String> {
        val texts = mutableListOf<String>()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = true) {
                        CasesColumn(cases)
                    }
                }
            }
            cases.indices.forEach { i ->
                waitUntilAtLeastOneExists(hasTestTag("waterDue_$i"), TIMEOUT)
                val node = onNode(hasTestTag("waterDue_$i"), useUnmergedTree = true).fetchSemanticsNode()
                texts += node.config[SemanticsProperties.Text].joinToString("") { it.text }
            }
        }
        return texts
    }

    @Composable
    private fun CasesColumn(cases: List<WaterDue>) {
        androidx.compose.foundation.layout.Column {
            cases.forEachIndexed { i, due ->
                GtWaterDueLine(due = due, progress = 0.5f, lastWateredText = "", textTag = "waterDue_$i")
            }
        }
    }

    private val renderCases = listOf(
        WaterDue.InDays(11),
        WaterDue.InDays(1),
        WaterDue.InDays(2),
        WaterDue.InDays(5),
        WaterDue.InDays(21),
        WaterDue.Today,
        WaterDue.Overdue(1),
        WaterDue.Overdue(3),
        WaterDue.Overdue(5),
        WaterDue.Overdue(21),
    )

    @Test
    fun rendersRussianForms() {
        assertEquals(
            listOf(
                "Полить через 11 дней",
                "Полить через 1 день",
                "Полить через 2 дня",
                "Полить через 5 дней",
                "Полить через 21 день",
                "Полить сегодня",
                "Пора полить: 1 день назад",
                "Пора полить: 3 дня назад",
                "Пора полить: 5 дней назад",
                "Пора полить: 21 день назад",
            ),
            renderedTexts("ru", renderCases),
        )
    }

    @Test
    fun rendersEnglishForms() {
        assertEquals(
            listOf(
                "Water in 11 days",
                "Water in 1 day",
                "Water in 2 days",
                "Water in 5 days",
                "Water in 21 days",
                "Water today",
                "Overdue by 1 day",
                "Overdue by 3 days",
                "Overdue by 5 days",
                "Overdue by 21 days",
            ),
            renderedTexts("en", renderCases),
        )
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
