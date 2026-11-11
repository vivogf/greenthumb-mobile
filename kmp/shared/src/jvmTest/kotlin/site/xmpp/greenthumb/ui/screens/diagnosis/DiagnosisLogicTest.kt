package site.xmpp.greenthumb.ui.screens.diagnosis

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DIAGNOSE_TIMEOUT_MILLIS
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.core.platform.resizeJpeg

/**
 * VAL-DIAG-017/018/020, VAL-CARD-016/018 without UI: [DiagnosisLogic] (note, language, causes, error mapping,
 * photo size, plant cover) and [ApiPlantDiagnoser] over the real [ApiClient] stack with a MockEngine on the test
 * dispatcher, so the 75 s and 10 s deadlines are virtual time. The plant check itself runs through the smart
 * camera (`smartcam/SmartCameraPlantDiagnosisTest`).
 */
class DiagnosisLogicTest {

    private class Seen {
        val requests = mutableListOf<Pair<String, String>>()
        val bodies = mutableListOf<String>()
    }

    private fun TestScope.api(seen: Seen = Seen(), handler: MockRequestHandler): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    seen.requests += request.method.value to request.url.encodedPath
                    (request.body as? TextContent)?.text?.let { seen.bodies += it }
                    handler(request)
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private val json = headersOf("Content-Type", "application/json")

    private val okBody = """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"e","advice":"a"}],"summary":"s"}"""

    @Test
    fun the_plant_diagnoser_posts_photo_note_and_language_to_the_plant_route() = runTest {
        val seen = Seen()
        val (api, client) = api(seen) { respond(okBody, HttpStatusCode.OK, json) }
        val answer = ApiPlantDiagnoser(api).diagnose("p-1", photoDataUri(byteArrayOf(1, 2, 3)), "Spots", "ru")
        assertEquals(listOf("POST" to "/api/plants/p-1/diagnose"), seen.requests)
        val sent = Json.parseToJsonElement(seen.bodies.single()).jsonObject
        assertEquals(setOf("photo", "note", "language"), sent.keys)
        assertEquals(photoDataUri(byteArrayOf(1, 2, 3)), sent["photo"]!!.jsonPrimitive.content)
        assertEquals("Spots", sent["note"]!!.jsonPrimitive.content)
        assertEquals("ru", sent["language"]!!.jsonPrimitive.content)
        assertEquals(listOf("Overwatering"), answer.causes.map { it.title })
        client.close()
    }

    @Test
    fun wire_language_is_ru_for_russian_and_en_otherwise() {
        assertEquals("ru", DiagnosisLogic.wireLanguage("ru"))
        assertEquals("ru", DiagnosisLogic.wireLanguage("ru-RU"))
        assertEquals("en", DiagnosisLogic.wireLanguage("en-US"))
        assertEquals("en", DiagnosisLogic.wireLanguage("de"))
    }

    @Test
    fun note_is_trimmed_omitted_when_blank_and_capped_at_500_characters() {
        assertEquals(500, DiagnosisLogic.MAX_NOTE_LENGTH)
        assertEquals("Yellow leaves", DiagnosisLogic.wireNote("  Yellow leaves  "))
        assertNull(DiagnosisLogic.wireNote("   "))
        assertEquals(DiagnosisLogic.MAX_NOTE_LENGTH, DiagnosisLogic.clampNote("я".repeat(600)).length)
        // A surrogate pair is never cut in half at the limit.
        val clamped = DiagnosisLogic.clampNote("a".repeat(499) + "\uD83C\uDF31" + "b")
        assertEquals(499, clamped.length)
        assertTrue(clamped.codePoints().count() <= 500)
    }

    @Test
    fun result_keeps_at_most_three_known_causes() {
        val dto = DiagnosisResponse(
            causes = listOf(
                DiagnosisCauseDto("A", "low", "e", "a"),
                DiagnosisCauseDto("B", "very high", "e", "a"),
                DiagnosisCauseDto("C", "MEDIUM", "e", "a"),
                DiagnosisCauseDto("D", "high", "e", "a"),
                DiagnosisCauseDto("E", "high", "e", "a"),
            ),
            summary = "s",
        )
        val result = DiagnosisLogic.toResult(dto)!!
        assertEquals(listOf("A", "C", "D"), result.causes.map { it.title })
        assertEquals(listOf(Likelihood.Low, Likelihood.Medium, Likelihood.High), result.causes.map { it.likelihood })
        assertNull(DiagnosisLogic.toResult(DiagnosisResponse(listOf(DiagnosisCauseDto("X", "0.8", "e", "a")), "s")))
    }

    @Test
    fun the_diagnose_call_has_a_75_second_deadline() = runTest(timeout = 10.seconds) {
        val (api, client) = api {
            delay(600_000)
            respond(okBody, HttpStatusCode.OK, json)
        }
        val start = testScheduler.currentTime
        assertFailsWith<ApiError.Timeout> { api.diagnosePlant("p-1", "data:image/jpeg;base64,AA==", null, "ru") }
        assertEquals(DIAGNOSE_TIMEOUT_MILLIS, testScheduler.currentTime - start)
        assertEquals(75_000L, DIAGNOSE_TIMEOUT_MILLIS)
        client.close()
    }

