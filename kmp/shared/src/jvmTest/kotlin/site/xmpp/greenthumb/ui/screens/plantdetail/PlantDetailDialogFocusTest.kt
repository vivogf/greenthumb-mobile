@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

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
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-TIMELINE-017: the plant-detail screen's own dialogs (change-photo sheet, delete-plant
 * confirmation, species picker from Care Settings) clear focus when they open and close, so a
 * focused note field or care field does not get focus back without a keyboard.
 */
@OptIn(ExperimentalTestApi::class)
class PlantDetailDialogFocusTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        cleanup?.invoke()
        cleanup = null
    }

    private val json = headersOf("Content-Type", "application/json")

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get -> respond(
                """[{"id":"det-1","user_id":"73","name":"Ficus","location":"","photo_url":"",""" +
                    """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
                    """"created_at":"2026-09-01T10:00:00.000Z"}]""",
                HttpStatusCode.OK,
                json,
            )
            path == "/api/plants/det-1/photos" -> respond("""{"photos":[]}""", HttpStatusCode.OK, json)
            path == "/api/plants/det-1/events" -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-detail-dialog-focus", "").also { it.delete() }
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
        runBlocking { repo.refresh() }
        return repo
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

    private fun ComposeUiTest.assertNothingFocused(message: String) {
        waitForIdle()
        val focused = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true)
        assertEquals(0, focused.fetchSemanticsNodes().size, message)
    }

    @Test
    fun changePhotoDeletePlantAndSpeciesPicker_leaveNoFieldFocused_andKeepTheDraft() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        PlantDetailScreen(
                            userId = "73",
                            plantId = "det-1",
                            opener = PlantRepositoryOpener { repo },
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                        )
                    }
                }
            }

            focusNote()
            onNode(noteInput).performTextInput("Draft")

            // Change-photo sheet, cancelled.
            onNode(hasClickAction() and hasContentDescription("Change plant photo")).performClick()
            waitUnmerged(hasText("Plant photo"))
            clickLast("Cancel")
            waitUnmerged(hasText("Plant photo"), present = false)
            onNode(noteInput).assertIsNotFocused()
            assertNothingFocused("no field keeps focus after the change-photo sheet")

            // Delete-plant confirmation, cancelled.
            focusNote()
            onNode(hasClickAction() and hasContentDescription("Delete plant")).performSemanticsAction(SemanticsActions.OnClick)
            waitUnmerged(hasText("Delete this plant?"))
            clickLast("Cancel")
            waitUnmerged(hasText("Delete this plant?"), present = false)
            assertNothingFocused("no field keeps focus after the delete-plant confirmation")

            // Species picker from Care Settings (the sheet has no text fields: steppers only).
            focusNote()
            onNode(hasClickAction() and hasText("Care Settings")).performSemanticsAction(SemanticsActions.OnClick)
            waitUnmerged(hasText("Plant care"))
            assertNothingFocused("the note loses focus when Care Settings opens")
            clickUnmerged(hasClickAction() and hasText("Choose species"))
            waitUnmerged(hasText(SPECIES_HINT))
            clickLast("Cancel")
            waitUnmerged(hasText(SPECIES_HINT), present = false)
            assertNothingFocused("nothing gets focus back after the picker")
            clickUnmerged(hasTestTag(CareTags.CLOSE))
            waitUnmerged(hasText("Plant care"), present = false)
            assertNothingFocused("no field keeps focus after Care Settings")

            assertEquals(1, onAllNodes(noteInput and hasText("Draft")).fetchSemanticsNodes().size, "typed note survives")
            waitForIdle()
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
        const val SPECIES_HINT = "Name or scientific name"
    }
}
