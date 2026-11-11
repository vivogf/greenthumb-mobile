@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.deleteRecursively
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.SaveOutcome
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * M15 journal table (VAL-CARD15-003): every row of every type has the same date column, a 48 dp
 * leading slot (thumbnail for a photo, type icon otherwise), a text column starting at one x and a
 * 48 dp trailing slot (delete on a note, and on care entries when the screen passes a delete action),
 * in ru and en at font scale 1.0 and 1.3.
 */
@OptIn(ExperimentalTestApi::class)
class PlantJournalTableTest {
    private var cleanup: (() -> Unit)? = null

    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    @AfterTest
    fun tearDown() {
        cleanup?.invoke()
        cleanup = null
    }

    private val json = headersOf("Content-Type", "application/json")
    private val cover = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="

    /** Newest first, one entry of each type plus an unknown type and an entry from an earlier year. */
    private val events = listOf(
        Triple("e-note", "note", "2026-10-12"),
        Triple("e-photo", "photo", "2026-09-30"),
        Triple("e-water", "water", "2026-09-20"),
        Triple("e-fert", "fertilize", "2026-08-01"),
        Triple("e-repot", "repot", "2026-05-03"),
        Triple("e-prune", "prune", "2026-03-15"),
        Triple("e-mist", "mist", "2026-02-28"),
        Triple("e-old", "water", "2025-12-28"),
    )
    private val ids = events.map { it.first }