    @Test
    fun other_calls_keep_the_10_second_default() = runTest(timeout = 10.seconds) {
        val (api, client) = api {
            delay(20_000)
            respond("[]", HttpStatusCode.OK, json)
        }
        val start = testScheduler.currentTime
        assertFailsWith<ApiError.Timeout> { api.getPlants() }
        assertEquals(10_000, testScheduler.currentTime - start)
        client.close()
    }

    @Test
    fun errors_map_to_unavailable_limits_network_and_failed() {
        assertEquals(DiagnosisError.Unavailable, DiagnosisLogic.errorFor(ApiError.Server(503, """{"code":"diagnosis_unavailable"}""")))
        assertEquals(DiagnosisError.Unavailable, DiagnosisLogic.errorFor(ApiError.Server(500, "boom")))
        assertEquals(DiagnosisError.Network, DiagnosisLogic.errorFor(ApiError.Network))
        assertEquals(DiagnosisError.Network, DiagnosisLogic.errorFor(ApiError.Timeout))
        assertEquals(DiagnosisError.Failed, DiagnosisLogic.errorFor(ApiError.Client(400, """{"error":"Invalid photo"}""")))
        assertEquals(DiagnosisError.Unavailable, DiagnosisLogic.errorFor(IllegalStateException("bad answer")))
    }

    // A photo with no plant is a verdict about the photo (422 no_plant), not a service outage: the screen asks for
    // another photo instead of blaming the service. Any other 4xx keeps the old "could not read the photo" answer.
    @Test
    fun no_plant_answer_maps_to_its_own_state() {
        assertEquals(DiagnosisError.NoPlant, DiagnosisLogic.errorFor(ApiError.Client(422, """{"error":"No plant or disease signs recognised","code":"no_plant"}""")))
        assertEquals(DiagnosisError.NoPlant, DiagnosisLogic.errorFor(ApiError.Client(422, """{"code":"no_plant"}""")))
        assertEquals(DiagnosisError.Failed, DiagnosisLogic.errorFor(ApiError.Client(422, """{"code":"validation"}""")))
        assertEquals(DiagnosisError.Failed, DiagnosisLogic.errorFor(ApiError.Client(422, "not json")))
        assertEquals(DiagnosisError.Unavailable, DiagnosisLogic.errorFor(ApiError.Server(503, """{"code":"diagnosis_unavailable"}""")))
    }

    @Test
    fun limit_scope_picks_the_message() {
        assertEquals(
            DiagnosisError.DailyLimit,
            DiagnosisLogic.errorFor(ApiError.Client(429, """{"error":"limit","code":"daily_limit","scope":"user"}""")),
        )
        assertEquals(
            DiagnosisError.ServiceLimit,
            DiagnosisLogic.errorFor(ApiError.Client(429, """{"error":"limit","code":"daily_limit","scope":"global"}""")),
        )
        assertEquals(DiagnosisError.DailyLimit, DiagnosisLogic.errorFor(ApiError.Client(429, """{"code":"daily_limit"}""")))
        assertEquals(DiagnosisError.DailyLimit, DiagnosisLogic.errorFor(ApiError.Client(429, "not json")))
        assertEquals(DiagnosisError.Failed, DiagnosisLogic.errorFor(ApiError.Client(400, """{"scope":"global"}""")))
    }

    @Test
    fun large_camera_photo_is_sent_under_5_mb() {
        val image = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB)
        val random = Random(7)
        for (y in 0 until 3000) for (x in 0 until 4000) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        val raw = out.toByteArray()
        val prepared = DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) }
        val decoded = Base64.decode(photoDataUri(prepared).removePrefix("data:image/jpeg;base64,"))
        assertTrue(raw.size > 5_000_000, "fixture is large: ${raw.size}")
        assertTrue(decoded.size <= DiagnosisLogic.MAX_PHOTO_BYTES, "sent photo ${decoded.size} bytes")
        val back = ImageIO.read(decoded.inputStream())
        assertEquals(DiagnosisLogic.PHOTO_MAX_SIDE, maxOf(back.width, back.height))
    }

    @Test
    fun cover_photo_from_https_goes_through_the_api_client_and_a_data_uri_is_decoded_locally() = runTest {
        val seen = Seen()
        val (api, client) = api(seen) { respond(byteArrayOf(5, 6, 7), HttpStatusCode.OK) }
        val diagnoser = ApiPlantDiagnoser(api)
        val fetched = DiagnosisLogic.coverPhoto("https://greenthumb.xmpp.site/photos/u/p/a.jpg", diagnoser::fetchPhoto)
        assertContentEquals(byteArrayOf(5, 6, 7), fetched)
        assertEquals(listOf("GET" to "/photos/u/p/a.jpg"), seen.requests)
        val decoded = DiagnosisLogic.coverPhoto("data:image/jpeg;base64,AQID") { error("no network for a data URI") }
        assertContentEquals(byteArrayOf(1, 2, 3), decoded)
        assertFailsWith<IllegalArgumentException> { DiagnosisLogic.coverPhoto("  ") { error("no network") } }
        client.close()
    }
}
