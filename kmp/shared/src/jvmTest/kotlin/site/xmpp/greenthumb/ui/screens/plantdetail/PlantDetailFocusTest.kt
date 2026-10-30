@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.components.rememberPhotoPicker
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-TIMELINE-016: a dialog opened from plant detail must not hand focus back to
 * the journal note field when it closes. On Android the field kept Compose focus
 * behind the dialog and restarted input when the window regained focus.
 */
@OptIn(ExperimentalTestApi::class)
class PlantDetailFocusTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        cleanup?.invoke()
        cleanup = null
    }

    private class Server {
        var photoPosts = 0
        val photos = mutableListOf("""{"id":"ph-1","url":"","taken_on":"2026-10-02","caption":"Seed","created_at":"2026-10-02T08:00:00.000Z"}""")
        val events = mutableListOf(
            """{"id":"n-1","plant_id":"det-1","user_id":"73","type":"note","occurred_on":"2026-10-01","note":"Old note",""" +
                """"photo_id":null,"created_at":"2026-10-01T08:00:00.000Z"}""",
        )
        private val json = headersOf("Content-Type", "application/json")

        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/plants" && request.method == HttpMethod.Get -> respond(
                    """[{"id":"det-1","user_id":"73","name":"Ficus","location":"","photo_url":"",""" +
                        """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
                        """"created_at":"2026-09-01T10:00:00.000Z"}]""",
                    HttpStatusCode.OK,
                    json,
                )
                path == "/api/plants/det-1/photos" && request.method == HttpMethod.Get ->
                    respond("""{"photos":[${synchronized(photos) { photos.joinToString(",") }}]}""", HttpStatusCode.OK, json)
                path == "/api/plants/det-1/photos" && request.method == HttpMethod.Post -> {
                    photoPosts++
                    val photo = """{"id":"ph-2","url":"","taken_on":"2026-10-03","caption":"Leaf","created_at":"2026-10-03T08:00:00.000Z"}"""
                    synchronized(photos) { photos.add(0, photo) }
                    respond(
                        photo.dropLast(1) + ""","event":{"id":"e-2","plant_id":"det-1","user_id":"73","type":"photo",""" +
                            """"occurred_on":"2026-10-03","note":null,"photo_id":"ph-2","created_at":"2026-10-03T08:00:00.000Z"}}""",
                        HttpStatusCode.Created,
                        json,
                    )
                }
                path == "/api/plants/det-1/events" && request.method == HttpMethod.Get ->
                    respond("""{"events":[${synchronized(events) { events.joinToString(",") }}],"nextCursor":null}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
    }

    private fun openRepo(server: Server): PlantRepository {
        val dir = File.createTempFile("gt-detail-focus", "").also { it.delete() }
        assertTrue(dir.mkdirs(), "temp dir created")
        val databases = JvmPlantDatabases(dir)
        val client = ApiClient(MockEngine(server.handler), NoSessionRecoveryProvider)
        val api = GreenThumbApi(client)
        val gate = AccountPlantGate(
            openDatabase = { userId -> databases.open(userId) },
            deleteDatabase = { userId -> databases.delete(userId) },
            api = api,
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
            repo.timeline.refresh("det-1")
            repo.journal.refresh("det-1")
        }
        return repo
    }

    @Composable
    private fun TimelineAndJournal(repo: PlantRepository, pick: TimelinePick) {
        AppEnvironment(customAppLocale = "en") {
            GreenThumbTheme(darkTheme = false) {
                val picker = rememberPhotoPicker { pick.bytes = it }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PlantTimelineSection(
                        timeline = repo.timeline,
                        plantId = "det-1",
                        online = true,
                        refreshKey = 0,
                        locale = "en",
                        picker = picker,
                        pick = pick,
                    )
                    PlantJournalSection(
                        journal = repo.journal,
                        plantId = "det-1",
                        online = true,
                        refreshKey = 0,
                        locale = "en",
                    )
                }
            }
        }
    }

    private val noteInput = hasTestTag("journal_noteInput")

    private fun ComposeUiTest.waitUnmerged(matcher: SemanticsMatcher, present: Boolean = true) {
        waitUntil(timeoutMillis = TIMEOUT) {
            onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun ComposeUiTest.clickUnmerged(matcher: SemanticsMatcher) {
        val nodes = onAllNodes(matcher, useUnmergedTree = true)
        nodes[nodes.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Text buttons merge their label into the clickable node; the dialog's node is the last one. */
    private fun ComposeUiTest.clickLast(label: String) {
        val nodes = onAllNodes(hasClickAction() and hasText(label))
        nodes[nodes.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ComposeUiTest.focusNote() {
        waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(noteInput).fetchSemanticsNodes().isNotEmpty() }
        onNode(noteInput).performSemanticsAction(SemanticsActions.RequestFocus)
        onNode(noteInput).assertIsFocused()
    }

    private fun ComposeUiTest.assertNothingFocused() {
        waitForIdle()
        onNode(noteInput).assertIsNotFocused()
        val focused = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true)
        assertEquals(0, focused.fetchSemanticsNodes().size, "no field keeps focus after the dialog closed")
    }

    @Test
    fun captionSaveAndCancel_leaveNoFieldFocused_andNoteFieldStillTakesText() {
        val server = Server()
        val repo = openRepo(server)
        val pick = TimelinePick()
        runDesktopComposeUiTest {
            setContent { TimelineAndJournal(repo, pick) }

            // Cancel path.
            focusNote()
            runOnIdle { pick.bytes = byteArrayOf(1, 2, 3) }
            waitUnmerged(hasTestTag("timeline_captionDialog"))
            clickUnmerged(hasClickAction() and hasText("Cancel"))
            waitUnmerged(hasTestTag("timeline_captionDialog"), present = false)
            assertNothingFocused()

            // Save path, with a typed caption.
            focusNote()
            runOnIdle { pick.bytes = byteArrayOf(4, 5, 6) }
            waitUnmerged(hasTestTag("timeline_captionDialog"))
            onNode(hasTestTag("timeline_captionInput"), useUnmergedTree = true).performTextInput("Leaf")
            clickUnmerged(hasClickAction() and hasTestTag("timeline_save"))
            waitUntil(timeoutMillis = TIMEOUT) { server.photoPosts == 1 }
            waitUnmerged(hasTestTag("timeline_captionDialog"), present = false)
            assertNothingFocused()

            // A later tap on the note field focuses it and typing works.
            onNode(noteInput).performClick()
            onNode(noteInput).assertIsFocused()
            onNode(noteInput).performTextInput("After caption")
            assertEquals(1, onAllNodes(noteInput and hasText("After caption")).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun photoViewerAndNoteDeleteConfirmation_leaveNoFieldFocused() {
        val server = Server()
        val repo = openRepo(server)
        runDesktopComposeUiTest {
            setContent { TimelineAndJournal(repo, TimelinePick()) }

            // Full-screen photo with its delete confirmation, both cancelled.
            focusNote()
            waitUnmerged(hasContentDescription("Open photo from 10/02/2026"))
            clickUnmerged(hasClickAction() and hasContentDescription("Open photo from 10/02/2026"))
            waitUnmerged(hasTestTag("timeline_viewer"))
            clickLast("Delete photo")
            waitUnmerged(hasText("Delete this photo?"))
            clickLast("Cancel")
            waitUnmerged(hasText("Delete this photo?"), present = false)
            clickLast("Close")
            waitUnmerged(hasTestTag("timeline_viewer"), present = false)
            assertNothingFocused()

            // Note delete confirmation, cancelled.
            focusNote()
            waitUnmerged(hasClickAction() and hasContentDescription("Delete note"))
            clickUnmerged(hasClickAction() and hasContentDescription("Delete note"))
            waitUnmerged(hasText("Delete this note?"))
            clickLast("Cancel")
            waitUnmerged(hasText("Delete this note?"), present = false)
            assertNothingFocused()
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
