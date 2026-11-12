@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import site.xmpp.greenthumb.ui.components.GtInputField
import java.io.File
import kotlin.io.path.deleteRecursively
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * M15 journal table (VAL-CARD15-003): every row of every type has the same date column, a 48 dp
 * leading slot (thumbnail for a photo, type icon otherwise), a text column starting at one x and a
 * 48 dp trailing slot (delete on a note, and on care entries when the screen passes a delete action),
 * in ru and en at font scale 1.0 and 1.3.
 */
@OptIn(ExperimentalTestApi::class)
class PlantJournalTableTest {
    private var cleanup: (() -> Unit)? = null

    /** When set, the note POST waits for it: a test types into the field while the save is in flight. */
    private var noteGate: CompletableDeferred<Unit>? = null

    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    @AfterTest
    fun tearDown() {
        noteGate?.complete(Unit)
        noteGate = null
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
            path == "/api/plants/p-1/events" && request.method == HttpMethod.Get -> respond(
                """{"events":[${events.joinToString(",") { (id, type, date) -> eventJson(id, type, date) }}],"nextCursor":null}""",
                HttpStatusCode.OK,
                json,
            )
            path == "/api/plants/p-1/events" && request.method == HttpMethod.Post -> {
                // A test can hold the save in flight to type into the field while the request is open.
                noteGate?.await()
                respond(eventJson("e-added", "note", "2026-10-20"), HttpStatusCode.Created, json)
            }
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
        widthDp: Int = 360,
        withFocusNeighbour: Boolean = false,
        reduceMotion: Boolean = false,
        deleteCareEntry: (suspend (String) -> SaveOutcome)? = null,
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    GreenThumbTheme(darkTheme = true, reduceMotion = reduceMotion) {
                        // A phone-wide column (Samsung is 384 dp; 360 dp leaves room for screen padding). The page
                        // contract of PlantDetailScreen is repeated here: a lift request scrolls the field to the
                        // top of the column. The real wiring is covered by PlantDetailScreenTest.
                        val scroll = rememberScrollState()
                        val scope = rememberCoroutineScope()
                        var pageTop by remember { mutableStateOf(0f) }
                        Box(
                            Modifier.width(widthDp.dp)
                                .testTag("journal_scroll")
                                .onGloballyPositioned { pageTop = it.positionInWindow().y }
                                .verticalScroll(scroll),
                        ) {
                            Column {
                                PlantJournalSection(
                                    journal = repo.journal,
                                    timeline = repo.timeline,
                                    plantId = "p-1",
                                    online = true,
                                    refreshKey = 0,
                                    locale = locale,
                                    deleteCareEntry = deleteCareEntry,
                                    onComposerLift = { fieldTop ->
                                        scope.launch {
                                            val target = fieldTop - pageTop + scroll.value
                                            scroll.scrollTo(target.toInt().coerceIn(0, scroll.maxValue))
                                        }
                                    },
                                )
                                // A second field below the card: focusing it takes the note field out of focus.
                                if (withFocusNeighbour) {
                                    GtInputField(
                                        value = "",
                                        onValueChange = {},
                                        placeholder = "Elsewhere",
                                        modifier = Modifier.testTag("focusElsewhere"),
                                    )
                                }
                            }
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

    /**
     * 0.8.2: at rest the composer is the bare field — no label, hint on the left, and no save button taking
     * room. Focus alone changes nothing; the «Сохранить заметку» bar on the card's full width comes with the
     * first non-blank character, enabled, without moving the field, and leaves when the draft is empty or
     * blank. The trash of a row has no square.
     */
    @Test
    fun composer_followsTheDraft_atFullWidth_and_the_trash_is_bare() {
        val repo = openRepo()
        // Narrow phone (300 dp column, ~320 dp screen) at the largest font: the worst case for a full-width bar.
        for ((locale, fontScale, widthDp) in listOf(
            Triple("ru", 1.3f, 360),
            Triple("en", 1f, 360),
            Triple("ru", 1.3f, 300),
        )) {
            val where = "[$locale x$fontScale ${widthDp}dp]"
            runDesktopComposeUiTest {
                show(repo, locale, fontScale, widthDp = widthDp)
                val field = one("journal_noteInput")
                val hint = nodes(hasText(if (locale == "ru") "Добавить заметку…" else "Add a note…", substring = true)).single()
                val result = layout(hint)
                println("M15 composer $where idle hintInk=${result.getLineLeft(0)}..${result.getLineRight(0)} width=${result.size.width}")
                // At rest: the hint starts at the left edge, the label is gone, and there is no button at all.
                assertTrue(
                    result.getLineLeft(0) < result.size.width * 0.1f,
                    "$where the hint is left-aligned: ink starts at ${result.getLineLeft(0)} of ${result.size.width}",
                )
                assertTrue(nodes(hasTestTag("journal_entryLabel")).isEmpty(), "$where no composer label at rest")
                assertTrue(nodes(hasTestTag("journal_addNote")).isEmpty(), "$where no save button at rest")

                // Focus alone changes nothing: an empty field keeps the card bare.
                onNode(hasTestTag("journal_noteInput")).performSemanticsAction(SemanticsActions.RequestFocus)
                waitForIdle()
                assertTrue(
                    nodes(hasTestTag("journal_addNote")).isEmpty(),
                    "$where focus on an empty field brings no bar",
                )

                fun fieldTop() = nodes(hasTestTag("journal_noteInput")).single().positionInRoot.y
                val topBeforeTyping = fieldTop()
                // A closed bar keeps no room under the field: the composer ends with the field itself.
                assertEquals(
                    field.size.height.toFloat(),
                    one("journal_composer").size.height.toFloat(),
                    1f,
                    "$where at rest the composer is only the field",
                )

                // The first character brings the bar, enabled, and the field above it stays where it was.
                onNode(hasTestTag("journal_noteInput")).performTextInput(if (locale == "ru") "Первая строка" else "First line")
                waitUntil(timeoutMillis = TIMEOUT) {
                    nodes(hasTestTag("journal_addNote") and isEnabled()).isNotEmpty()
                }
                waitForIdle()
                assertEquals(
                    topBeforeTyping,
                    fieldTop(),
                    1f,
                    "$where the bar grows downwards: the field does not move",
                )
                val button = one("journal_addNote")
                assertEquals(
                    field.size.width.toFloat(),
                    button.size.width.toFloat(),
                    2f,
                    "$where the live bar spans the field",
                )
                assertTrue(
                    nodes(hasTestTag("journal_addNote") and isNotEnabled()).isEmpty(),
                    "$where a visible bar is never grey: it is the live action",
                )
                val labelText = if (locale == "ru") "Сохранить заметку" else "Add note"
                val label = layout(
                    nodes(hasAnyAncestor(hasTestTag("journal_addNote")) and hasText(labelText)).single(),
                )
                assertEquals(1, label.lineCount, "$where the button label stays on one line")

                // Only spaces left: nothing to save, so the bar leaves — and so does an empty draft.
                onNode(hasTestTag("journal_noteInput")).performTextReplacement("   ")
                waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote")).isEmpty() }
                onNode(hasTestTag("journal_noteInput")).performTextInput("x")
                waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote")).isNotEmpty() }
                onNode(hasTestTag("journal_noteInput")).performTextReplacement("")
                waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote")).isEmpty() }
                waitForIdle()
                // The bar shrinks away completely: the field is the whole composer again, and it never moved.
                assertEquals(
                    field.size.height.toFloat(),
                    one("journal_composer").size.height.toFloat(),
                    1f,
                    "$where a closed bar leaves no room behind",
                )
                assertEquals(topBeforeTyping, fieldTop(), 1f, "$where the field stayed put through both animations")

                // The trash is an icon, not a framed button: no hairline runs along the top edge of its box.
                // (A framed GtIconButton draws outline at y = 0..1 and surface below, so the column would split.)
                val delete = one("journal_delete_e-note")
                val pixels = onNode(hasTestTag("journal_delete_e-note"), useUnmergedTree = true)
                    .captureToImage().toPixelMap()
                val column = (0..2).map { y -> pixels[pixels.width / 2, y] }
                val spread = column.maxOf { a -> column.maxOf { b -> colourDistance(a, b) } }
                println("M15 composer $where delete=${delete.size.width}x${delete.size.height} column=$column")
                assertTrue(spread < 0.02f, "$where the delete has no square frame: top edge $column")
            }
        }
    }

    /**
     * 0.8.2: the save bar reads the draft, never the focus. A focused empty field shows no bar, a typed draft
     * keeps the bar after the focus leaves, and emptying the draft without focus takes it away.
     */
    @Test
    fun composer_bar_ignoresFocus_andFollowsTheDraftOnly() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            show(repo, "ru", 1f, withFocusNeighbour = true)

            fun focus(tag: String) = onNode(hasTestTag(tag)).performSemanticsAction(SemanticsActions.RequestFocus)

            focus("journal_noteInput")
            waitForIdle()
            assertTrue(nodes(hasTestTag("journal_addNote")).isEmpty(), "focus alone brings no bar")
            onNode(hasTestTag("journal_noteInput"), useUnmergedTree = true).assertExists()

            // A draft keeps the bar after the field loses focus: a half-written note is never hidden.
            onNode(hasTestTag("journal_noteInput")).performTextInput("половина")
            waitUntil(timeoutMillis = TIMEOUT) {
                nodes(hasTestTag("journal_addNote") and isEnabled()).isNotEmpty()
            }
            focus("focusElsewhere")
            waitForIdle()
            assertTrue(
                nodes(hasTestTag("journal_addNote") and isEnabled()).isNotEmpty(),
                "a typed draft keeps the save bar after blur",
            )

            // Emptied while the focus is elsewhere: the bar leaves with the text.
            onNode(hasTestTag("journal_noteInput")).performTextReplacement("")
            waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote")).isEmpty() }
        }
    }

    /**
     * 0.8.2: the bar grows down from under the field on the screen-change pair of durations, and reduce motion
     * shows it in one frame. The clock is paused, so every frame below is asked for by hand: the bar is measured
     * mid-animation, and the field's top is compared at each step. An animated page scroll would fail this.
     */
    @Test
    fun composer_bar_growsDownwards_overMotionTokens_andShowsAtOnceUnderReduceMotion() {
        val repo = openRepo()
        for (reduceMotion in listOf(false, true)) {
            val where = if (reduceMotion) "[reduce motion]" else "[animated]"
            runDesktopComposeUiTest {
                // The card loads on the running clock; only the bar's animation is stepped by hand below.
                show(repo, "ru", 1f, reduceMotion = reduceMotion)
                mainClock.autoAdvance = false
                waitForIdle()

                fun fieldTop() = one("journal_noteInput").positionInRoot.y
                // The animation clips the bar, so the button node keeps its own height while it opens. What grows
                // is the room the composer takes under the field: that is the height measured here.
                fun composerHeight() = one("journal_composer").size.height.toFloat()
                fun barExists() = nodes(hasTestTag("journal_addNote")).isNotEmpty()
                val fieldHeight = one("journal_noteInput").size.height.toFloat()
                val topAtRest = fieldTop()
                assertTrue(nodes(hasTestTag("journal_addNote")).isEmpty(), "$where no bar at rest")
                assertEquals(fieldHeight, composerHeight(), 1f, "$where at rest the composer is the field alone")

                onNode(hasTestTag("journal_noteInput")).performTextInput("заметка")
                // One frame in: the bar is on screen, and its room starts to open under the field.
                mainClock.advanceTimeBy(1)
                waitForIdle()
                assertTrue(barExists(), "$where the bar is on screen from the first frame")
                val firstFrame = composerHeight()
                assertEquals(topAtRest, fieldTop(), 1f, "$where the field does not move on the first frame")

                // Kept for the lower bound below: half the token must still be short of the full height.
                var middle = firstFrame
                if (!reduceMotion) {
                    // Mid-animation the room is wider open than on the first frame, and the field still holds.
                    mainClock.advanceTimeBy(Motion.ScreenEnterMs.toLong() / 2)
                    waitForIdle()
                    middle = composerHeight()
                    assertTrue(middle > firstFrame, "$where the bar grows downwards: $firstFrame -> $middle")
                    assertEquals(topAtRest, fieldTop(), 1f, "$where the field does not move mid-animation")
                }

                mainClock.advanceTimeBy(Motion.ScreenEnterMs.toLong() + 1)
                waitForIdle()
                val settled = composerHeight()
                assertTrue(barExists(), "$where the bar is up after the animation")
                if (reduceMotion) {
                    assertEquals(firstFrame, settled, 1f, "$where reduce motion opens the whole bar in one frame")
                } else {
                    assertTrue(settled > firstFrame, "$where the bar grew to its full height: $firstFrame -> $settled")
                    // The token is a floor as well as a deadline: a shorter invented duration would already be
                    // fully open at the midpoint, so the middle read would equal the settled one.
                    assertTrue(middle < settled, "$where half the token is still mid-animation: $middle -> $settled")
                }
                assertTrue(settled > fieldHeight, "$where the open bar takes room under the field")
                assertEquals(topAtRest, fieldTop(), 1f, "$where the field did not move while the bar grew")

                // The token is the deadline, not just a step: one token past the start the bar must be fully open.
                // A longer invented duration would still be growing here, and more time would change the height.
                mainClock.advanceTimeBy(Motion.ScreenEnterMs.toLong())
                waitForIdle()
                assertEquals(
                    settled,
                    composerHeight(),
                    1f,
                    "$where the bar is finished by Motion.ScreenEnterMs, not still growing",
                )

                // And back: the draft empties, the bar leaves, the field stays. Count the frames it takes, because
                // reduce motion has to hide the bar in the same single frame it showed it in — an exit transition
                // of None still spends a frame on the removal, so the bar would linger one frame too long.
                onNode(hasTestTag("journal_noteInput")).performTextReplacement("")
                var framesToLeave = 0
                if (!reduceMotion) {
                    // The exit token is a floor too: half of it must still find the bar on screen. Without this
                    // a None exit (2 frames) or a drastically shortened tween would pass the frame count below.
                    mainClock.advanceTimeByFrame()
                    framesToLeave++
                    mainClock.advanceTimeBy(Motion.ScreenExitMs.toLong() / 2)
                    // advanceTimeBy rounds up to whole frames, so this probe spends real frames too. Count them,
                    // or the deadline below would silently grow by the probe's length.
                    framesToLeave += (Motion.ScreenExitMs / 2) / HARNESS_FRAME_MS
                    waitForIdle()
                    assertTrue(barExists(), "$where half the exit token still shows the bar")
                }
                while (barExists() && framesToLeave < 60) {
                    mainClock.advanceTimeByFrame()
                    framesToLeave++
                }
                waitForIdle()
                assertTrue(nodes(hasTestTag("journal_addNote")).isEmpty(), "$where the bar is gone after the exit")
                if (reduceMotion) {
                    assertEquals(1, framesToLeave, "$where reduce motion hides the bar in one frame")
                } else {
                    // The exit runs its own token: it must not vanish on the first frame, and it must be gone
                    // within the token plus three frames — the one that applies the empty draft, the one that
                    // settles the removal, and one spare. The probe's frames are counted above, so this budget
                    // is the whole exit, not just the part after the probe.
                    assertTrue(framesToLeave > 1, "$where the animated bar does not vanish at once: $framesToLeave")
                    assertTrue(
                        framesToLeave <= Motion.ScreenExitMs / HARNESS_FRAME_MS + 3,
                        "$where the bar leaves within Motion.ScreenExitMs: $framesToLeave frames",
                    )
                }
                assertEquals(topAtRest, fieldTop(), 1f, "$where the field did not move while the bar left")
                assertEquals(
                    fieldHeight,
                    one("journal_composer").size.height.toFloat(),
                    1f,
                    "$where the composer is the field alone again",
                )
            }
        }
    }

    /**
     * 0.8.2: a refused delete explains itself in the message under the field, and the user is usually scrolled
     * down to the row they tapped — so the message must come into view by itself.
     */
    @Test
    fun refusedDelete_bringsItsMessageIntoView_whenTheComposerIsScrolledAway() {
        val repo = openRepo()
        // A short window: the card with its rows is taller than this, so the composer can be scrolled away.
        val windowHeight = 360
        runDesktopComposeUiTest(width = 360, height = windowHeight) {
            show(
                repo,
                "ru",
                1f,
                deleteCareEntry = { throw ApiError.Server(500, """{"error":"boom"}""") },
            )
            // Down to the oldest rows: the composer leaves the top of the window. The unmerged node is the column
            // itself — the merged one offers the scroll action of the multi-line note field.
            onNode(hasTestTag("journal_scroll"), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) }
            waitForIdle()
            // positionInRoot, not boundsInRoot: a clipped node reports an empty rect at the clip edge.
            val composerTop = nodes(hasTestTag("journal_noteInput")).single().positionInRoot.y
            assertTrue(composerTop < 0f, "the composer is scrolled away before the delete: $composerTop")

            // The oldest row ends the card, so it stands on screen after the scroll.
            val trash = nodes(hasTestTag("journal_delete_e-old")).single()
            val trashTop = trash.positionInRoot.y
            assertTrue(
                trashTop >= 0f && trashTop + trash.size.height <= windowHeight,
                "the oldest row's trash is on screen: $trashTop",
            )
            onNode(hasTestTag("journal_delete_e-old"), useUnmergedTree = true).performClick()
            waitUntilAtLeastOneExists(hasText("Удалить"), TIMEOUT)
            onNodeWithText("Удалить").performClick()

            waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_message")).isNotEmpty() }
            waitForIdle()
            val message = one("journal_message")
            val top = message.positionInRoot.y
            val bottom = top + message.size.height
            assertTrue(
                top >= 0f && bottom <= windowHeight,
                "the refusal message is on screen: $top..$bottom in 0..$windowHeight",
            )
            // A refused row delete is not a problem with the note: the save bar stays closed next to it.
            assertTrue(
                nodes(hasTestTag("journal_addNote")).isEmpty(),
                "a refused delete does not open the save bar",
            )
            // The composer column no longer spaces its children, so the message carries the gap itself. Without
            // that padding the message would sit flush against the field.
            val field = one("journal_noteInput")
            assertEquals(
                Spacing.sm.value,
                dp(top - (field.positionInRoot.y + field.size.height)),
                1f,
                "the message keeps one Spacing.sm gap under the field",
            )

            // The same refusal again: the message has to come back on screen, not stay where the first one left it.
            onNode(hasTestTag("journal_scroll"), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 5000f) }
            waitForIdle()
            assertTrue(
                one("journal_message").positionInRoot.y < 0f,
                "the message is scrolled away before the second refusal",
            )
            onNode(hasTestTag("journal_delete_e-old"), useUnmergedTree = true).performClick()
            waitUntilAtLeastOneExists(hasText("Удалить"), TIMEOUT)
            onNodeWithText("Удалить").performClick()
            waitUntil(timeoutMillis = TIMEOUT) {
                val shown = nodes(hasTestTag("journal_message"))
                shown.isNotEmpty() && shown.single().positionInRoot.y >= 0f
            }
        }
    }

    /**
     * 0.8.2: what the user types while the save is in flight is a new note, so the composer must stay open with its
     * bar instead of folding away and hiding the keyboard. Emptying the field mid-request keeps the bar too — that
     * is the `|| saving` half of the visibility rule, and the only case where a visible bar is disabled.
     */
    @Test
    fun textTypedWhileTheSaveIsInFlight_keepsTheComposerOpen() {
        noteGate = CompletableDeferred()
        val repo = openRepo()
        runDesktopComposeUiTest(width = 360, height = 700) {
            show(repo, "ru", 1f)
            fun focus(tag: String) = onNode(hasTestTag(tag)).performSemanticsAction(SemanticsActions.RequestFocus)

            focus("journal_noteInput")
            onNode(hasTestTag("journal_noteInput")).performTextInput("abc")
            waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote") and isEnabled()).isNotEmpty() }
            onNode(hasTestTag("journal_addNote")).performClick()
            // The save stands on the gate: the bar is disabled while the request is open.
            waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote") and isNotEnabled()).isNotEmpty() }

            // The other half of the visibility rule: an emptied draft mid-request keeps the bar up, because the
            // request is still open. Without `|| saving` the bar would vanish here and the user would lose the
            // only sign that a save is running.
            focus("journal_noteInput")
            onNode(hasTestTag("journal_noteInput")).performTextReplacement("")
            waitForIdle()
            assertTrue(
                nodes(hasTestTag("journal_addNote") and isNotEnabled()).isNotEmpty(),
                "an open request keeps the bar visible and disabled even with an empty draft",
            )

            focus("journal_noteInput")
            onNode(hasTestTag("journal_noteInput")).performTextInput("x")
            noteGate?.complete(Unit)

            // The save lands and releases the bar; the composer must stay open and keep the typed text.
            waitUntil(timeoutMillis = TIMEOUT) { nodes(hasTestTag("journal_addNote") and isEnabled()).isNotEmpty() }
            waitForIdle()
            assertTrue(
                nodes(hasTestTag("journal_message")).isEmpty(),
                "the save went through: no refusal message",
            )
            // The saved text was "abc"; the field was emptied and then given "x" during the request, so the save
            // must not swallow it: what is in the field now is a new note, and its bar is live again.
            assertEquals(
                "x",
                one("journal_noteInput").config[SemanticsProperties.EditableText].text,
                "the text typed during the request stays in the field",
            )
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L

        /**
         * One virtual frame of the desktop test clock, not a design token: `MainTestClockImpl` is constructed
         * with `frameDelayMillis = 16` by `SkikoComposeUiTest` (compose ui-test-desktop 1.12.0), and
         * `advanceTimeByFrame()` steps exactly that. Used only to turn a [Motion] duration into a frame count;
         * a Compose upgrade that changes the period must change this constant too.
         */
        const val HARNESS_FRAME_MS = 16

        /** Sum of channel differences: 0 for the same colour, ~3 for black against white. */
        fun colourDistance(a: Color, b: Color): Float =
            abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
    }
}