    private fun eventJson(id: String, type: String, date: String): String {
        val extra = when (type) {
            "note" -> """"note":"Moved to the window","""
            "photo" -> """"photo_id":"ph-1","""
            else -> ""
        }
        return """{"id":"$id","plant_id":"p-1","user_id":"73","type":"$type","occurred_on":"$date",$extra""" +
            """"created_at":"${date}T10:00:00.000Z"}"""
    }

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get -> respond(
                """[{"id":"p-1","user_id":"73","name":"Ficus","location":"","photo_url":"",""" +
                    """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
                    """"created_at":"2026-01-01T10:00:00.000Z"}]""",
                HttpStatusCode.OK,
                json,
            )
            path == "/api/plants/p-1/photos" -> respond(
                """{"photos":[{"id":"ph-1","url":"$cover","taken_on":"2026-09-30","caption":"New leaf",""" +
                    """"created_at":"2026-09-30T10:00:00.000Z"}]}""",
                HttpStatusCode.OK,
                json,
            )
            path == "/api/plants/p-1/events" -> respond(
                """{"events":[${events.joinToString(",") { (id, type, date) -> eventJson(id, type, date) }}],"nextCursor":null}""",
                HttpStatusCode.OK,
                json,
            )
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-journal-table", "").also { it.delete() }
        assertTrue(dir.mkdirs(), "temp dir created")
        val databases = JvmPlantDatabases(dir)
        val client = ApiClient(MockEngine(handler), NoSessionRecoveryProvider)
        val gate = AccountPlantGate(
            openDatabase = { userId -> databases.open(userId) },
            deleteDatabase = { userId -> databases.delete(userId) },
            api = GreenThumbApi(client),
            session = AccountSession(),
        )
        val repo = gate.open("73")
        cleanup = {
            runCatching { repo.close() }
            runCatching { client.close() }
            runCatching { dir.toPath().deleteRecursively() }
        }
        runBlocking {
            repo.refresh()
            // The journal section shows cached timeline photos; plant detail refreshes them in its timeline card.
            repo.timeline.refresh("p-1")
        }
        return repo
    }

    private fun ComposeUiTest.show(
        repo: PlantRepository,
        locale: String,
        fontScale: Float,
        deleteCareEntry: (suspend (String) -> SaveOutcome)? = null,
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    GreenThumbTheme(darkTheme = true) {
                        // A phone-wide column (Samsung is 384 dp; 360 dp leaves room for screen padding).
                        Box(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                            PlantJournalSection(
                                journal = repo.journal,
                                timeline = repo.timeline,
                                plantId = "p-1",
                                online = true,
                                refreshKey = 0,
                                locale = locale,
                                deleteCareEntry = deleteCareEntry,
                            )
                        }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = TIMEOUT) { ids.all { nodes(hasTestTag("journal_entry_$it")).isNotEmpty() } }
        waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_thumb_e-photo")).isNotEmpty() }
    }

    private fun ComposeUiTest.nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.one(tag: String): SemanticsNode {
        val found = nodes(hasTestTag(tag))
        assertEquals(1, found.size, "exactly one node tagged $tag")
        return found.single()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun ComposeUiTest.dp(px: Float): Float = with(density) { px.toDp().value }

    private fun assertSame(what: String, values: Map<String, Float>, tolerance: Float = 1f) {
        val min = values.values.min()
        val max = values.values.max()
        assertTrue(max - min <= tolerance, "$what differs by ${max - min} dp: $values")
    }

    @Test
    fun journal_rows_form_a_table_in_ru_and_en_at_font_scale_1_0_and_1_3() {
        val repo = openRepo()
        val expectedDates = mapOf(
            "en" to listOf("Oct 12", "Sep 30", "Sep 20", "Aug 1", "May 3", "Mar 15", "Feb 28", "Dec 28"),
            "ru" to listOf("12 окт.", "30 сент.", "20 сент.", "1 авг.", "3 мая", "15 мар.", "28 февр.", "28 дек."),
        )
        for (locale in listOf("ru", "en")) {
            for (fontScale in listOf(1f, 1.3f)) {
                val where = "[$locale x$fontScale]"
                runDesktopComposeUiTest {
                    show(repo, locale, fontScale)
                    val row = ids.associateWith { one("journal_entry_$it") }
                    val date = ids.associateWith { one("journal_date_$it") }
                    val lead = ids.associateWith { one("journal_lead_$it") }
                    val text = ids.associateWith { one("journal_text_$it") }
                    val trailing = ids.associateWith { one("journal_trailing_$it") }

                    // Newest first, as before.
                    val tops = ids.map { row.getValue(it).positionInRoot.y }
                    assertEquals(tops.sorted(), tops, "$where newest first")

                    val dateLeft = date.mapValues { dp(it.value.positionInRoot.x) }
                    val dateWidth = date.mapValues { dp(it.value.size.width.toFloat()) }
                    val leadLeft = lead.mapValues { dp(it.value.positionInRoot.x) }
                    val leadWidth = lead.mapValues { dp(it.value.size.width.toFloat()) }
                    val textLeft = text.mapValues { dp(it.value.positionInRoot.x) }
                    val trailingLeft = trailing.mapValues { dp(it.value.positionInRoot.x) }
                    val trailingWidth = trailing.mapValues { dp(it.value.size.width.toFloat()) }
                    println("M15 journal $where dateLeft=$dateLeft dateWidth=$dateWidth leadLeft=$leadLeft " +
                        "leadWidth=$leadWidth textLeft=$textLeft trailingLeft=$trailingLeft trailingWidth=$trailingWidth")

                    assertSame("$where date left", dateLeft)
                    assertSame("$where date width", dateWidth)
                    assertSame("$where leading slot left", leadLeft)
                    assertSame("$where text left", textLeft)
                    assertSame("$where trailing left", trailingLeft)
                    assertSame("$where trailing width", trailingWidth)
                    leadWidth.forEach { (id, w) -> assertEquals(48f, w, 0.5f, "$where $id leading slot width") }
                    lead.forEach { (id, node) -> assertEquals(48f, dp(node.size.height.toFloat()), 0.5f, "$where $id leading slot height") }
                    trailingWidth.forEach { (id, w) -> assertEquals(48f, w, 0.5f, "$where $id trailing slot width") }
                    ids.forEach { id ->
                        assertTrue(leadLeft.getValue(id) >= dateLeft.getValue(id) + dateWidth.getValue(id) - 0.5f, "$where $id date before icon")
                        assertTrue(textLeft.getValue(id) >= leadLeft.getValue(id) + 48f - 0.5f, "$where $id icon before text")
                    }

                    // Short, untruncated dates on one line; an earlier year adds the year under it.
                    ids.forEachIndexed { index, id ->
                        val labels = nodes(hasAnyAncestor(hasTestTag("journal_date_$id")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                        val first = labels.first()
                        assertEquals(expectedDates.getValue(locale)[index], texts(first).single(), "$where $id short date")
                        val slot = date.getValue(id).size.width
                        labels.forEach { label ->
                            val result = layout(label)
                            val inkWidth = result.getLineRight(0) - result.getLineLeft(0)
                            assertEquals(1, result.lineCount, "$where $id date on one line: ${texts(label)}")
                            assertTrue(
                                inkWidth <= slot + 0.5f && label.size.width <= slot,
                                "$where $id date ${texts(label)} fits: text $inkWidth px, node ${label.size.width} px, slot $slot px",
                            )
                        }
                        if (id == "e-old") assertTrue(labels.any { texts(it) == listOf("2025") }, "$where earlier year shown")
                    }

                    // Thumbnail only for the photo, a type icon for every other row, both in the leading slot.
                    assertTrue(nodes(hasTestTag("journal_thumb_e-photo") and hasAnyAncestor(hasTestTag("journal_lead_e-photo"))).isNotEmpty(), "$where photo thumbnail in slot")
                    ids.filter { it != "e-photo" }.forEach { id ->
                        assertTrue(nodes(hasTestTag("journal_icon_$id") and hasAnyAncestor(hasTestTag("journal_lead_$id"))).isNotEmpty(), "$where $id type icon")
                        assertTrue(nodes(hasTestTag("journal_thumb_$id")).isEmpty(), "$where $id no thumbnail")
                    }
                    assertTrue(nodes(hasTestTag("journal_caption_e-photo") and hasAnyAncestor(hasTestTag("journal_text_e-photo"))).isNotEmpty(), "$where photo caption in text column")
                    assertTrue(nodes(hasTestTag("journal_caption_e-note") and hasAnyAncestor(hasTestTag("journal_text_e-note"))).isNotEmpty(), "$where note text in text column")

                    // Delete only on the note, inside its trailing slot.
                    val deleteLabel = if (locale == "ru") "Удалить заметку" else "Delete note"
                    val deletes = nodes(hasClickAction() and hasContentDescription(deleteLabel))
                    assertEquals(1, deletes.size, "$where one delete action")
                    assertTrue(
                        nodes(hasClickAction() and hasContentDescription(deleteLabel) and hasAnyAncestor(hasTestTag("journal_trailing_e-note"))).isNotEmpty(),
                        "$where delete sits in the note's trailing slot",
                    )
                }
            }
        }
    }

    /** 0.8.2: with a delete action, care rows get a delete button and a confirmation that names the date effect. */
    @Test
    fun care_entries_get_a_confirmed_delete_and_an_old_server_refusal_is_explained() {
        val repo = openRepo()
        for ((locale, fontScale) in listOf("ru" to 1.3f, "en" to 1f)) {
            val where = "[$locale x$fontScale]"
            val ru = locale == "ru"
            val calls = mutableListOf<String>()
            var refuse = false
            runDesktopComposeUiTest {
                show(repo, locale, fontScale) { id ->
                    calls += id
                    if (refuse) throw ApiError.Client(409, """{"error":"Only notes"}""")
                    SaveOutcome.Queued("p-1")
                }
                val entryLabel = if (ru) "Удалить запись" else "Delete entry"
                val careIds = listOf("e-water", "e-fert", "e-repot", "e-prune", "e-old")
                careIds.forEach { id ->
                    assertTrue(
                        nodes(hasClickAction() and hasContentDescription(entryLabel) and hasAnyAncestor(hasTestTag("journal_trailing_$id"))).isNotEmpty(),
                        "$where $id has a delete button",
                    )
                }
                listOf("e-photo", "e-mist").forEach { id ->
                    assertTrue(nodes(hasTestTag("journal_delete_$id")).isEmpty(), "$where $id has no delete")
                }
                assertEquals(careIds.size, nodes(hasClickAction() and hasContentDescription(entryLabel)).size, "$where care delete count")

                // Water: the message says the date stays; Cancel sends nothing.
                onNode(hasTestTag("journal_delete_e-water"), useUnmergedTree = true).performClick()
                onNodeWithText(if (ru) "Удалить запись?" else "Delete this entry?").assertExists()
                onNodeWithText(
                    if (ru) "Запись удалится из журнала. Дата полива не изменится."
                    else "The entry will be removed from the journal. The watering date stays the same.",
                ).assertExists()
                onNodeWithText(if (ru) "Отмена" else "Cancel").performClick()
                waitForIdle()
                assertTrue(calls.isEmpty(), "$where cancel sends nothing")

                // Fertilize: the message names the card date; Delete calls the repository once.
                onNode(hasTestTag("journal_delete_e-fert"), useUnmergedTree = true).performClick()
                onNodeWithText(
                    if (ru) "Запись удалится из журнала. Если на карточке ухода стоит эта дата, вернётся предыдущая."
                    else "The entry will be removed from the journal. If the care card shows this date, it goes back to the previous one.",
                ).assertExists()
                onNodeWithText(if (ru) "Удалить" else "Delete").performClick()
                waitUntil(timeoutMillis = TIMEOUT) { calls.isNotEmpty() }
                assertEquals(listOf("e-fert"), calls, "$where one delete call")

                // An old server answers 409: the screen says the server is not updated yet.
                refuse = true
                onNode(hasTestTag("journal_delete_e-prune"), useUnmergedTree = true).performClick()
                onNodeWithText(if (ru) "Удалить" else "Delete").performClick()
                waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_message")).isNotEmpty() }
                assertEquals(
                    listOf(
                        if (ru) "Эту запись пока нельзя удалить: сервер ещё не обновлён. Повторите позже."
                        else "This entry can’t be deleted yet: the server is not updated. Try again later.",
                    ),
                    texts(one("journal_message")),
                    "$where old server message",
                )
            }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
