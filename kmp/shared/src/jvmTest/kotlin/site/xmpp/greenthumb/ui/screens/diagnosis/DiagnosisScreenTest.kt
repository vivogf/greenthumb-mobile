@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.diagnosis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDeleteErrorState
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDetailScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-DIAG-018/019/022: what the diagnosis screen shows in Russian and English
 * for a result, 503, 429, network and timeout failures, and cancel; the
 * plant-detail entry opens it.
 */
@OptIn(ExperimentalTestApi::class)
class DiagnosisScreenTest {
    private val originalLocale: Locale = Locale.getDefault()
    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        // The jvm LocalAppLocale sets the process default locale.
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val jpeg: ByteArray = run {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, 0x2E7D32 + x * 2)
        ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private val okBody = """{"causes":[
        {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Reduce watering by 20%."},
        {"title":"Low light","likelihood":"medium","explanation":"Pale leaves.","advice":"Move closer to a window."},
        {"title":"Pests","likelihood":"low","explanation":"No webs seen.","advice":"Check the leaf undersides."}
    ],"summary":"Most likely too much water."}"""

    private fun apiDiagnoser(handler: MockRequestHandler): PlantDiagnoser {
        val client = ApiClient(MockEngine(handler), NoSessionRecoveryProvider)
        val previous = cleanup
        cleanup = { previous?.invoke(); client.close() }
        return ApiPlantDiagnoser(GreenThumbApi(client))
    }

    private fun status(code: HttpStatusCode, body: String): PlantDiagnoser =
        apiDiagnoser { respond(body, code, jsonHeaders) }

    private fun ComposeUiTest.show(locale: String, diagnoser: PlantDiagnoser, onBack: () -> Unit = {}) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    DiagnosisScreen(
                        plantId = "p-1",
                        diagnoser = diagnoser,
                        onBack = onBack,
                        pick = { PickResult.Picked(jpeg) },
                        deleteCapture = {},
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher): Boolean =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.click(tag: String) {
        waitFor(hasTestTag(tag))
        onNode(hasTestTag(tag), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tagged(tag: String, text: String) = hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))

