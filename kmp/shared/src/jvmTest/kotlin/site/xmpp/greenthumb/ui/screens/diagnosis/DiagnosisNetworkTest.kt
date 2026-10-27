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
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DIAGNOSE_TIMEOUT_MILLIS
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.resizeJpeg

/**
 * VAL-DIAG-017/018/020 and the temp-file part of VAL-DIAG-024: the diagnosis
 * controller over the real [ApiClient] stack with a MockEngine. The engine runs
 * on the test dispatcher, so the 60 s and 75 s waits are virtual time.
 */
class DiagnosisNetworkTest {

    private class Seen {
        val bodies = mutableListOf<String>()
        var method = ""
        var path = ""
    }

    private fun TestScope.api(seen: Seen = Seen(), handler: MockRequestHandler): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    seen.method = request.method.value
                    seen.path = request.url.encodedPath
                    (request.body as? TextContent)?.text?.let { seen.bodies += it }
                    handler(request)
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private val okBody = """{"causes":[
        {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Reduce watering by 20%."},
        {"title":"Low light","likelihood":"medium","explanation":"Pale leaves.","advice":"Move closer to a window."}
    ],"summary":"Most likely too much water."}"""

    private fun json(status: HttpStatusCode, body: String): MockRequestHandler = {
        respond(body, status, headersOf("Content-Type", "application/json"))
    }

    private val photoBytes = byteArrayOf(1, 2, 3, 4)

    private fun controller(
        diagnoser: PlantDiagnoser,
        pick: suspend (PickSource) -> PickResult = { PickResult.Picked(photoBytes) },
        deleted: MutableList<String> = mutableListOf(),
    ) = DiagnosisController(
        plantId = "p-1",
        diagnoser = diagnoser,
        pick = pick,
        prepare = { it },
        deleteCapture = { deleted += it },
    )

    private fun body(seen: Seen, index: Int = 0): JsonObject = Json.parseToJsonElement(seen.bodies[index]).jsonObject

