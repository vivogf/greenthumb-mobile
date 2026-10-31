package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.Collections
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-CARD-013/014/015/016 on screen, ru and en: the result of a plant check from «Что с растением?» over the real
 * [ApiClient] stack — likelihood words with dots, the photo, «Что делать», the disclaimer, no numbers or counters;
 * «Сохранить в журнал растения» posts the three-cause caption to that plant, shows the save error and retries;
 * without a plant journal nothing about saving is shown. The desktop has no camera, so the photo comes through the
 * gallery fallback.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraPlantResultScreenTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private val originalLocale: Locale = Locale.getDefault()
    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val basil = SmartCameraPlant(id = "p-1", name = "Базилик", photoUrl = "")
    private val json = headersOf("Content-Type", "application/json")

    private val okBody = """{"causes":[
        {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Water less."},
        {"title":"Low light","likelihood":"medium","explanation":"Pale leaves.","advice":"Move closer to a window."},
        {"title":"Pests","likelihood":"low","explanation":"No webs seen.","advice":"Check the leaf undersides."}
    ],"summary":"Most likely too much water."}"""

    private val created = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-30",""" +
        """"caption":"c","created_at":"2026-10-30T10:00:00.000Z"}"""

    private class Calls {
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val photoBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private fun api(calls: Calls, handler: MockRequestHandler): GreenThumbApi {
        val client = ApiClient(
            MockEngine { request ->
                calls.paths += request.url.encodedPath
                if (request.url.encodedPath.endsWith("/photos")) {
                    (request.body as? TextContent)?.text?.let { calls.photoBodies += it }
                }
                handler(request)
            },
            NoSessionRecoveryProvider,
        )
        val previous = cleanup
        cleanup = { previous?.invoke(); client.close() }
        return GreenThumbApi(client)
    }

    private fun okHandler(): MockRequestHandler = { request ->
        when (request.url.encodedPath) {
            "/api/plants/p-1/diagnose" -> respond(okBody, HttpStatusCode.OK, json)
            "/api/plants/p-1/photos" -> respond(created, HttpStatusCode.Created, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun ComposeUiTest.show(locale: String, api: GreenThumbApi, journal: Boolean = true) {
        val diagnoser = ApiPlantDiagnoser(api)
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = true) {
                    SmartCameraScreen(
                        initialMode = SmartCameraMode.Disease,
                        identifier = null,
                        diagnoser = diagnoser,
                        onClose = {},
                        onAddToMine = {},
                        myPlants = listOf(basil),
                        journal = if (journal) DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) } else null,
                        pick = { PickResult.Picked(paintedJpeg(320, 240)) },
                        deleteCapture = {},
                        plant = basil,
                        plantDiagnoser = diagnoser,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.count(tag: String): Int =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) = waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.click(tag: String) {
        waitFor(hasTestTag(tag))
        onNode(hasTestTag(tag), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tagged(tag: String, text: String) = hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    private fun ComposeUiTest.diagnose() {
        click("smartcam_fallbackGallery")
        waitFor(hasTestTag("smartcam_aftershot"))
        click("smartcam_send")
        waitFor(hasTestTag("diagnosis_result"))
    }

    private fun assertResultAndSave(
        locale: String,
        words: List<String>,
        whatToDo: String,
        save: String,
        saved: String,
        done: String,
        disclaimer: String,
        caption: String,
    ) = runDesktopComposeUiTest {
        val calls = Calls()
        show(locale, api(calls, okHandler()))
        diagnose()
        words.forEachIndexed { index, word ->
            assertTrue(exists(tagged("diagnosis_likelihood_$index", word)), "[$locale] cause $index says '$word'")
            assertEquals(3 - index, count("diagnosis_dotFilled_$index"), "[$locale] filled dots of cause $index")
            assertEquals(index, count("diagnosis_dotEmpty_$index"), "[$locale] empty dots of cause $index")
        }
        assertTrue(exists(hasTestTag("diagnosis_resultPhoto")), "photo thumbnail")
        assertTrue(exists(tagged("diagnosis_summary", "Most likely too much water.")))
        assertTrue(exists(tagged("diagnosis_whatToDo", whatToDo)))
        assertTrue(exists(hasText("Move closer to a window.")), "advice is shown as sent")
        assertTrue(exists(tagged("diagnosis_disclaimer", disclaimer)))
        assertTrue(exists(tagged("diagnosis_saveJournal", save)))
        assertTrue(exists(tagged("diagnosis_done", done)))
        val all = texts()
        assertTrue(all.none { it.contains('%') || it.contains(Regex("\\d")) }, "no number or percentage: $all")
        assertTrue(all.none { it.contains("осталось", ignoreCase = true) || it.contains(" left today") }, "no counter")
        assertTrue(!exists(hasTestTag("smartcam_send")), "the after-shot screen is replaced by the result")

        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveJournal", saved))
        onNode(hasTestTag("diagnosis_saveJournal"), useUnmergedTree = true).assert(isNotEnabled())
        waitForIdle()
        assertEquals(listOf("/api/plants/p-1/diagnose", "/api/plants/p-1/photos"), calls.paths.toList())
        val body = Json.parseToJsonElement(calls.photoBodies.single()).jsonObject
        assertEquals(caption, body["caption"]!!.jsonPrimitive.content)
        assertTrue(body["photo"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun ru_result_shows_words_dots_what_to_do_and_saves_to_the_plant() = assertResultAndSave(
        "ru",
        words = listOf("Скорее всего", "Возможно", "Маловероятно"),
        whatToDo = "Что делать",
        save = "Сохранить в журнал растения",
        saved = "Сохранено в журнал",
        done = "Готово",
        disclaimer = "Это подсказка, а не диагноз. Если растению становится хуже, обратитесь к специалисту.",
        caption = "Что с растением?: Overwatering (Скорее всего), Low light (Возможно), Pests (Маловероятно)",
    )

    @Test
    fun en_result_shows_words_dots_what_to_do_and_saves_to_the_plant() = assertResultAndSave(
        "en",
        words = listOf("Most likely", "Possible", "Unlikely"),
        whatToDo = "What to do",
        save = "Save to plant journal",
        saved = "Saved to journal",
        done = "Done",
        disclaimer = "This is a hint, not a verdict. If the plant keeps getting worse, ask a specialist.",
        caption = "Plant check: Overwatering (Most likely), Low light (Possible), Pests (Unlikely)",
    )

    private fun assertSaveFailureThenRetry(failFirst: MockRequestHandler) = runDesktopComposeUiTest {
        val calls = Calls()
        var photoCalls = 0
        val ok = okHandler()
        show("ru", api(calls) { request ->
            if (request.url.encodedPath == "/api/plants/p-1/photos" && ++photoCalls == 1) failFirst(request) else ok(request)
        })
        diagnose()
        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveError", "Не удалось сохранить. Попробуйте ещё раз."))
        assertTrue(exists(hasTestTag("diagnosis_result")), "the result stays")
        onNode(hasTestTag("diagnosis_saveJournal"), useUnmergedTree = true).assert(isEnabled())
        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveJournal", "Сохранено в журнал"))
        assertTrue(!exists(hasTestTag("diagnosis_saveError")))
        assertEquals(2, calls.photoBodies.size)
        assertEquals(calls.photoBodies[0], calls.photoBodies[1], "same prepared photo and caption")
        assertEquals(1, calls.paths.count { it.endsWith("/diagnose") }, "no new diagnosis call")
    }

    @Test
    fun network_error_on_save_is_shown_and_retry_saves() = assertSaveFailureThenRetry { throw java.io.IOException("airplane mode") }

    @Test
    fun server_error_on_save_is_shown_and_retry_saves() =
        assertSaveFailureThenRetry { respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, json) }

    @Test
    fun without_a_plant_journal_nothing_about_saving_is_shown() = runDesktopComposeUiTest {
        show("ru", api(Calls(), okHandler()), journal = false)
        diagnose()
        assertTrue(!exists(hasTestTag("diagnosis_saveJournal")))
        assertTrue(!exists(hasTestTag("smartcam_chosenPlant")))
        assertTrue(!exists(hasTestTag("smartcam_notInList")))
        assertTrue(!exists(hasTestTag("smartcam_isMine")))
        assertTrue(exists(tagged("diagnosis_done", "Готово")))
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
