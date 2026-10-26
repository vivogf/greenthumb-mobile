package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VAL-IDENT-012/014: what the identification dialog shows in Russian. */
@OptIn(ExperimentalTestApi::class)
class SpeciesIdentifyDialogTest {

    private val monstera = SpeciesEntry(
        id = "monstera-deliciosa",
        scientificName = "Monstera deliciosa",
        synonyms = emptyList(),
        names = SpeciesText(ru = "Монстера деликатесная", en = "Swiss cheese plant"),
        aliases = SpeciesAliases(),
        waterIntervalDays = 8,
        fertilizeIntervalDays = 21,
        repotIntervalMonths = 24,
        light = SpeciesLight.BrightIndirect,
        tip = SpeciesText(ru = "Совет", en = "Tip"),
    )

    @Composable
    private fun Ru(content: @Composable () -> Unit) {
        AppEnvironment(customAppLocale = "ru") { GreenThumbTheme(darkTheme = false) { content() } }
    }

    @Test
    fun results_show_words_catalog_mark_and_attribution_without_percent() = runDesktopComposeUiTest {
        var picked: IdentifyCandidate? = null
        val candidates = listOf(
            IdentifyCandidate("Monstera deliciosa", "Swiss cheese plant", ScoreLevel.High, monstera),
            IdentifyCandidate("Unknownia rara", null, ScoreLevel.Medium, null),
            IdentifyCandidate("Tinyus leafus", "Tiny", ScoreLevel.Low, null),
        )
        setContent {
            Ru {
                SpeciesIdentifyDialog(IdentifyState.Results(candidates), "ru", onPick = { picked = it }, onSearchCatalog = {}, onDismiss = {})
            }
        }
        waitForIdle()
        onNodeWithText("Монстера деликатесная").assertExists()
        onNodeWithText("Уверенность: высокая").assertExists()
        onNodeWithText("Уверенность: средняя").assertExists()
        onNodeWithText("Уверенность: низкая").assertExists()
        assertEquals(1, onAllNodesWithText("Есть в каталоге").fetchSemanticsNodes().size)
        onNodeWithText("Powered by Pl@ntNet").assertExists()
        assertEquals(0, onAllNodesWithText("%", substring = true).fetchSemanticsNodes().size)
        onNodeWithTag("identify_candidate_1").performClick()
        assertEquals("Unknownia rara", picked?.scientificName)
    }

    @Test
    fun quota_and_unavailable_offer_catalog_search() = runDesktopComposeUiTest {
        var state: IdentifyState by mutableStateOf<IdentifyState>(IdentifyState.QuotaExceeded)
        var searches = 0
        setContent {
            Ru { SpeciesIdentifyDialog(state, "ru", onPick = {}, onSearchCatalog = { searches++ }, onDismiss = {}) }
        }
        waitForIdle()
        onNodeWithText("Лимит распознаваний на сегодня исчерпан").assertExists()
        onNodeWithTag("identify_searchCatalog").performClick()
        state = IdentifyState.Unavailable
        waitForIdle()
        onNodeWithText("Распознавание недоступно").assertExists()
        onNodeWithTag("identify_loading").assertDoesNotExist()
        onNodeWithTag("identify_searchCatalog").performClick()
        onNodeWithText("Powered by Pl@ntNet").assertExists()
        assertEquals(2, searches)
        assertTrue(onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