    /** Picks the photo from the gallery, waits for the preview and submits. */
    private fun ComposeUiTest.pickAndSubmit(note: String? = null) {
        click("diagnosis_gallery")
        waitFor(hasTestTag("diagnosis_photo"))
        if (note != null) onNode(hasTestTag("diagnosis_note")).performTextInput(note)
        click("diagnosis_submit")
    }

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        }

    private fun ComposeUiTest.assertSettled() {
        assertTrue(!exists(hasTestTag("diagnosis_loading")), "loading has ended")
        assertTrue(texts().none { it.contains("переустанов", ignoreCase = true) || it.contains("reinstall", ignoreCase = true) })
    }

    private fun assertErrorState(
        locale: String,
        diagnoser: PlantDiagnoser,
        message: String,
        retry: Boolean,
    ) = runDesktopComposeUiTest {
        var backs = 0
        show(locale, diagnoser) { backs++ }
        pickAndSubmit()
        waitFor(tagged("diagnosis_error", message))
        assertSettled()
        assertEquals(retry, exists(hasTestTag("diagnosis_retry")), "retry button for '$message'")
        click("diagnosis_errorBack")
        assertEquals(1, backs, "the error state leads back to plant detail")
    }

    private val unavailable503 = """{"error":"Diagnosis is unavailable","code":"diagnosis_unavailable"}"""
    private val limit429 = """{"error":"Daily diagnosis limit reached","code":"daily_limit"}"""

    @Test
    fun ru_503_shows_temporarily_unavailable() =
        assertErrorState("ru", status(HttpStatusCode.ServiceUnavailable, unavailable503), "Диагностика временно недоступна", retry = true)

    @Test
    fun en_503_shows_temporarily_unavailable() =
        assertErrorState("en", status(HttpStatusCode.ServiceUnavailable, unavailable503), "Diagnosis is temporarily unavailable", retry = true)

    @Test
    fun ru_429_shows_the_daily_limit() =
        assertErrorState("ru", status(HttpStatusCode.TooManyRequests, limit429), "Дневной лимит диагностик исчерпан. Попробуйте завтра.", retry = false)

    @Test
    fun en_429_shows_the_daily_limit() =
        assertErrorState("en", status(HttpStatusCode.TooManyRequests, limit429), "Daily diagnosis limit reached. Try again tomorrow.", retry = false)

    @Test
    fun ru_timeout_shows_the_network_error_with_retry() =
        assertErrorState("ru", PlantDiagnoser { _, _, _, _ -> throw ApiError.Timeout }, "Нет связи с сервером. Проверьте интернет и повторите.", retry = true)

    @Test
    fun en_timeout_shows_the_network_error_with_retry() =
        assertErrorState("en", PlantDiagnoser { _, _, _, _ -> throw ApiError.Timeout }, "Could not reach the server. Check your connection and try again.", retry = true)

    @Test
    fun network_error_then_retry_shows_the_result_for_the_same_input() = runDesktopComposeUiTest {
        val bodies = mutableListOf<String>()
        var calls = 0
        val diagnoser = apiDiagnoser { request ->
            bodies += (request.body as io.ktor.http.content.TextContent).text
            calls++
            if (calls == 1) throw java.io.IOException("airplane mode")
            respond(okBody, HttpStatusCode.OK, jsonHeaders)
        }
        show("ru", diagnoser)
        pickAndSubmit(note = "Жёлтые листья")
        waitFor(tagged("diagnosis_error", "Нет связи с сервером. Проверьте интернет и повторите."))
        assertSettled()
        click("diagnosis_retry")
        waitFor(hasTestTag("diagnosis_result"))
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1], "retry sends the same photo and note")
    }

    private fun assertResult(locale: String, prefix: String, high: String, medium: String, low: String, disclaimer: String) =
        runDesktopComposeUiTest {
            show(locale, status(HttpStatusCode.OK, okBody))
            pickAndSubmit()
            waitFor(hasTestTag("diagnosis_result"))
            assertTrue(exists(tagged("diagnosis_likelihood_0", "$prefix$high")))
            assertTrue(exists(tagged("diagnosis_likelihood_1", "$prefix$medium")))
            assertTrue(exists(tagged("diagnosis_likelihood_2", "$prefix$low")))
            assertTrue(exists(tagged("diagnosis_disclaimer", disclaimer)))
            assertTrue(exists(hasText("Reduce watering by 20%.")), "advice text is shown as sent")
            assertTrue(exists(hasText("Most likely too much water.")))
            // No likelihood is a number: the only % is inside the advice text.
            val numeric = texts().filter { it.contains('%') && it != "Reduce watering by 20%." }
            assertTrue(numeric.isEmpty(), "numeric likelihood shown: $numeric")
            assertSettled()
        }

    @Test
    fun ru_result_uses_likelihood_words_and_disclaimer() = assertResult(
        "ru", "Вероятность: ", high = "высокая", medium = "средняя", low = "низкая",
        disclaimer = "Это подсказка, а не диагноз. Если растению становится хуже, обратитесь к специалисту.",
    )

    @Test
    fun en_result_uses_likelihood_words_and_disclaimer() = assertResult(
        "en", "Likelihood: ", high = "high", medium = "medium", low = "low",
        disclaimer = "This is a hint, not a verdict. If the plant keeps getting worse, ask a specialist.",
    )

    @Test
    fun cancel_returns_to_input_and_a_late_answer_is_not_shown() = runDesktopComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val diagnoser = PlantDiagnoser { _, _, _, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
            DiagnosisResponse(listOf(DiagnosisCauseDto("Late", "high", "e", "a")), "late")
        }
        show("en", diagnoser)
        pickAndSubmit(note = "keep")
        waitFor(hasTestTag("diagnosis_loading"))
        assertTrue(exists(tagged("diagnosis_cancel", "Cancel diagnosis")), "cancel is visible while loading")
        click("diagnosis_cancel")
        waitFor(hasTestTag("diagnosis_submit"))
        gate.complete(Unit)
        waitForIdle()
        Thread.sleep(200)
        waitForIdle()
        assertTrue(!exists(hasTestTag("diagnosis_result")), "late answer is not shown")
        assertTrue(exists(hasTestTag("diagnosis_photo")), "photo is kept")
        assertTrue(exists(hasText("keep")), "note is kept")
    }

    @Test
    fun interactive_elements_have_labels_in_both_languages() {
        for ((locale, labels) in listOf(
            "ru" to listOf("Назад", "Сделать фото", "Выбрать из галереи", "Диагностировать"),
            "en" to listOf("Back", "Take photo", "Choose from gallery", "Diagnose"),
        )) {
            runDesktopComposeUiTest {
                show(locale, status(HttpStatusCode.OK, okBody))
                waitFor(hasTestTag("diagnosis_submit"))
                val all = texts()
                labels.forEach { assertTrue(it in all, "[$locale] label '$it' in $all") }
                assertTrue(exists(hasContentDescription(labels[0]) and hasClickAction()))
            }
        }
    }

    @Test
    fun likelihood_words_map_low_medium_high_in_both_languages() {
        val expected = mapOf(
            "ru" to listOf("низкая", "средняя", "высокая"),
            "en" to listOf("low", "medium", "high"),
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
    fun plant_detail_entry_opens_diagnosis() {
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
        for ((locale, label) in listOf("ru" to "Диагностика", "en" to "Diagnosis")) {
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
                onNode(hasTestTag("plantDetail_diagnosis")).performSemanticsAction(SemanticsActions.OnClick)
                waitForIdle()
                assertEquals(1, opened, locale)
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