    @Test
    fun posts_photo_note_and_language_through_core_network() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, json(HttpStatusCode.OK, okBody))
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.updateNote("  Yellow leaves  ")
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals("POST", seen.method)
        assertEquals("/api/plants/p-1/diagnose", seen.path)
        val sent = body(seen)
        assertEquals("data:image/jpeg;base64," + Base64.encode(photoBytes), sent["photo"]!!.jsonPrimitive.content)
        assertEquals("Yellow leaves", sent["note"]!!.jsonPrimitive.content)
        assertEquals("ru", sent["language"]!!.jsonPrimitive.content)
        assertEquals(setOf("photo", "note", "language"), sent.keys)
        val result = assertIs<DiagnosisPhase.Result>(c.phase).result
        assertEquals(listOf("Overwatering", "Low light"), result.causes.map { it.title })
        assertEquals(listOf(Likelihood.High, Likelihood.Medium), result.causes.map { it.likelihood })
        assertEquals("Reduce watering by 20%.", result.causes[0].advice)
        assertEquals("Most likely too much water.", result.summary)
        client.close()
    }

    @Test
    fun blank_note_is_omitted_and_english_ui_sends_en() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, json(HttpStatusCode.OK, okBody))
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Camera)
        advanceUntilIdle()
        c.updateNote("   ")
        c.submit(this, DiagnosisLogic.wireLanguage("en-US"))
        advanceUntilIdle()
        val sent = body(seen)
        assertEquals("en", sent["language"]!!.jsonPrimitive.content)
        assertFalse("note" in sent, "blank note is not sent: $sent")
        assertEquals("ru", DiagnosisLogic.wireLanguage("ru"))
        assertEquals("en", DiagnosisLogic.wireLanguage("de"))
        client.close()
    }

    @Test
    fun note_is_capped_at_500_characters() {
        val c = controller(PlantDiagnoser { _, _, _, _ -> error("not used") })
        c.updateNote("я".repeat(600))
        assertEquals(DiagnosisLogic.MAX_NOTE_LENGTH, c.note.length)
        assertEquals(500, DiagnosisLogic.MAX_NOTE_LENGTH)
        // A surrogate pair is never cut in half at the limit.
        val emoji = "a".repeat(499) + "\uD83C\uDF31" + "b"
        val clamped = DiagnosisLogic.clampNote(emoji)
        assertEquals(499, clamped.length)
        assertTrue(clamped.codePoints().count() <= 500)
    }

    @Test
    fun submit_needs_a_photo() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, json(HttpStatusCode.OK, okBody))
        val c = controller(ApiPlantDiagnoser(api))
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals(DiagnosisPhase.Input, c.phase)
        assertTrue(seen.bodies.isEmpty())
        client.close()
    }

    @Test
    fun response_after_60_seconds_succeeds() = runTest {
        val (api, client) = api(handler = {
            delay(60_000)
            respond(okBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        val start = testScheduler.currentTime
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals(60_000, testScheduler.currentTime - start)
        assertIs<DiagnosisPhase.Result>(c.phase)
        client.close()
    }

    @Test
    fun hanging_response_ends_with_the_network_error_at_75_seconds() = runTest(timeout = 10.seconds) {
        val (api, client) = api(handler = {
            delay(600_000)
            respond(okBody, HttpStatusCode.OK)
        })
        val start = testScheduler.currentTime
        assertFailsWith<ApiError.Timeout> { api.diagnosePlant("p-1", "data:image/jpeg;base64,AA==", null, "ru") }
        assertEquals(DIAGNOSE_TIMEOUT_MILLIS, testScheduler.currentTime - start)
        assertEquals(75_000L, DIAGNOSE_TIMEOUT_MILLIS)

        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals(DiagnosisPhase.Input, c.phase)
        assertEquals(DiagnosisError.Network, c.error)
        client.close()
    }

    @Test
    fun other_calls_keep_the_10_second_default() = runTest(timeout = 10.seconds) {
        val (api, client) = api(handler = {
            delay(20_000)
            respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val start = testScheduler.currentTime
        assertFailsWith<ApiError.Timeout> { api.getPlants() }
        assertEquals(10_000, testScheduler.currentTime - start)
        client.close()
    }

    @Test
    fun errors_map_to_unavailable_daily_limit_network_and_failed() = runTest {
        val offline: MockRequestHandler = { throw java.io.IOException("no route") }
        val cases = listOf(
            json(HttpStatusCode.ServiceUnavailable, """{"error":"Diagnosis is unavailable","code":"diagnosis_unavailable"}""") to DiagnosisError.Unavailable,
            json(HttpStatusCode.TooManyRequests, """{"error":"Daily diagnosis limit reached","code":"daily_limit"}""") to DiagnosisError.DailyLimit,
            json(HttpStatusCode.InternalServerError, """{"error":"boom"}""") to DiagnosisError.Unavailable,
            json(HttpStatusCode.BadRequest, """{"error":"Invalid photo"}""") to DiagnosisError.Failed,
            json(HttpStatusCode.OK, """{"causes":[],"summary":"x"}""") to DiagnosisError.Unavailable,
            offline to DiagnosisError.Network,
        )
        for ((handler, expected) in cases) {
            val (api, client) = api(handler = handler)
            val c = controller(ApiPlantDiagnoser(api))
            c.pickPhoto(this, PickSource.Gallery)
            advanceUntilIdle()
            c.submit(this, "en")
            advanceUntilIdle()
            assertEquals(DiagnosisPhase.Input, c.phase, "$expected ends loading")
            assertEquals(expected, c.error)
            client.close()
        }
    }

    @Test
    fun retry_after_a_network_failure_sends_the_same_input() = runTest {
        val seen = Seen()
        var calls = 0
        val (api, client) = api(seen) {
            calls++
            if (calls == 1) throw java.io.IOException("airplane mode")
            respond(okBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.updateNote("Spots")
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals(DiagnosisError.Network, c.error)
        assertTrue(c.canRetry)
        c.retry(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
        assertNull(c.error)
        assertEquals(2, seen.bodies.size)
        assertEquals(seen.bodies[0], seen.bodies[1])
        client.close()
    }

    @Test
    fun cancel_returns_to_input_and_a_late_response_is_not_shown() = runTest {
        val (api, client) = api(handler = {
            delay(30_000)
            respond(okBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.updateNote("Keep me")
        c.submit(this, "ru")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DiagnosisPhase.Loading, c.phase)
        c.cancel()
        assertEquals(DiagnosisPhase.Input, c.phase)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(DiagnosisPhase.Input, c.phase, "late response is not shown")
        assertNull(c.error)
        assertEquals("Keep me", c.note)
        assertTrue(c.hasPhoto)
        client.close()
    }

    @Test
    fun cancel_ignores_a_result_that_arrives_after_cancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val result = DiagnosisResponse(listOf(DiagnosisCauseDto("Late", "high", "e", "a")), "late")
        val c = controller(PlantDiagnoser { _, _, _, _ ->
            // A transport that does not observe cancellation still completes later.
            withContext(NonCancellable) { gate.await() }
            result
        })
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        runCurrent()
        c.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DiagnosisPhase.Input, c.phase)
        // A new submit after cancel works.
        c.submit(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
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
    fun camera_capture_is_deleted_on_replace_and_on_close() = runTest {
        val dir = File.createTempFile("gt-diag-test", "").also { it.delete(); it.mkdirs() }
        val first = File(dir, "gt-diag-1.jpg").also { it.writeBytes(photoBytes) }
        val second = File(dir, "gt-diag-2.jpg").also { it.writeBytes(photoBytes) }
        val captures = ArrayDeque(listOf(first, second))
        val deleted = mutableListOf<String>()
        val c = DiagnosisController(
            plantId = "p-1",
            diagnoser = PlantDiagnoser { _, _, _, _ -> error("not used") },
            pick = { PickResult.Picked(photoBytes, capturePath = captures.removeFirst().absolutePath) },
            prepare = { it },
            deleteCapture = { path -> deleted += path; File(path).delete() },
        )
        c.pickPhoto(this, PickSource.Camera)
        advanceUntilIdle()
        assertTrue(first.exists(), "capture stays while the screen is open")
        c.pickPhoto(this, PickSource.Camera)
        advanceUntilIdle()
        assertFalse(first.exists(), "replaced capture is deleted")
        assertTrue(second.exists())
        c.close()
        assertFalse(second.exists(), "capture is deleted when the screen closes")
        assertEquals(listOf(first.absolutePath, second.absolutePath), deleted)
        assertFalse(c.hasPhoto)
        dir.deleteRecursively()
    }

    @Test
    fun close_cancels_a_running_request() = runTest {
        val (api, client) = api(handler = {
            delay(30_000)
            respond(okBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val c = controller(ApiPlantDiagnoser(api))
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        runCurrent()
        c.close()
        advanceUntilIdle()
        assertEquals(DiagnosisPhase.Input, c.phase)
        client.close()
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
        val decoded = Base64.decode(DiagnosisLogic.photoDataUrl(prepared).removePrefix("data:image/jpeg;base64,"))
        assertTrue(raw.size > 5_000_000, "fixture is large: ${raw.size}")
        assertTrue(decoded.size <= 5 * 1024 * 1024, "sent photo ${decoded.size} bytes")
        val back = ImageIO.read(decoded.inputStream())
        assertEquals(DiagnosisLogic.PHOTO_MAX_SIDE, maxOf(back.width, back.height))
    }
}
