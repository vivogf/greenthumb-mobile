package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-FIX11-001/-003: the dashboard filter chips read «Все · Полить (N) · В порядке», every label is
 * one line at 384 dp and font scale 1.3 in ru and en, and a row wider than the screen scrolls.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardFilterRowTest {
    // The jvm LocalAppLocale sets the process default locale.
    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private fun ComposeUiTest.showRow(
        locale: String,
        dueCount: Int,
        widthDp: Int = 384,
        fontScale: Float = 1.3f,
        onSelect: (DashboardFilter) -> Unit = {},
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    GreenThumbTheme(darkTheme = true) {
                        var filter by mutableStateOf(DashboardFilter.All)
                        Box(modifier = Modifier.width(widthDp.dp)) {
                            DashboardFilterRow(
                                filter = filter,
                                dueCount = dueCount,
                                onSelect = { filter = it; onSelect(it) },
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.layoutOf(label: String): TextLayoutResult {
        val node = onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun ComposeUiTest.assertOneLineLabels(labels: List<String>) {
        labels.forEach { label ->
            val layout = layoutOf(label)
            assertEquals(1, layout.lineCount, "«$label» is one line")
            assertTrue(!layout.isLineEllipsized(0), "«$label» is shown in full")
        }
    }

    private fun ComposeUiTest.allTexts(): List<String> =
        onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test
    fun ru_labels_are_short_and_one_line_at_384dp_font_1_3() = runDesktopComposeUiTest {
        showRow(locale = "ru", dueCount = 12)
        assertOneLineLabels(listOf("Все", "Полить (12)", "В порядке"))
        assertTrue(allTexts().none { it.startsWith("Нужен полив") }, "old label is gone: ${allTexts()}")
    }

    @Test
    fun en_labels_are_short_and_one_line_at_384dp_font_1_3() = runDesktopComposeUiTest {
        showRow(locale = "en", dueCount = 12)
        assertOneLineLabels(listOf("All", "Water (12)", "Healthy"))
        assertTrue(allTexts().none { it.startsWith("Needs Water") }, "old label is gone: ${allTexts()}")
    }

    @Test
    fun ru_labels_are_one_line_at_font_1_15_without_a_count() = runDesktopComposeUiTest {
        showRow(locale = "ru", dueCount = 0, fontScale = 1.15f)
        assertOneLineLabels(listOf("Все", "Полить", "В порядке"))
    }

    @Test
    fun a_row_wider_than_the_screen_scrolls_to_its_last_chip() = runDesktopComposeUiTest {
        showRow(locale = "ru", dueCount = 12, widthDp = 200)
        assertOneLineLabels(listOf("Все", "Полить (12)", "В порядке"))
        val row = onNode(hasScrollAction())
        val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the row overflows and can scroll")
        row.performScrollToNode(hasText("В порядке"))
        waitForIdle()
        val viewport = row.fetchSemanticsNode().boundsInRoot
        val last = onNode(hasClickAction() and hasText("В порядке")).fetchSemanticsNode().boundsInRoot
        assertTrue(
            last.right <= viewport.right + 0.5f && last.left >= viewport.left - 0.5f,
            "last chip fully visible: $last in $viewport",
        )
    }

    @Test
    fun tapping_the_water_chip_selects_the_needs_water_filter() = runDesktopComposeUiTest {
        var selected: DashboardFilter? = null
        showRow(locale = "en", dueCount = 3, onSelect = { selected = it })
        onNode(hasClickAction() and hasText("Water (3)")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(DashboardFilter.NeedsWater, selected)
    }
}
