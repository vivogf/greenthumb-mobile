package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VAL-IDENT-012/014: what the identification dialog shows in Russian and English. */
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

    @Composable
    private fun En(content: @Composable () -> Unit) {
        AppEnvironment(customAppLocale = "en") { GreenThumbTheme(darkTheme = false) { content() } }
    }

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        // The jvm LocalAppLocale sets the process default locale.
        Locale.setDefault(originalLocale)
    }

    private fun dto(name: String, score: Double) =
        SpeciesCandidateDto(scientificName = name, commonNames = emptyList(), score = score, gbifId = null)

    /** Server-shaped results at the word boundaries, mapped the way the screen maps them. */
    private val boundaryTop = IdentifyLogic.toCandidates(
        listOf(dto("Alpha fifty", 0.50), dto("Beta fortynine", 0.49), dto("Gamma twenty", 0.20)),
        catalog = null,
    )
    private val boundaryLow = IdentifyLogic.toCandidates(listOf(dto("Delta nineteen", 0.19)), catalog = null)

    private fun ComposeUiTest.unmergedTags(tag: String): Int =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /**
     * GtModal merges its content, so tags are matched in the unmerged tree
     * and the text is looked up among the tagged node's descendants.
     */
    private fun ComposeUiTest.taggedWithText(tag: String, text: String) =
        onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    /** Every text and content description in the dialog, unmerged. */
    private fun ComposeUiTest.visibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        }

    private fun ComposeUiTest.assertNoRawScore() {
        val texts = visibleTexts()
        assertTrue("Powered by Pl@ntNet" in texts, "text collection sees the dialog: $texts")
        val offending = texts.filter { it.contains('%') || Regex("""\d[.,]\d""").containsMatchIn(it) }
        assertTrue(offending.isEmpty(), "raw score shown: $offending")
    }

    /** Renders the boundary results in [locale] and checks the word for each candidate row. */
    private fun assertBoundaryWords(locale: String, prefix: String, high: String, medium: String, low: String) =
        runDesktopComposeUiTest {
            var state: IdentifyState by mutableStateOf<IdentifyState>(IdentifyState.Results(boundaryTop))
            setContent {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = false) {
                        SpeciesIdentifyDialog(state, locale, onPick = {}, onSearchCatalog = {}, onDismiss = {})
                    }
                }
            }
            waitForIdle()
            taggedWithText("identify_candidate_0", "$prefix$high").assertExists()
            taggedWithText("identify_candidate_1", "$prefix$medium").assertExists()
            taggedWithText("identify_candidate_2", "$prefix$medium").assertExists()
            assertNoRawScore()
            state = IdentifyState.Results(boundaryLow)
            waitForIdle()
            taggedWithText("identify_candidate_0", "$prefix$low").assertExists()
            assertEquals(0, unmergedTags("identify_candidate_1"))
            assertNoRawScore()
        }

    @Test
    fun en_results_use_words_at_score_boundaries_without_raw_numbers() =
        assertBoundaryWords("en", "Confidence: ", high = "high", medium = "medium", low = "low")

    @Test
    fun ru_results_use_words_at_score_boundaries_without_raw_numbers() =
        assertBoundaryWords("ru", "Уверенность: ", high = "высокая", medium = "средняя", low = "низкая")

    @Test
    fun en_results_show_catalog_mark_and_attribution() = runDesktopComposeUiTest {
        val candidates = listOf(
            IdentifyCandidate("Monstera deliciosa", "Monstera", ScoreLevel.High, monstera),
            IdentifyCandidate("Unknownia rara", null, ScoreLevel.Low, null),
        )
        setContent {
            En { SpeciesIdentifyDialog(IdentifyState.Results(candidates), "en", onPick = {}, onSearchCatalog = {}, onDismiss = {}) }
        }
        waitForIdle()
        onNodeWithText("Identify species").assertExists()
        onNodeWithText("Swiss cheese plant").assertExists()
        onNodeWithText("Tap a species to fill in the form.").assertExists()
        assertEquals(1, onAllNodesWithText("In the catalog").fetchSemanticsNodes().size)
        assertEquals(1, unmergedTags("identify_inCatalog_0"))
        onNodeWithText("Powered by Pl@ntNet").assertExists()
        assertEquals(1, onAllNodes(hasContentDescription("Pl@ntNet logo"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertNoRawScore()
    }

    @Test
    fun en_errors_and_empty_replace_the_spinner_and_offer_catalog_search() = runDesktopComposeUiTest {
        var state: IdentifyState by mutableStateOf<IdentifyState>(IdentifyState.Loading)
        var searches = 0
        setContent {
            En { SpeciesIdentifyDialog(state, "en", onPick = {}, onSearchCatalog = { searches++ }, onDismiss = {}) }
        }
        val settled = listOf(
            IdentifyState.QuotaExceeded to "Daily identification limit reached",
            IdentifyState.Unavailable to "Identification is unavailable",
            IdentifyState.Empty to "No species recognized. Try another photo or search the catalog.",
        )
        settled.forEachIndexed { index, (next, message) ->
            state = IdentifyState.Loading
            waitForIdle()
            assertEquals(1, unmergedTags("identify_loading"), "spinner while loading before $next")
            onNodeWithText("Identifying the species…").assertExists()
            state = next
            waitForIdle()
            assertEquals(0, unmergedTags("identify_loading"), "$next leaves no spinner")
            onNodeWithText("Identifying the species…").assertDoesNotExist()
            onNodeWithText(message).assertExists()
            assertEquals(0, unmergedTags("identify_candidate_0"), "$next invents no candidates")
            taggedWithText("identify_searchCatalog", "Search the catalog").performClick()
            assertEquals(index + 1, searches)
            onNodeWithText("Powered by Pl@ntNet").assertExists()
        }
    }

    @Test
    fun ru_empty_shows_message_and_catalog_search() = runDesktopComposeUiTest {
        var searches = 0
        setContent {
            Ru { SpeciesIdentifyDialog(IdentifyState.Empty, "ru", onPick = {}, onSearchCatalog = { searches++ }, onDismiss = {}) }
        }
        waitForIdle()
        onNodeWithText("Вид не распознан. Попробуйте другое фото или найдите вид в каталоге.").assertExists()
        assertEquals(0, unmergedTags("identify_loading"))
        taggedWithText("identify_searchCatalog", "Искать в каталоге").performClick()
        assertEquals(1, searches)
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
        onNodeWithTag("identify_candidate_1", useUnmergedTree = true).performClick()
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
        onNodeWithTag("identify_searchCatalog", useUnmergedTree = true).performClick()
        state = IdentifyState.Unavailable
        waitForIdle()
        onNodeWithText("Распознавание недоступно").assertExists()
        assertEquals(0, onAllNodesWithTag("identify_loading", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithTag("identify_searchCatalog", useUnmergedTree = true).performClick()
        onNodeWithText("Powered by Pl@ntNet").assertExists()
        assertEquals(2, searches)
        assertTrue(onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
