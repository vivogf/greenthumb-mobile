package site.xmpp.greenthumb.ui.screens.progress

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockRequestHandler
import java.io.IOException
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.data.AchievementId
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.RepoHarness
import site.xmpp.greenthumb.data.USER
import site.xmpp.greenthumb.data.ok
import site.xmpp.greenthumb.data.openHarness
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Progress screen (architecture §5.8; VAL-PROGRESS-018/019/020): streaks and nine
 * localized cells, the cached copy offline, a "needs network" state when nothing
 * is cached, and tolerance of unknown or missing achievement ids.
 */
@OptIn(ExperimentalTestApi::class)
class ProgressScreenTest {
    private val originalLocale: Locale = Locale.getDefault()
    private var harness: RepoHarness? = null

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        harness?.close()
        harness = null
    }

    /** `first_plant` and `first_water` unlocked, plus an unknown id; `first_note` omitted. */
    private val body = """{"streak":{"current":4,"best":11},"achievements":[
        {"id":"first_plant","unlocked":true,"unlocked_at":"2026-10-20T10:00:00.000Z"},
        {"id":"plants_5","unlocked":false,"unlocked_at":null},
        {"id":"first_water","unlocked":true,"unlocked_at":"2026-10-21T10:00:00.000Z"},
        {"id":"waterings_25","unlocked":false,"unlocked_at":null},
        {"id":"waterings_100","unlocked":false,"unlocked_at":null},
        {"id":"streak_7","unlocked":false,"unlocked_at":null},
        {"id":"streak_30","unlocked":false,"unlocked_at":null},
        {"id":"moon_landing","unlocked":true,"unlocked_at":"2026-10-21T10:00:00.000Z"},
        {"id":"first_photo","unlocked":false,"unlocked_at":null}
    ]}"""

    private fun harness(handler: MockRequestHandler): RepoHarness = openHarness(handler = handler).also { harness = it }

    private fun ComposeUiTest.show(locale: String, box: RepoHarness, online: Boolean) {
        val opener = PlantRepositoryOpener { box.repo }
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    ProgressScreen(
                        userId = USER,
                        opener = opener,
                        isOnline = MutableStateFlow(online),
                        onBack = {},
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        }

    private fun ComposeUiTest.cellDescription(id: AchievementId): String =
        onNode(hasTestTag("progress_cell_${id.wire}")).fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ")

    private fun ComposeUiTest.assertNoRawIds() {
        val all = texts()
        AchievementId.entries.forEach { id ->
            assertTrue(all.none { it.contains(id.wire) }, "raw id ${id.wire} shown: $all")
        }
        assertTrue(all.none { it.contains("moon_landing") }, "unknown id shown")
        assertTrue(all.none { it.contains("achievement_") || it.contains("progress_") }, "raw key shown: $all")
        assertTrue(all.none { it.contains("%1") || it.contains("%2") }, "placeholder shown: $all")
    }

    private fun ComposeUiTest.assertCells(unlocked: String, locked: String) {
        assertEquals(
            9,
            onAllNodes(SemanticsMatcher("cell") { node ->
                node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("progress_cell_")
            }).fetchSemanticsNodes().size,
        )
        val open = setOf(AchievementId.FirstPlant, AchievementId.FirstWater)
        AchievementId.entries.forEach { id ->
            val description = cellDescription(id)
            if (id in open) {
                assertTrue(description.contains(unlocked), "$id: $description")
            } else {
                assertTrue(description.contains(locked) && !description.contains(unlocked), "$id: $description")
            }
        }
    }

    @Test
    fun online_english_streaks_and_nine_cells() = runDesktopComposeUiTest {
        val box = harness { _ -> ok(body) }
        show("en", box, online = true)
        waitFor(hasTestTag("progress_cell_first_plant"))
        assertTrue(exists(hasTestTag("progress_current") and hasText("4")))
        assertTrue(exists(hasTestTag("progress_best") and hasText("11")))
        assertTrue(exists(hasText("Current streak")))
        assertTrue(exists(hasText("First plant")))
        assertTrue(exists(hasText("Write a note in a plant's journal.")))
        assertCells(unlocked = "Unlocked", locked = "Locked")
        assertNoRawIds()
    }

    @Test
    fun online_russian_streaks_and_nine_cells() = runDesktopComposeUiTest {
        val box = harness { _ -> ok(body) }
        show("ru", box, online = true)
        waitFor(hasTestTag("progress_cell_first_plant"))
        assertTrue(exists(hasText("Текущая серия")))
        assertTrue(exists(hasText("Первый полив")))
        assertTrue(exists(hasText("Первая заметка")))
        assertCells(unlocked = "Получено", locked = "Не получено")
        assertNoRawIds()
    }

    @Test
    fun offline_shows_the_cached_copy_without_a_spinner() = runDesktopComposeUiTest {
        var offline = false
        val box = harness { _ -> if (offline) throw IOException("offline") else ok(body) }
        runBlocking { box.repo.progress.refresh() }
        offline = true
        show("en", box, online = false)
        waitFor(hasTestTag("progress_offline"))
        assertTrue(exists(hasTestTag("progress_current") and hasText("4")))
        assertTrue(exists(hasTestTag("progress_best") and hasText("11")))
        assertCells(unlocked = "Unlocked", locked = "Locked")
        assertTrue(!exists(hasTestTag("progress_loading")), "no spinner over cached data")
        assertTrue(!exists(hasTestTag("progress_needsNetwork")))
    }

    @Test
    fun offline_without_cache_needs_network() = runDesktopComposeUiTest {
        var requests = 0
        val box = harness { _ ->
            requests++
            ok(body)
        }
        show("ru", box, online = false)
        waitFor(hasTestTag("progress_needsNetwork"))
        assertTrue(exists(hasText("Для прогресса нужна сеть. Подключитесь к интернету и попробуйте снова.")))
        assertTrue(!exists(hasTestTag("progress_loading")))
        assertTrue(!exists(hasTestTag("progress_cell_first_plant")))
        assertEquals(0, requests, "offline: no request")
    }

    @Test
    fun failed_first_load_offers_retry() = runDesktopComposeUiTest {
        var fail = true
        val box = harness { _ -> if (fail) throw IOException("down") else ok(body) }
        show("en", box, online = true)
        waitFor(hasTestTag("progress_needsNetwork"))
        assertTrue(!exists(hasTestTag("progress_loading")))
        fail = false
        onNode(hasTestTag("progress_retry"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(hasTestTag("progress_cell_first_water"))
        assertTrue(!exists(hasTestTag("progress_needsNetwork")))
        assertTrue(exists(hasContentDescription("Back")))
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
