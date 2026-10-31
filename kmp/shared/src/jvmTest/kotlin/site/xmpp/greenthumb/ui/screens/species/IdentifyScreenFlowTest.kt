@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.FilePickBridge
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-IDENT-005/012/014 on the real add-plant screen: "Identify by photo" →
 * Gallery → the desktop file picker gets a JPEG → [ApiSpeciesIdentifier] over
 * [ApiClient] with a MockEngine. The engine records every identify body, and
 * a gate can hold the response so the loading state is observable.
 */
@OptIn(ExperimentalTestApi::class)
class IdentifyScreenFlowTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()


    private class Server {
        val identifyBodies = mutableListOf<String>()

        @Volatile var status: HttpStatusCode = HttpStatusCode.OK

        @Volatile var body: String = OK_BODY

        @Volatile var gate: CompletableDeferred<Unit>? = null

        val engine = MockEngine { request ->
            val json = headersOf("Content-Type", "application/json")
            if (request.url.encodedPath == "/api/species/identify") {
                synchronized(identifyBodies) { identifyBodies += (request.body as TextContent).text }
                gate?.await()
                respond(body, status, json)
            } else {
                respond("{}", HttpStatusCode.NotFound, json)
            }
        }

        fun count(): Int = synchronized(identifyBodies) { identifyBodies.size }

        fun lang(index: Int): String = synchronized(identifyBodies) {
            ApiClient.json.parseToJsonElement(identifyBodies[index]).jsonObject.getValue("lang").jsonPrimitive.content
        }
    }

    private class Harness : AutoCloseable {
        val server = Server()
        val dir: File = File.createTempFile("gt-identify-screen", "").also { it.delete(); it.mkdirs() }
        val photo: File = File(dir, "plant.jpg").also { it.writeBytes(paintedJpeg(64, 48)) }
        private val client = ApiClient(server.engine)
        private val api = GreenThumbApi(client)
        val identifier = ApiSpeciesIdentifier(api)
        private val databases = JvmPlantDatabases(dir)
        private val gate = AccountPlantGate(
            openDatabase = { userId -> databases.open(userId) },
            deleteDatabase = { userId -> databases.delete(userId) },
            api = api,
            session = AccountSession(),
        )
        private val repos = mutableListOf<PlantRepository>()
        val opener = PlantRepositoryOpener { userId ->
            gate.open(userId).also { repo -> synchronized(repos) { if (repos.none { it === repo }) repos += repo } }
        }

        override fun close() {
            synchronized(repos) { repos.forEach { runCatching { it.close() } } }
            runCatching { client.close() }
            runCatching { dir.toPath().deleteRecursively() }
        }
    }

    private var harness: Harness? = null
    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
        // The jvm LocalAppLocale sets the process default locale.
        Locale.setDefault(originalLocale)
    }

    private fun newHarness(): Harness = Harness().also { harness = it }

    /**
     * The modal content sits inside a clickable column that merges its
     * children, so plain tags such as identify_loading are only in the
     * unmerged tree.
     */
    private fun ComposeUiTest.unmergedCount(tag: String): Int =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.waitForTag(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { unmergedCount(tag) > 0 }

    private fun ComposeUiTest.waitForNoTag(tag: String) = waitUntil(timeoutMillis = TIMEOUT) { unmergedCount(tag) == 0 }

    private fun ComposeUiTest.assertNoTag(tag: String) = assertEquals(0, unmergedCount(tag), "$tag is shown")

    private fun ComposeUiTest.click(matcher: SemanticsMatcher) {
        waitUntilAtLeastOneExists(matcher, TIMEOUT)
        onAllNodes(matcher)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ComposeUiTest.clickTag(tag: String) {
        waitForTag(tag)
        onAllNodes(hasTestTag(tag), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * With [pick] the photo box sets the fixture JPEG as the cover first (Gallery → file; no crop
     * screen [done] since M11c); «Find species by photo» exists only with a cover. Then the button
     * sends that cover, with no second picker.
     */
    private fun ComposeUiTest.identifyFromGallery(h: Harness, done: String = "Done", pick: Boolean = true) {
        val before = h.server.count()
        if (pick) {
            assertNoTag("addPlant_identify")
            click(hasClickAction() and hasContentDescription(if (done == "Done") "Add photo" else "Добавить фото"))
            clickTag("addPlant_photo_gallery")
            waitUntil(timeoutMillis = TIMEOUT) { FilePickBridge.pending != null }
            runOnIdle {
                val request = FilePickBridge.pending!!
                FilePickBridge.clear(request)
                request.deferred.complete(h.photo.absolutePath)
            }
            waitForTag("addPlant_identify")
            assertEquals(0, onAllNodes(hasClickAction() and hasText(done)).fetchSemanticsNodes().size, "no crop screen")
            assertEquals(before, h.server.count(), "choosing the cover sends nothing")
        }
        clickTag("addPlant_identify")
        assertEquals(null, FilePickBridge.pending, "no picker for a chosen photo")
        waitUntil(timeoutMillis = TIMEOUT) { h.server.count() == before + 1 }
    }

    /** Every text and content description on screen, dialogs included. */
    private fun ComposeUiTest.visibleTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            val config = node.config
            val texts = config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.map { it.text }
            val descriptions = config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }
            texts + descriptions
        }

    private fun ComposeUiTest.assertNoRawScore() {
        val texts = visibleTexts()
        assertTrue("Powered by Pl@ntNet" in texts, "text collection sees the dialog: $texts")
        val offending = texts.filter { it.contains('%') || RAW_DECIMAL.containsMatchIn(it) }
        assertTrue(offending.isEmpty(), "raw score shown: $offending")
    }

    @Test
    fun en_ui_sends_lang_en_and_shows_confidence_words_without_numbers() {
        val h = newHarness()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            identifyFromGallery(h)
            assertTrue(h.server.identifyBodies[0].contains("\"lang\":\"en\""), h.server.identifyBodies[0])
            assertEquals("en", h.server.lang(0))
            assertTrue(h.server.identifyBodies[0].contains("\"photos\":[\"data:image/jpeg;base64,"), "photo sent as a JPEG data URL")

            waitForTag("identify_candidate_2")
            onNodeWithText("Swiss cheese plant").assertExists()
            onNodeWithText("Confidence: high").assertExists()
            onNodeWithText("Confidence: medium").assertExists()
            onNodeWithText("Confidence: low").assertExists()
            assertEquals(1, unmergedCount("identify_inCatalog_0"))
            assertEquals(1, unmergedCount("identify_inCatalog_1"), "Monstera adansonii is in the catalog too")
            assertNoTag("identify_inCatalog_2")
            onNodeWithText("Powered by Pl@ntNet").assertExists()
            assertNoRawScore()
        }
    }

    @Test
    fun switching_ui_language_ru_to_en_changes_lang_of_next_identify_request() {
        val h = newHarness()
        runDesktopComposeUiTest {
            var locale by mutableStateOf("ru")
            setContent {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Добавить фото"), TIMEOUT)
            identifyFromGallery(h, done = "Готово")
            assertEquals("ru", h.server.lang(0))
            waitUntilAtLeastOneExists(hasText("Уверенность: высокая"), TIMEOUT)
            clickTag("identify_close")
            waitForNoTag("identify_dialog")

            locale = "en"
            waitUntilAtLeastOneExists(hasText("Find species by photo"), TIMEOUT)
            onNodeWithText("Узнать вид по фото").assertDoesNotExist()
            identifyFromGallery(h, pick = false)
            assertEquals("en", h.server.lang(1))
            assertTrue(h.server.identifyBodies[1].contains("\"lang\":\"en\""), h.server.identifyBodies[1])
            waitUntilAtLeastOneExists(hasText("Confidence: high"), TIMEOUT)
        }
    }

    @Test
    fun quota_429_settles_without_spinner_and_catalog_search_opens_working_picker() {
        val h = newHarness()
        h.server.status = HttpStatusCode.TooManyRequests
        h.server.body = """{"error":"Daily identification limit reached","code":"quota_exceeded"}"""
        h.server.gate = CompletableDeferred()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            identifyFromGallery(h)
            waitForTag("identify_loading")
            onNodeWithText("Identifying the species…").assertExists()
            h.server.gate!!.complete(Unit)

            waitUntilAtLeastOneExists(hasText("Daily identification limit reached"), TIMEOUT)
            assertNoTag("identify_loading")
            onNodeWithText("Identifying the species…").assertDoesNotExist()
            onNodeWithText("Search the catalog").assertExists()

            openPickerAndChooseMonstera()
        }
    }

    @Test
    fun ru_quota_429_shows_the_daily_identification_limit() {
        val h = newHarness()
        h.server.status = HttpStatusCode.TooManyRequests
        h.server.body = """{"error":"Daily identification limit reached","code":"quota_exceeded"}"""
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Добавить фото"), TIMEOUT)
            identifyFromGallery(h, done = "Готово")
            waitUntilAtLeastOneExists(hasText("Лимит распознаваний на сегодня исчерпан"), TIMEOUT)
            assertNoTag("identify_loading")
        }
    }

    @Test
    fun unavailable_503_shows_en_text_and_catalog_search() {
        val h = newHarness()
        h.server.status = HttpStatusCode.ServiceUnavailable
        h.server.body = """{"error":"Identification is unavailable","code":"identify_unavailable"}"""
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            identifyFromGallery(h)
            waitUntilAtLeastOneExists(hasText("Identification is unavailable"), TIMEOUT)
            assertNoTag("identify_loading")
            onNodeWithTag("identify_searchCatalog", useUnmergedTree = true).assertExists()
            openPickerAndChooseMonstera()
        }
    }

    @Test
    fun empty_results_show_message_and_catalog_search_without_candidates() {
        val h = newHarness()
        h.server.body = """{"results":[],"remainingToday":7}"""
        h.server.gate = CompletableDeferred()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = h.opener, onBack = {}, identifier = h.identifier)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            identifyFromGallery(h)
            waitForTag("identify_loading")
            h.server.gate!!.complete(Unit)

            waitUntilAtLeastOneExists(
                hasText("No species recognized. Try another photo or search the catalog."),
                TIMEOUT,
            )
            assertNoTag("identify_loading")
            assertNoTag("identify_candidate_0")
            onNodeWithText("Confidence:", substring = true).assertDoesNotExist()
            onNodeWithTag("identify_searchCatalog", useUnmergedTree = true).assertExists()
            openPickerAndChooseMonstera()
        }
    }

    /**
     * Taps "Search the catalog": the identify dialog closes, the species
     * picker opens, a query finds Monstera, and choosing it fills the form.
     */
    private fun ComposeUiTest.openPickerAndChooseMonstera() {
        clickTag("identify_searchCatalog")
        waitForNoTag("identify_dialog")
        val search = isEditable() and hasText("Name or scientific name", substring = true)
        waitUntilAtLeastOneExists(search, TIMEOUT)
        onAllNodes(search)[0].performTextInput("Monstera deliciosa")
        click(hasText("Swiss cheese plant"))
        waitUntilDoesNotExist(search, TIMEOUT)
        waitUntilAtLeastOneExists(
            hasText("Care intervals are filled in from the species. You can change them."),
            TIMEOUT,
        )
        onNodeWithText("Monstera deliciosa").assertExists()
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
        val RAW_DECIMAL = Regex("""\d[.,]\d""")
        const val OK_BODY = """{"results":[
            {"scientificName":"Monstera deliciosa","commonNames":["Swiss cheese plant"],"score":0.81,"gbifId":"2868241"},
            {"scientificName":"Monstera adansonii","commonNames":[],"score":0.32,"gbifId":null},
            {"scientificName":"Unknownia rara","commonNames":["Rare one"],"score":0.05,"gbifId":null}
        ],"remainingToday":19}"""
    }
}
