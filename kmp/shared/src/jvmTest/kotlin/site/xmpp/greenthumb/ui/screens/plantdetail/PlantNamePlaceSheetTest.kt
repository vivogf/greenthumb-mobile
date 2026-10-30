@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
import site.xmpp.greenthumb.ui.components.GT_MODAL_SCRIM_TAG
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-CARD-004..007 on plant detail: the «Name and place» sheet. ✕ and system Back (Escape on desktop)
 * send no PATCH; ✓ sends exactly one with both fields; «Other…» saves free text; a cleared place is "".
 */
@OptIn(ExperimentalTestApi::class)
class PlantNamePlaceSheetTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        cleanup?.invoke()
        cleanup = null
    }

    private val json = headersOf("Content-Type", "application/json")
    private val patches = CopyOnWriteArrayList<String>()
    private var name = "Ficus"
    private var location = "Kitchen"

    private fun plantJson() =
        """{"id":"np-1","user_id":"73","name":${JsonPrimitive(name)},"location":${JsonPrimitive(location)},""" +
            """"photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
            """"created_at":"2026-09-01T10:00:00.000Z"}"""

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get ->
                respond("[${plantJson()}]", HttpStatusCode.OK, json)
            path == "/api/plants/np-1" && request.method == HttpMethod.Patch -> {
                val body = (request.body as TextContent).text
                patches += body
                val fields = Json.parseToJsonElement(body).jsonObject
                (fields["name"] as? JsonPrimitive)?.contentOrNull?.let { name = it }
                (fields["location"] as? JsonPrimitive)?.contentOrNull?.let { location = it }
                respond(plantJson(), HttpStatusCode.OK, json)
            }
            path.endsWith("/photos") -> respond("""{"photos":[]}""", HttpStatusCode.OK, json)
            path.endsWith("/events") -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-name-place", "").also { it.delete() }
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

    private fun ComposeUiTest.showPlant(repo: PlantRepository, language: String = "en") {
        setContent {
            AppEnvironment(customAppLocale = language) {
                GreenThumbTheme(darkTheme = true) {
                    PlantDetailScreen(
                        userId = "73",
                        plantId = "np-1",
                        opener = PlantRepositoryOpener { repo },
                        onBack = {},
                        onDeleted = {},
                        deleteErrorState = PlantDeleteErrorState(),
                    )
                }
            }
        }
        waitUnmerged(hasText(name))
    }

    private fun ComposeUiTest.waitUnmerged(matcher: SemanticsMatcher, present: Boolean = true) {
        waitUntil(timeoutMillis = TIMEOUT) {
            onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun ComposeUiTest.click(matcher: SemanticsMatcher) {
        val nodes = onAllNodes(matcher)
        nodes[nodes.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ComposeUiTest.openSheet() {
        click(hasClickAction() and hasTestTag("plantDetail_name"))
        waitUnmerged(hasTestTag(NamePlaceTags.TITLE))
    }

    private fun ComposeUiTest.nameField() =
        onAllNodes(hasTestTag(NamePlaceTags.NAME), useUnmergedTree = true)[0]

    private fun ComposeUiTest.editNameAndPickBedroom() {
        nameField().performTextClearance()
        nameField().performTextInput("Changed name")
        click(hasTestTag(NamePlaceTags.CHIP_PREFIX + PlacePreset.Bedroom.ordinal))
        waitForIdle()
    }

    private fun ComposeUiTest.assertSheetClosedWithOldValues() {
        waitUnmerged(hasTestTag(NamePlaceTags.TITLE), present = false)
        waitForIdle()
        assertEquals(0, patches.size, "cancel sends no PATCH: $patches")
        waitUnmerged(hasText("Ficus"))
        waitUnmerged(hasTestTag("plantDetail_location") and hasAnyDescendant(hasText("Kitchen")))
    }

    @Test
    fun sheet_opens_with_name_place_chips_and_the_current_place_selected() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            waitUnmerged(hasText("Name and place"))
            val labels = (0 until PlacePreset.entries.size).map { index ->
                onAllNodes(hasTestTag(NamePlaceTags.CHIP_PREFIX + index))
                    .fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString()
            }
            assertEquals(listOf("Living room", "Kitchen", "Bedroom", "Balcony", "Bathroom", "Office"), labels)
            waitUnmerged(hasTestTag(NamePlaceTags.CUSTOM_CHIP) and hasAnyDescendant(hasText("Other…")))
            assertTrue(
                onAllNodes(hasTestTag(NamePlaceTags.CHIP_PREFIX + PlacePreset.Kitchen.ordinal) and isSelected())
                    .fetchSemanticsNodes().isNotEmpty(),
                "Kitchen chip is preselected",
            )
            assertTrue(
                onAllNodes(hasTestTag(NamePlaceTags.NAME) and hasText("Ficus"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "name field holds the current name",
            )
        }
    }

    @Test
    fun close_button_cancels_without_patch() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            editNameAndPickBedroom()
            click(hasClickAction() and hasTestTag(NamePlaceTags.CANCEL))
            assertSheetClosedWithOldValues()
        }
    }

    @Test
    fun dismiss_path_of_system_back_cancels_without_patch() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            editNameAndPickBedroom()
            // On Android the Dialog sends system Back to onDismissRequest; the scrim tap calls the same
            // callback, and the desktop test window has no Back key. The device check covers real Back.
            click(hasTestTag(GT_MODAL_SCRIM_TAG))
            assertSheetClosedWithOldValues()
        }
    }

    @Test
    fun back_key_with_the_name_field_focused_cancels_without_patch() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            nameField().performSemanticsAction(SemanticsActions.RequestFocus)
            nameField().performTextInput(" changed")
            nameField().assertIsFocused()
            // The keyboard (if any) is already hidden here; this is the Back that must reach the sheet.
            nameField().performKeyInput { pressKey(Key.Back) }
            assertSheetClosedWithOldValues()
        }
    }

    @Test
    fun confirm_sends_one_patch_with_name_and_place() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            editNameAndPickBedroom()
            click(hasClickAction() and hasTestTag(NamePlaceTags.CONFIRM))
            waitUntil(timeoutMillis = TIMEOUT) { patches.isNotEmpty() }
            waitForIdle()
            assertEquals(1, patches.size, "one PATCH: $patches")
            val body = Json.parseToJsonElement(patches.single()).jsonObject
            assertEquals("Changed name", (body["name"] as JsonPrimitive).content)
            assertEquals("Bedroom", (body["location"] as JsonPrimitive).content)
            waitUnmerged(hasTestTag(NamePlaceTags.TITLE), present = false)
            waitUnmerged(hasText("Changed name"))
            waitUnmerged(hasTestTag("plantDetail_location") and hasAnyDescendant(hasText("Bedroom")))
        }
    }

    @Test
    fun blank_name_disables_both_save_controls() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            nameField().performTextClearance()
            nameField().performTextInput("   ")
            waitForIdle()
            listOf(NamePlaceTags.CONFIRM, NamePlaceTags.SAVE).forEach { tag ->
                val node = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single()
                assertTrue(SemanticsProperties.Disabled in node.config, "$tag is disabled for a blank name")
            }
        }
    }

    @Test
    fun custom_place_is_saved_reopens_as_custom_and_can_be_cleared_to_empty_string() {
        location = ""
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo, language = "ru")
            openSheet()
            click(hasTestTag(NamePlaceTags.CUSTOM_CHIP))
            waitUnmerged(hasTestTag(NamePlaceTags.CUSTOM_FIELD))
            onAllNodes(hasTestTag(NamePlaceTags.CUSTOM_FIELD), useUnmergedTree = true)[0]
                .performTextInput("Подоконник у мамы")
            click(hasClickAction() and hasTestTag(NamePlaceTags.SAVE))
            waitUntil(timeoutMillis = TIMEOUT) { patches.size == 1 }
            assertEquals("Подоконник у мамы", (Json.parseToJsonElement(patches[0]).jsonObject["location"] as JsonPrimitive).content)
            waitUnmerged(hasTestTag("plantDetail_location") and hasAnyDescendant(hasText("Подоконник у мамы")))

            openSheet()
            assertTrue(
                onAllNodes(hasTestTag(NamePlaceTags.CUSTOM_CHIP) and isSelected()).fetchSemanticsNodes().isNotEmpty(),
                "«Своё…» is selected on reopen",
            )
            val field = onAllNodes(hasTestTag(NamePlaceTags.CUSTOM_FIELD), useUnmergedTree = true)
            assertTrue(field.fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasTestTag(NamePlaceTags.CUSTOM_FIELD) and hasText("Подоконник у мамы"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "custom text prefilled")
            field[0].performTextClearance()
            click(hasClickAction() and hasTestTag(NamePlaceTags.CONFIRM))
            waitUntil(timeoutMillis = TIMEOUT) { patches.size == 2 }
            val cleared = Json.parseToJsonElement(patches[1]).jsonObject["location"]
            assertEquals(JsonPrimitive(""), cleared, "cleared place is \"\", not null")
            waitUnmerged(hasTestTag("plantDetail_location"), present = false)
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
