@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.likelihoodWord
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDeleteErrorState
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDetailScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-DIAG-018/019/022 and VAL-CARD-012 for the camera bound to a plant, ru and en: the texts of 503, 429 (no
 * scope, personal, global), timeout and network failures with a way to retake and a way back; cancel keeps the
 * photo and the note and hides a late answer; the likelihood words; and the plant-detail entry «Что с растением?».
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraPlantLocaleTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private val originalLocale: Locale = Locale.getDefault()
    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        // The jvm LocalAppLocale sets the process default locale.
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val basil = SmartCameraPlant(id = "p-1", name = "Базилик", photoUrl = "")
    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun apiDiagnoser(handler: MockRequestHandler): PlantDiagnoser {
        val client = ApiClient(MockEngine(handler), NoSessionRecoveryProvider)
        val previous = cleanup
        cleanup = { previous?.invoke(); client.close() }
        return ApiPlantDiagnoser(GreenThumbApi(client))
    }

    private fun status(code: HttpStatusCode, body: String): PlantDiagnoser =
        apiDiagnoser { respond(body, code, jsonHeaders) }

    private fun ComposeUiTest.show(locale: String, plantDiagnoser: PlantDiagnoser, onClose: () -> Unit = {}) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    SmartCameraScreen(
                        initialMode = SmartCameraMode.Disease,
                        identifier = null,
                        diagnoser = StandaloneDiagnoser { _, _, _ -> error("a plant check never goes plant-less") },
                        onClose = onClose,
                        onAddToMine = {},
                        myPlants = listOf(basil),
                        pick = { PickResult.Picked(paintedJpeg(320, 240)) },
                        deleteCapture = {},
                        plant = basil,
                        plantDiagnoser = plantDiagnoser,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.exists(tag: String): Boolean = exists(hasTestTag(tag))

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.click(tag: String) {
        waitFor(hasTestTag(tag))
        onNode(hasTestTag(tag), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tagged(tag: String, text: String) = hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))

    private fun ComposeUiTest.selected(tag: String): Boolean =
        onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    /** Takes the photo through the gallery fallback (the desktop has no camera) and sends it. */
    private fun ComposeUiTest.pickAndSend(note: String? = null) {
        click("smartcam_fallbackGallery")
        waitFor(hasTestTag("smartcam_aftershot"))
        if (note != null) onNode(hasTestTag("smartcam_note")).performTextInput(note)
        click("smartcam_send")
    }

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    /** [backToPhoto] is the label of the return to the same photo after a transient failure; null where none is offered. */
    private fun assertErrorState(locale: String, diagnoser: PlantDiagnoser, message: String, backToPhoto: String? = null) = runDesktopComposeUiTest {
        var closed = 0
        show(locale, diagnoser) { closed++ }
        pickAndSend()
        waitFor(tagged("smartcam_message", message))
        assertTrue(!exists("smartcam_loading"), "loading has ended")
        if (backToPhoto != null) {
            assertTrue(exists(tagged("smartcam_backToPhoto", backToPhoto)), "[$locale] the same photo can be sent again: ${texts()}")
        } else {
            assertTrue(!exists("smartcam_backToPhoto"), "[$locale] a limit offers no immediate retry")
        }
        val all = texts()
        assertTrue(all.none { it.contains("переустанов", ignoreCase = true) || it.contains("reinstall", ignoreCase = true) }, "$all")
        assertTrue(all.none { Regex("\\d").containsMatchIn(it) }, "[$locale] no counter: $all")
        click("smartcam_back")
        assertEquals(1, closed, "the failure leads back to plant detail")
        click("smartcam_retake")
        waitFor(hasTestTag("smartcam_viewfinder"))
        assertTrue(selected("smartcam_mode_disease"), "[$locale] retake stays in «Болезнь»")
        assertTrue(exists(tagged("smartcam_plantChip", "Базилик")), "[$locale] retake keeps the plant chip")
    }

    private val unavailable503 = """{"error":"Diagnosis is unavailable","code":"diagnosis_unavailable"}"""
    private val limit429 = """{"error":"Daily diagnosis limit reached","code":"daily_limit"}"""
    private val userLimit429 = """{"error":"Daily diagnosis limit reached","code":"daily_limit","scope":"user"}"""
    private val globalLimit429 = """{"error":"Daily diagnosis limit reached","code":"daily_limit","scope":"global"}"""

    @Test
    fun ru_503_shows_temporarily_unavailable() =
        assertErrorState("ru", status(HttpStatusCode.ServiceUnavailable, unavailable503), "Проверка растения временно недоступна", RU_BACK)

    @Test
    fun en_503_shows_temporarily_unavailable() =
        assertErrorState("en", status(HttpStatusCode.ServiceUnavailable, unavailable503), "Plant check is temporarily unavailable", EN_BACK)

    @Test
    fun ru_429_shows_the_daily_limit() =
        assertErrorState("ru", status(HttpStatusCode.TooManyRequests, limit429), "Дневной лимит диагностик исчерпан. Попробуйте завтра.")

    @Test
    fun en_429_shows_the_daily_limit() =
        assertErrorState("en", status(HttpStatusCode.TooManyRequests, limit429), "Daily diagnosis limit reached. Try again tomorrow.")

    @Test
    fun ru_429_user_scope_shows_the_personal_daily_limit() =
        assertErrorState("ru", status(HttpStatusCode.TooManyRequests, userLimit429), "Дневной лимит диагностик исчерпан. Попробуйте завтра.")

    @Test
    fun en_429_user_scope_shows_the_personal_daily_limit() =
        assertErrorState("en", status(HttpStatusCode.TooManyRequests, userLimit429), "Daily diagnosis limit reached. Try again tomorrow.")

    @Test
    fun ru_429_global_scope_shows_the_service_limit() =
        assertErrorState("ru", status(HttpStatusCode.TooManyRequests, globalLimit429), "Сегодня проверки у сервиса закончились. Попробуйте завтра.")

    @Test
    fun en_429_global_scope_shows_the_service_limit() =
        assertErrorState("en", status(HttpStatusCode.TooManyRequests, globalLimit429), "The service has used up today's checks. Try again tomorrow.")

    @Test
    fun ru_timeout_shows_the_network_error() =
        assertErrorState("ru", PlantDiagnoser { _, _, _, _ -> throw ApiError.Timeout }, "Нет связи с сервером. Проверьте интернет и повторите.", RU_BACK)

    @Test
    fun en_timeout_shows_the_network_error() =
        assertErrorState("en", PlantDiagnoser { _, _, _, _ -> throw ApiError.Timeout }, "Could not reach the server. Check your connection and try again.", EN_BACK)

    @Test
    fun ru_offline_shows_the_network_error() =
        assertErrorState("ru", apiDiagnoser { throw java.io.IOException("airplane mode") }, "Нет связи с сервером. Проверьте интернет и повторите.", RU_BACK)

    @Test
    fun a_transient_failure_returns_to_the_same_photo_note_and_chip_and_only_send_retries() {
        for ((locale, back) in listOf("ru" to RU_BACK, "en" to EN_BACK)) {
            runDesktopComposeUiTest {
                val photos = mutableListOf<String>()
                val notes = mutableListOf<String?>()
                val diagnoser = PlantDiagnoser { id, photo, note, _ ->
                    assertEquals("p-1", id)
                    photos += photo
                    notes += note
                    if (photos.size == 1) throw ApiError.Network
                    DiagnosisResponse(listOf(DiagnosisCauseDto("Overwatering", "high", "Wet soil.", "Water less.")), "Too much water.")
                }
                show(locale, diagnoser)
                pickAndSend(note = "spots")
                waitFor(hasTestTag("smartcam_backToPhoto"))
                assertTrue(exists(tagged("smartcam_backToPhoto", back)), "[$locale] ${texts()}")
                assertTrue(exists("smartcam_retake"), "[$locale] a new photo stays possible")
                click("smartcam_backToPhoto")
                waitFor(hasTestTag("smartcam_aftershot"))
                waitForIdle()
                assertEquals(1, photos.size, "[$locale] going back sends nothing")
                assertTrue(exists("smartcam_photo"), "[$locale] photo is kept")
                assertTrue(exists(hasText("spots")), "[$locale] note is kept")
                assertTrue(exists(tagged("smartcam_plantChip", "Базилик")), "[$locale] chip is kept")
                click("smartcam_send")
                waitFor(hasTestTag("diagnosis_result"))
                assertEquals(2, photos.size, "[$locale] one more request on the explicit tap")
                assertEquals(photos[0], photos[1], "[$locale] the same photo")
                assertEquals(listOf<String?>("spots", "spots"), notes)
            }
        }
    }

    @Test
    fun cancel_returns_to_the_after_shot_screen_and_a_late_answer_is_not_shown() {
        for ((locale, cancel) in listOf("ru" to "Отмена", "en" to "Cancel")) {
            runDesktopComposeUiTest {
                val gate = CompletableDeferred<Unit>()
                val diagnoser = PlantDiagnoser { _, _, _, _ ->
                    withContext(NonCancellable) { gate.await() }
                    DiagnosisResponse(listOf(DiagnosisCauseDto("Late", "high", "e", "a")), "late")
                }
                show(locale, diagnoser)
                pickAndSend(note = "keep")
                waitFor(hasTestTag("smartcam_loading"))
                assertTrue(exists(tagged("smartcam_cancel", cancel)), "[$locale] cancel is visible while loading")
                click("smartcam_cancel")
                waitFor(hasTestTag("smartcam_send"))
                gate.complete(Unit)
                waitForIdle()
                Thread.sleep(200)
                waitForIdle()
                assertTrue(!exists("diagnosis_result"), "[$locale] late answer is not shown")
                assertTrue(exists("smartcam_photo"), "[$locale] photo is kept")
                assertTrue(exists(hasText("keep")), "[$locale] note is kept")
                assertTrue(exists(tagged("smartcam_plantChip", "Базилик")), "[$locale] chip is kept")
            }
        }
    }

    @Test
    fun likelihood_words_map_low_medium_high_in_both_languages() {
        val expected = mapOf(
            "ru" to listOf("Маловероятно", "Возможно", "Скорее всего"),
            "en" to listOf("Unlikely", "Possible", "Most likely"),
        )
        for ((locale, words) in expected) {
            runDesktopComposeUiTest {
                val seen = mutableListOf<String>()
                setContent {
                    AppEnvironment(customAppLocale = locale) {
                        listOf("low", "medium", "high").forEach { wire ->
                            seen += likelihoodWord(DiagnosisLogic.likelihood(wire)!!)
                        }
                    }
                }
                waitForIdle()
                assertEquals(words, seen.takeLast(3), locale)
            }
        }
    }

    @Test
    fun plant_detail_entry_opens_the_plant_camera() {
        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/plants" && request.method == HttpMethod.Get -> respond(
                    """[{"id":"det-1","user_id":"73","name":"Ficus","location":"","photo_url":"",""" +
                        """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
                        """"created_at":"2026-09-01T10:00:00.000Z"}]""",
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
                path == "/api/plants/det-1/photos" -> respond("""{"photos":[]}""", HttpStatusCode.OK, jsonHeaders)
                path == "/api/plants/det-1/events" -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
        val dir = File.createTempFile("gt-diagnosis-entry", "").also { it.delete() }
        assertTrue(dir.mkdirs())
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
        for ((locale, label, line) in listOf(
            Triple("ru", "Что с растением?", "Фото → возможные причины и что делать"),
            Triple("en", "What's wrong with my plant?", "Photo → likely causes and what to do"),
        )) {
            runDesktopComposeUiTest {
                var opened by mutableStateOf(0)
                setContent {
                    AppEnvironment(customAppLocale = locale) {
                        GreenThumbTheme(darkTheme = false) {
                            PlantDetailScreen(
                                userId = "73",
                                plantId = "det-1",
                                opener = PlantRepositoryOpener { repo },
                                onBack = {},
                                onDeleted = {},
                                deleteErrorState = PlantDeleteErrorState(),
                                onOpenDiagnosis = { opened++ },
                            )
                        }
                    }
                }
                waitFor(tagged("plantDetail_diagnosis", label))
                assertTrue(exists(tagged("plantDetail_diagnosis", line)), "[$locale] supporting line")
                val all = texts()
                assertTrue(all.none { it == "Диагностика" || it == "Diagnosis" }, "[$locale] no old entry label: $all")
                onNode(hasTestTag("plantDetail_diagnosis")).performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                assertEquals(1, opened, locale)
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
        const val RU_BACK = "Вернуться к фото"
        const val EN_BACK = "Back to photo"
    }
}
