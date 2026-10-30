@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
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
import site.xmpp.greenthumb.ui.components.GtSheetTags
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Item 8 / VAL-LOOK-018 on plant detail: the header photo and the camera icon both open the
 * «Plant photo» sheet built from GtSheetRow, and a plant without a place shows no location row.
 */
@OptIn(ExperimentalTestApi::class)
class PlantPhotoSheetTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        cleanup?.invoke()
        cleanup = null
    }

    private val json = headersOf("Content-Type", "application/json")

    private fun plant(id: String, location: String) =
        """{"id":"$id","user_id":"73","name":"Ficus $id","location":"$location","photo_url":"",""" +
            """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
            """"created_at":"2026-09-01T10:00:00.000Z"}"""

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get ->
                respond("[${plant("p-empty", "")},${plant("p-place", "Kitchen")}]", HttpStatusCode.OK, json)
            path.endsWith("/photos") -> respond("""{"photos":[]}""", HttpStatusCode.OK, json)
            path.endsWith("/events") -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-photo-sheet", "").also { it.delete() }
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

    private fun ComposeUiTest.showPlant(repo: PlantRepository, plantId: String) {
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = true) {
                    PlantDetailScreen(
                        userId = "73",
                        plantId = plantId,
                        opener = PlantRepositoryOpener { repo },
                        onBack = {},
                        onDeleted = {},
                        deleteErrorState = PlantDeleteErrorState(),
                    )
                }
            }
        }
        waitUnmerged(hasText("Ficus $plantId"))
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

    private fun ComposeUiTest.assertSheetRows() {
        waitUnmerged(hasText("Plant photo"))
        val labels = onAllNodes(hasTestTag(GtSheetTags.LABEL), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString() }
        assertEquals(listOf("Take a new photo", "Choose from gallery", "Cancel"), labels)
        click(hasClickAction() and hasText("Cancel"))
        waitUnmerged(hasText("Plant photo"), present = false)
    }

    @Test
    fun header_photo_and_camera_icon_open_the_plant_photo_sheet() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo, "p-empty")
            click(hasClickAction() and hasTestTag("plantDetail_headerPhoto"))
            assertSheetRows()
            click(hasClickAction() and hasContentDescription("Change plant photo"))
            assertSheetRows()
        }
    }

    @Test
    fun empty_place_shows_no_location_row() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo, "p-empty")
            waitForIdle()
            assertTrue(
                onAllNodes(hasTestTag("plantDetail_location"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no location row for an empty place",
            )
        }
    }

    @Test
    fun a_place_still_shows_its_location_row() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo, "p-place")
            waitUnmerged(hasTestTag("plantDetail_location"))
            waitUnmerged(hasText("Kitchen"))
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
