@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Care settings sheet, variant A (VAL-CARD-008..011): −/+ steppers stop at the server bounds, a switch turns a
 * care type off (explicit null), and a failed save is shown in the sheet with the edited values kept. A network
 * error leaves the change in the offline queue and says so.
 */
@OptIn(ExperimentalTestApi::class)
class CareSettingsSheetTest {
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
    private var water = 7
    private var fertilize: Int? = 30
    private var repot: Int? = 12
    private var prune: Int? = 6
    private var patchStatus = HttpStatusCode.OK
    private var patchThrows: Throwable? = null

    private fun plantJson() =
        """{"id":"cs-1","user_id":"73","name":"Ficus","location":"Kitchen","photo_url":"",""" +
            """"water_frequency_days":$water,"last_watered_date":"2026-09-20",""" +
            """"fertilize_frequency_days":$fertilize,"last_fertilized_date":"2026-08-01",""" +
            """"repot_frequency_months":$repot,"last_repotted_date":"2026-01-10",""" +
            """"prune_frequency_months":$prune,"last_pruned_date":"2026-03-15",""" +
            """"notes":"","created_at":"2026-09-01T10:00:00.000Z"}"""

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get ->
                respond("[${plantJson()}]", HttpStatusCode.OK, json)
            path == "/api/plants/cs-1" && request.method == HttpMethod.Patch -> {
                val body = (request.body as TextContent).text
                patches += body
                patchThrows?.let { throw it }
                if (patchStatus != HttpStatusCode.OK) {
                    respond("""{"error":"Invalid plant data"}""", patchStatus, json)
                } else {
                    val fields = Json.parseToJsonElement(body).jsonObject
                    fun int(key: String, old: Int?): Int? = when (val v = fields[key]) {
                        null -> old
                        is JsonNull -> null
                        else -> (v as JsonPrimitive).intOrNull
                    }
                    water = int("water_frequency_days", water)!!
                    fertilize = int("fertilize_frequency_days", fertilize)
                    repot = int("repot_frequency_months", repot)
                    prune = int("prune_frequency_months", prune)
                    respond(plantJson(), HttpStatusCode.OK, json)
                }
            }
            path.endsWith("/photos") -> respond("""{"photos":[]}""", HttpStatusCode.OK, json)
            path.endsWith("/events") -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-care-sheet", "").also { it.delete() }
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

