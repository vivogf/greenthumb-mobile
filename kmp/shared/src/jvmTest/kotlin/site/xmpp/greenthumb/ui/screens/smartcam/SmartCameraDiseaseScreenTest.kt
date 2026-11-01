package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import site.xmpp.greenthumb.ui.screens.species.ApiSpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-CAM-009/010/011/012 on screen, ru and en: the street-plant result says it is not saved and offers
 * «Это моё растение?» instead of the journal button; the plant picker lists the user's plants; the save goes
 * to the chosen plant once; limit answers show clear texts; no counter text appears. The desktop has no
 * camera, so the photo comes through the gallery fallback and is sent with the after-shot button.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraDiseaseScreenTest {
    private val originalLocale: Locale = Locale.getDefault()
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val okBody = """{"causes":[
        {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Water less."},
        {"title":"Low light","likelihood":"low","explanation":"Pale leaves.","advice":"More light."}
    ],"summary":"Too much water."}"""

    private val plants = listOf(
        SmartCameraPlant(id = "p-1", name = "Фикус", photoUrl = ""),
        SmartCameraPlant(id = "p-2", name = "Монстера", photoUrl = ""),
    )

    private var calls = 0

    private fun api(status: HttpStatusCode, body: String): GreenThumbApi {
        val client = ApiClient(
            MockEngine { calls++; respond(body, status, jsonHeaders) },
            NoSessionRecoveryProvider,
        )
        val previous = cleanup
        cleanup = { previous?.invoke(); client.close() }
        return GreenThumbApi(client)
    }

    private fun ComposeUiTest.show(
        locale: String,
        mode: SmartCameraMode,
        api: GreenThumbApi,
        journal: DiagnosisJournalSaver? = null,
        identifier: SpeciesIdentifier? = ApiSpeciesIdentifier(api),
        onClose: () -> Unit = {},
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = true) {
                    SmartCameraScreen(
                        initialMode = mode,
                        identifier = identifier,
                        diagnoser = ApiPlantDiagnoser(api),
                        onClose = onClose,
                        onAddToMine = {},
                        myPlants = plants,
                        journal = journal,
                        pick = { PickResult.Picked(paintedJpeg(320, 240)) },
                        deleteCapture = {},
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) = waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.click(tag: String) {
        waitFor(hasTestTag(tag))
        onNode(hasTestTag(tag), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tagged(tag: String, text: String) = hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun ComposeUiTest.assertNoCounter() {
        val all = texts()
        val counter = Regex("(?i)осталось|left today|\\b\\d+ из \\d+\\b|\\b\\d+ of \\d+\\b")
        assertTrue(all.none { counter.containsMatchIn(it) }, "counter text: $all")
        assertTrue(all.none { it.contains("переустанов", ignoreCase = true) || it.contains("reinstall", ignoreCase = true) }, "$all")
    }

    private class Labels(
        val notInList: String,
        val isMine: String,
        val pickTitle: String,
        val chosen: String,
        val save: String,
        val saved: String,
        val captionPrefix: String,
    )

    private fun streetPlantSavedToChosenPlant(locale: String, l: Labels) = runDesktopComposeUiTest {
        val saves = mutableListOf<Triple<String, String, String>>()
        val api = api(HttpStatusCode.OK, okBody)
        show(locale, SmartCameraMode.Disease, api, journal = DiagnosisJournalSaver { id, uri, caption -> saves += Triple(id, uri, caption) })
        click("smartcam_fallbackGallery")
        click("smartcam_send")
        waitFor(hasTestTag("diagnosis_result"))
        assertEquals(1, calls)
        assertTrue(exists(hasTestTag("diagnosis_cause_0")))
        assertTrue(exists(hasTestTag("diagnosis_whatToDo")))
        assertTrue(exists(hasTestTag("diagnosis_disclaimer")))
        assertTrue(exists(tagged("smartcam_notInList", l.notInList)), "[$locale] ${texts()}")
        assertTrue(exists(tagged("smartcam_isMine", l.isMine)))
        assertTrue(exists(hasTestTag("diagnosis_done")))
        assertTrue(!exists(hasTestTag("diagnosis_saveJournal")), "no journal button for a street plant")
        assertNoCounter()

        click("smartcam_isMine")
        waitFor(tagged("smartcam_pickPlantTitle", l.pickTitle))
        assertTrue(exists(tagged("smartcam_plant_p-1", "Фикус")))
        assertTrue(exists(tagged("smartcam_plant_p-2", "Монстера")))
        assertTrue(exists(hasTestTag("smartcam_plantThumb_p-2")), "each row has a thumbnail")
        click("smartcam_plant_p-2")
        waitFor(tagged("diagnosis_saveJournal", l.save))
        assertTrue(exists(tagged("smartcam_chosenPlant", l.chosen)), "[$locale] ${texts()}")
        assertTrue(!exists(hasTestTag("smartcam_pickPlantTitle")), "the picker closed")
        assertTrue(!exists(hasTestTag("smartcam_isMine")), "the save button replaces «Это моё растение?»")

        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveJournal", l.saved))
        click("diagnosis_saveJournal")
        waitForIdle()
        val (plantId, uri, caption) = saves.single()
        assertEquals("p-2", plantId)
        assertTrue(uri.startsWith("data:image/jpeg;base64,"))
        assertTrue(caption.startsWith(l.captionPrefix + ": Overwatering ("), caption)
        assertTrue(caption.length <= 300)
        assertEquals(1, calls, "no second diagnosis request")
        assertTrue(!exists(hasTestTag("smartcam_isMine")), "the plant cannot change after saving")
        assertNoCounter()
    }

    @Test
    fun ru_street_plant_result_is_saved_to_the_chosen_plant_once() = streetPlantSavedToChosenPlant(
        "ru",
        Labels(
            notInList = "Растение не из вашего списка, поэтому результат не сохранён.",
            isMine = "Это моё растение?",
            pickTitle = "Какое из ваших растений?",
            chosen = "Сохраним в журнал растения «Монстера»",
            save = "Сохранить в журнал растения",
            saved = "Сохранено в журнал",
            captionPrefix = "Что с растением?",
        ),
    )

    @Test
    fun en_street_plant_result_is_saved_to_the_chosen_plant_once() = streetPlantSavedToChosenPlant(
        "en",
        Labels(
            notInList = "This plant is not from your list, so the result is not saved.",
            isMine = "Is this my plant?",
            pickTitle = "Which of your plants?",
            chosen = "Will be saved to the journal of “Монстера”",
            save = "Save to plant journal",
            saved = "Saved to journal",
            captionPrefix = "Plant check",
        ),
    )

    @Test
    fun without_a_journal_there_is_no_is_mine_button() = runDesktopComposeUiTest {
        show("ru", SmartCameraMode.Disease, api(HttpStatusCode.OK, okBody), journal = null)
        click("smartcam_fallbackGallery")
        click("smartcam_send")
        waitFor(hasTestTag("diagnosis_result"))
        assertTrue(exists(hasTestTag("smartcam_notInList")))
        assertTrue(!exists(hasTestTag("smartcam_isMine")))
        assertTrue(!exists(hasTestTag("diagnosis_saveJournal")))
    }

    private fun assertLimit(locale: String, mode: SmartCameraMode, status: HttpStatusCode, body: String, message: String) =
        runDesktopComposeUiTest {
            var closed = 0
            show(locale, mode, api(status, body), onClose = { closed++ })
            click("smartcam_fallbackGallery")
        click("smartcam_send")
            waitFor(tagged("smartcam_message", message))
            println("[$locale ${mode.wire}] $body -> $message")
            assertTrue(!exists(hasTestTag("smartcam_loading")), "loading has ended")
            assertTrue(exists(hasTestTag("smartcam_retake")), "a way to try another photo")
            assertTrue(!exists(hasTestTag("smartcam_backToPhoto")), "a used-up limit offers no immediate retry")
            assertNoCounter()
            click("smartcam_back")
            assertEquals(1, closed, "a way back")
        }

    private val userLimit = """{"error":"Daily diagnosis limit reached","code":"daily_limit","scope":"user"}"""
    private val globalLimit = """{"error":"Daily diagnosis limit reached","code":"daily_limit","scope":"global"}"""
    private val identifyQuota = """{"error":"Daily identification limit reached","code":"quota_exceeded"}"""

    @Test
    fun ru_disease_user_limit() = assertLimit(
        "ru", SmartCameraMode.Disease, HttpStatusCode.TooManyRequests, userLimit, "Дневной лимит диагностик исчерпан. Попробуйте завтра.",
    )

    @Test
    fun en_disease_user_limit() = assertLimit(
        "en", SmartCameraMode.Disease, HttpStatusCode.TooManyRequests, userLimit, "Daily diagnosis limit reached. Try again tomorrow.",
    )

    @Test
    fun ru_disease_global_limit() = assertLimit(
        "ru", SmartCameraMode.Disease, HttpStatusCode.TooManyRequests, globalLimit, "Сегодня проверки у сервиса закончились. Попробуйте завтра.",
    )

    @Test
    fun en_disease_global_limit() = assertLimit(
        "en", SmartCameraMode.Disease, HttpStatusCode.TooManyRequests, globalLimit, "The service has used up today's checks. Try again tomorrow.",
    )

    @Test
    fun ru_plant_identify_quota() = assertLimit(
        "ru", SmartCameraMode.Plant, HttpStatusCode.TooManyRequests, identifyQuota, "Лимит распознаваний на сегодня исчерпан",
    )

    @Test
    fun en_plant_identify_quota() = assertLimit(
        "en", SmartCameraMode.Plant, HttpStatusCode.TooManyRequests, identifyQuota, "Daily identification limit reached",
    )

    private fun identifyUnavailableReturnsToThePhoto(locale: String, message: String, back: String) = runDesktopComposeUiTest {
        val api = api(HttpStatusCode.ServiceUnavailable, """{"error":"Identification is unavailable"}""")
        show(locale, SmartCameraMode.Plant, api)
        click("smartcam_fallbackGallery")
        click("smartcam_send")
        waitFor(tagged("smartcam_message", message))
        assertTrue(exists(tagged("smartcam_backToPhoto", back)), "[$locale] ${texts()}")
        assertTrue(exists(hasTestTag("smartcam_retake")))
        click("smartcam_backToPhoto")
        waitFor(hasTestTag("smartcam_aftershot"))
        waitForIdle()
        assertTrue(exists(hasTestTag("smartcam_photo")), "[$locale] the same photo is shown")
        assertEquals(1, calls, "[$locale] going back sends nothing")
        click("smartcam_send")
        waitFor(tagged("smartcam_message", message))
        assertEquals(2, calls, "[$locale] one more request, only on the explicit tap")
        assertNoCounter()
    }

    @Test
    fun ru_plant_identify_unavailable_returns_to_the_photo() =
        identifyUnavailableReturnsToThePhoto("ru", "Распознавание недоступно", "Вернуться к фото")

    @Test
    fun en_plant_identify_unavailable_returns_to_the_photo() =
        identifyUnavailableReturnsToThePhoto("en", "Identification is unavailable", "Back to photo")

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
