package site.xmpp.greenthumb.ui.screens.diagnosis

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-CARD-013/014/015/016/018 on screen: the result of «Что с растением?» on its own screen with
 * words and dots, «Сохранить в журнал растения» with its states, the plant route of the save,
 * the cover photo preset and both 429 scopes.
 */
@OptIn(ExperimentalTestApi::class)
class DiagnosisResultViewTest {
    private val originalLocale: Locale = Locale.getDefault()
    private var cleanup: (() -> Unit)? = null

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val jpeg: ByteArray = run {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 48) for (x in 0 until 64) image.setRGB(x, y, 0x2E7D32 + x * 2)
        ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

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

    private fun ComposeUiTest.show(
        locale: String,
        api: GreenThumbApi,
        journal: Boolean = true,
        picks: MutableList<Int> = mutableListOf(),
        initialPhoto: (suspend () -> ByteArray)? = null,
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = true) {
                    DiagnosisScreen(
                        plantId = "p-1",
                        diagnoser = ApiPlantDiagnoser(api),
                        onBack = {},
                        pick = {
                            picks += 1
                            PickResult.Picked(jpeg)
                        },
                        deleteCapture = {},
                        journal = if (journal) DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) } else null,
                        initialPhoto = initialPhoto,
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
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        }

    private fun ComposeUiTest.diagnose() {
        click("diagnosis_gallery")
        waitFor(hasTestTag("diagnosis_photo"))
        click("diagnosis_submit")
        waitFor(hasTestTag("diagnosis_result"))
    }

    private fun okHandler(photoStatus: () -> HttpStatusCode = { HttpStatusCode.Created }): MockRequestHandler = { request ->
        when (request.url.encodedPath) {
            "/api/plants/p-1/diagnose" -> respond(okBody, HttpStatusCode.OK, json)
            "/api/plants/p-1/photos" -> {
                val status = photoStatus()
                respond(if (status.value < 300) created else """{"error":"x"}""", status, json)
            }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun assertResult(
        locale: String,
        words: List<String>,
        whatToDo: String,
        save: String,
        done: String,
        disclaimer: String,
    ) = runDesktopComposeUiTest {
        show(locale, api(Calls(), okHandler()))
        diagnose()
        words.forEachIndexed { index, word ->
            assertTrue(exists(tagged("diagnosis_likelihood_$index", word)), "[$locale] cause $index says '$word'")
            assertEquals(3 - index, count("diagnosis_dotFilled_$index"), "[$locale] filled dots of cause $index")
            assertEquals(index, count("diagnosis_dotEmpty_$index"), "[$locale] empty dots of cause $index")
        }
        assertTrue(exists(hasTestTag("diagnosis_resultPhoto")), "photo thumbnail")
        assertTrue(exists(tagged("diagnosis_whatToDo", whatToDo)))
        assertTrue(exists(hasText("Move closer to a window.")))
        assertTrue(exists(tagged("diagnosis_disclaimer", disclaimer)))
        assertTrue(exists(tagged("diagnosis_saveJournal", save)))
        assertTrue(exists(tagged("diagnosis_done", done)))
        val all = texts()
        assertTrue(all.none { it.contains('%') || it.contains(Regex("\\d")) }, "no number or percentage: $all")
        assertTrue(all.none { it.contains("осталось", ignoreCase = true) || it.contains(" left today") }, "no counter")
        assertTrue(!exists(hasTestTag("diagnosis_submit")), "the input is replaced by the result")
    }

    @Test
    fun ru_result_screen_shows_words_dots_what_to_do_and_save() = assertResult(
        "ru",
        words = listOf("Скорее всего", "Возможно", "Маловероятно"),
        whatToDo = "Что делать",
        save = "Сохранить в журнал растения",
        done = "Готово",
        disclaimer = "Это подсказка, а не диагноз. Если растению становится хуже, обратитесь к специалисту.",
    )

    @Test
    fun en_result_screen_shows_words_dots_what_to_do_and_save() = assertResult(
        "en",
        words = listOf("Most likely", "Possible", "Unlikely"),
        whatToDo = "What to do",
        save = "Save to plant journal",
        done = "Done",
        disclaimer = "This is a hint, not a verdict. If the plant keeps getting worse, ask a specialist.",
    )

    @Test
    fun save_posts_to_this_plant_with_the_caption_and_turns_into_saved() = runDesktopComposeUiTest {
        val calls = Calls()
        show("ru", api(calls, okHandler()))
        diagnose()
        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveJournal", "Сохранено в журнал"))
        onNode(hasTestTag("diagnosis_saveJournal"), useUnmergedTree = true).assert(isNotEnabled())
        waitForIdle()
        assertEquals(listOf("/api/plants/p-1/diagnose", "/api/plants/p-1/photos"), calls.paths.toList())
        val body = Json.parseToJsonElement(calls.photoBodies.single()).jsonObject
        assertEquals(
            "Что с растением?: Overwatering (Скорее всего), Low light (Возможно), Pests (Маловероятно)",
            body["caption"]!!.jsonPrimitive.content,
        )
        assertTrue(body["photo"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun english_caption_uses_the_english_words() = runDesktopComposeUiTest {
        val calls = Calls()
        show("en", api(calls, okHandler()))
        diagnose()
        click("diagnosis_saveJournal")
        waitFor(tagged("diagnosis_saveJournal", "Saved to journal"))
        val body = Json.parseToJsonElement(calls.photoBodies.single()).jsonObject
        assertEquals(
            "Plant check: Overwatering (Most likely), Low light (Possible), Pests (Unlikely)",
            body["caption"]!!.jsonPrimitive.content,
        )
    }

    private fun assertFailureThenRetry(failFirst: MockRequestHandler) = runDesktopComposeUiTest {
        val calls = Calls()
        var photoCalls = 0
        show("ru", api(calls) { request ->
            if (request.url.encodedPath == "/api/plants/p-1/photos" && ++photoCalls == 1) failFirst(request) else okHandler()(request)
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
    fun network_error_on_save_is_shown_and_retry_saves() = assertFailureThenRetry { throw java.io.IOException("airplane mode") }

    @Test
    fun server_error_on_save_is_shown_and_retry_saves() =
        assertFailureThenRetry { respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, json) }

    @Test
    fun no_save_offer_without_a_plant_journal() = runDesktopComposeUiTest {
        show("ru", api(Calls(), okHandler()), journal = false)
        diagnose()
        assertTrue(!exists(hasTestTag("diagnosis_saveJournal")))
        assertTrue(exists(tagged("diagnosis_done", "Готово")))
    }

    @Test
    fun cover_photo_is_preset_without_a_picker() = runDesktopComposeUiTest {
        val calls = Calls()
        val picks = mutableListOf<Int>()
        show("ru", api(calls, okHandler()), picks = picks, initialPhoto = { jpeg })
        waitFor(hasTestTag("diagnosis_photo"))
        assertTrue(picks.isEmpty(), "no picker opened")
        click("diagnosis_submit")
        waitFor(hasTestTag("diagnosis_result"))
        assertEquals(listOf("/api/plants/p-1/diagnose"), calls.paths.toList())
    }

    @Test
    fun failed_cover_download_shows_the_message_and_sends_nothing() {
        for ((locale, message) in listOf(
            "ru" to "Не удалось загрузить фото. Снимите новое.",
            "en" to "Could not load the photo. Take a new one.",
        )) {
            runDesktopComposeUiTest {
                val calls = Calls()
                show(locale, api(calls, okHandler()), initialPhoto = { throw ApiError.Network })
                waitFor(tagged("diagnosis_photoLoadError", message))
                assertTrue(!exists(hasTestTag("diagnosis_photo")))
                assertTrue(exists(hasTestTag("diagnosis_gallery")), "[$locale] the normal input is shown")
                assertTrue(calls.paths.isEmpty(), "no request: ${calls.paths}")
            }
        }
    }

    @Test
    fun limit_messages_for_the_user_and_the_service() {
        val cases = listOf(
            Triple("ru", "user", "Дневной лимит диагностик исчерпан. Попробуйте завтра."),
            Triple("en", "user", "Daily diagnosis limit reached. Try again tomorrow."),
            Triple("ru", "global", "Сегодня проверки у сервиса закончились. Попробуйте завтра."),
            Triple("en", "global", "The service has used up today's checks. Try again tomorrow."),
        )
        for ((locale, scope, message) in cases) {
            runDesktopComposeUiTest {
                show(locale, api(Calls()) {
                    respond("""{"error":"limit","code":"daily_limit","scope":"$scope"}""", HttpStatusCode.TooManyRequests, json)
                })
                click("diagnosis_gallery")
                waitFor(hasTestTag("diagnosis_photo"))
                click("diagnosis_submit")
                waitFor(tagged("diagnosis_error", message))
                assertTrue(!exists(hasTestTag("diagnosis_retry")), "[$locale/$scope] no retry")
                assertTrue(texts().none { Regex("\\d").containsMatchIn(it) }, "[$locale/$scope] no counter")
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