    private fun ComposeUiTest.showPlant(repo: PlantRepository) {
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = true) {
                    PlantDetailScreen(
                        userId = "73",
                        plantId = "cs-1",
                        opener = PlantRepositoryOpener { repo },
                        onBack = {},
                        onDeleted = {},
                        deleteErrorState = PlantDeleteErrorState(),
                    )
                }
            }
        }
        waitUnmerged(hasText("Care Settings"))
    }

    private fun ComposeUiTest.waitUnmerged(matcher: SemanticsMatcher, present: Boolean = true) {
        waitUntil(timeoutMillis = TIMEOUT) {
            onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun ComposeUiTest.click(matcher: SemanticsMatcher) {
        val merged = onAllNodes(matcher)
        val nodes = if (merged.fetchSemanticsNodes().isNotEmpty()) merged else onAllNodes(matcher, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        check(count > 0) { "no node for $matcher" }
        nodes[count - 1].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.openSheet() {
        click(hasClickAction() and hasText("Care Settings"))
        waitUnmerged(hasTestTag(CareTags.TITLE))
    }

    private fun ComposeUiTest.press(type: CareType, delta: Int, times: Int = 1) {
        val tag = CareTags.stepper(type) + if (delta < 0) CareTags.DEC else CareTags.INC
        repeat(times) { click(hasTestTag(tag)) }
    }

    private fun ComposeUiTest.shownValue(type: CareType): String =
        onAllNodes(hasTestTag(CareTags.stepper(type) + CareTags.VALUE), useUnmergedTree = true)
            .fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString()

    private fun ComposeUiTest.isDisabled(tag: String): Boolean =
        SemanticsProperties.Disabled in onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().single().config

    @Test
    fun steppers_and_switch_save_one_patch_and_the_card_disappears() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            waitUnmerged(hasText("Repotting"))
            openSheet()
            assertEquals("30", shownValue(CareType.Fertilize))
            press(CareType.Fertilize, -1, times = 16)
            assertEquals("14", shownValue(CareType.Fertilize))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Fertilize) + CareTags.UNIT) and hasText("days"))
            click(hasTestTag(CareTags.switch(CareType.Repot)))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Repot) + CareTags.VALUE), present = false)
            click(hasTestTag(CareTags.SAVE))
            waitUntil(timeoutMillis = TIMEOUT) { patches.isNotEmpty() }
            waitUnmerged(hasTestTag(CareTags.TITLE), present = false)
            assertEquals(1, patches.size, "one PATCH: $patches")
            val body = Json.parseToJsonElement(patches.single()).jsonObject
            assertEquals(14, (body["fertilize_frequency_days"] as JsonPrimitive).content.toInt())
            assertEquals(JsonNull, body["repot_frequency_months"])
            assertTrue("water_frequency_days" !in body, "unchanged watering is not sent: $body")
            assertEquals(14, fertilize)
            assertEquals(null, repot)
            // Repot card leaves the care grid; reopening shows 14 and repotting off.
            waitUnmerged(hasText("Repotting"), present = false)
            openSheet()
            assertEquals("14", shownValue(CareType.Fertilize))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Repot) + CareTags.VALUE), present = false)
        }
    }

    @Test
    fun water_stepper_stays_inside_the_server_range() {
        water = 1
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            val dec = CareTags.stepper(CareType.Water) + CareTags.DEC
            val inc = CareTags.stepper(CareType.Water) + CareTags.INC
            assertTrue(isDisabled(dec), "− is disabled at the minimum")
            assertEquals("1", shownValue(CareType.Water))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Water) + CareTags.UNIT) and hasText("day"))
            press(CareType.Water, +1)
            assertEquals("2", shownValue(CareType.Water))
            assertTrue(!isDisabled(dec))
            assertTrue(!isDisabled(inc))
        }
    }

    @Test
    fun water_stepper_stops_at_the_maximum() {
        water = 365
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            assertTrue(isDisabled(CareTags.stepper(CareType.Water) + CareTags.INC), "+ is disabled at the maximum")
            assertEquals("365", shownValue(CareType.Water))
            press(CareType.Water, -1)
            assertEquals("364", shownValue(CareType.Water))
        }
    }

    @Test
    fun each_switch_is_one_node_with_its_title() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            val isSwitch = SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch)
            val switches = onAllNodes(isSwitch, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(3, switches.size, "fertilize, repot and prune: one switch node each")
            for ((type, title) in listOf(CareType.Fertilize to "Fertilizing", CareType.Repot to "Repotting", CareType.Prune to "Pruning")) {
                val node = onAllNodes(hasTestTag(CareTags.switch(type)), useUnmergedTree = true).fetchSemanticsNodes().single()
                assertTrue(isSwitch.matches(node), "$type tag is the switch node")
                assertTrue(SemanticsActions.OnClick in node.config, "$type switch node is clickable")
                assertTrue(
                    androidx.compose.ui.test.hasAnyDescendant(hasText(title)).matches(node),
                    "$type switch node holds its title text",
                )
                val twins = onAllNodes(androidx.compose.ui.test.hasContentDescription(title), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                assertEquals(0, twins.size, "$type: no second node named by a content description")
            }
        }
    }

    @Test
    fun switching_a_type_on_starts_at_the_default() {
        prune = null
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Prune) + CareTags.VALUE), present = false)
            click(hasTestTag(CareTags.switch(CareType.Prune)))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Prune) + CareTags.VALUE))
            assertEquals("6", shownValue(CareType.Prune))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Prune) + CareTags.UNIT) and hasText("months"))
        }
    }

    @Test
    fun rejected_save_400_shows_a_message_and_keeps_the_values() {
        patchStatus = HttpStatusCode.BadRequest
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            press(CareType.Fertilize, -1, times = 2)
            click(hasTestTag(CareTags.switch(CareType.Prune)))
            click(hasTestTag(CareTags.SAVE))
            waitUnmerged(hasTestTag(CareTags.ERROR))
            waitUnmerged(hasTestTag(CareTags.ERROR) and hasText("The server did not accept the changes", substring = true))
            assertEquals(1, patches.size)
            // Sheet stays open with the edited values.
            waitUnmerged(hasTestTag(CareTags.TITLE))
            assertEquals("28", shownValue(CareType.Fertilize))
            waitUnmerged(hasTestTag(CareTags.stepper(CareType.Prune) + CareTags.VALUE), present = false)
            // Nothing changed on the server, and the local copy is rolled back.
            assertEquals(30, fertilize)
            assertEquals(6, prune)
            val local = runBlocking { repo.observePlants().first().single() }
            assertEquals(30, local.fertilizeFrequencyDays)
            assertEquals(6, local.pruneFrequencyMonths)
            // Retry after the server recovers sends the same values.
            patchStatus = HttpStatusCode.OK
            click(hasTestTag(CareTags.SAVE))
            waitUnmerged(hasTestTag(CareTags.TITLE), present = false)
            assertEquals(28, fertilize)
            assertEquals(null, prune)
        }
    }

    @Test
    fun server_error_5xx_shows_a_message_and_keeps_the_values() {
        patchStatus = HttpStatusCode.InternalServerError
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            press(CareType.Water, +1)
            click(hasTestTag(CareTags.SAVE))
            waitUnmerged(hasTestTag(CareTags.ERROR) and hasText("server error", substring = true))
            waitUnmerged(hasTestTag(CareTags.TITLE))
            assertEquals("8", shownValue(CareType.Water))
            assertEquals(7, water)
        }
    }

    @Test
    fun network_error_keeps_the_change_in_the_queue_and_says_so() {
        patchThrows = IllegalStateException("Connection reset")
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            press(CareType.Water, +1, times = 2)
            click(hasTestTag(CareTags.SAVE))
            waitUnmerged(hasText("Saved on this phone"))
            waitUnmerged(hasTestTag(CareTags.TITLE), present = false)
            val local = runBlocking { repo.observePlants().first().single() }
            assertEquals(9, local.waterFrequencyDays, "the queued change stays on the phone")
            // Back online: the replay sends the queued change once.
            patchThrows = null
            runBlocking { repo.replayPending() }
            assertEquals(9, water)
            assertEquals(2, patches.size, "one failed try + one replay: $patches")
        }
    }

    @Test
    fun unchanged_save_sends_nothing_and_closes() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            click(hasTestTag(CareTags.SAVE))
            waitUnmerged(hasTestTag(CareTags.TITLE), present = false)
            waitForIdle()
            assertEquals(0, patches.size)
        }
    }

    @Test
    fun close_cancels_without_patch() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            showPlant(repo)
            openSheet()
            press(CareType.Water, +1)
            click(hasTestTag(CareTags.CLOSE))
            waitUnmerged(hasTestTag(CareTags.TITLE), present = false)
            waitForIdle()
            assertEquals(0, patches.size)
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
