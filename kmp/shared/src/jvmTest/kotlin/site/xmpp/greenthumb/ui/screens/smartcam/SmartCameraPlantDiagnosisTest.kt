package site.xmpp.greenthumb.ui.screens.smartcam

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import site.xmpp.greenthumb.core.network.DIAGNOSE_TIMEOUT_MILLIS
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
import site.xmpp.greenthumb.ui.screens.diagnosis.Likelihood
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser

/**
 * VAL-DIAG-017/018/020/024 and VAL-CARD-016/018 for a check bound to a plant, over the real [ApiClient] stack with
 * a MockEngine on the test dispatcher (60 s and 75 s waits are virtual time): the answer of
 * `POST /api/plants/<id>/diagnose`, the deadline, every error, cancel and close, the capture file, a journal
 * server error, and the plant cover downloaded through the API client. A failed check is retaken, never resent.
 * The route, the chip and the journal basics are in [SmartCameraPlantRouteTest].
 */
class SmartCameraPlantDiagnosisTest {

    private class Sent(val method: String, val path: String, val body: String)

    private fun TestScope.api(sent: MutableList<Sent> = mutableListOf(), handler: MockRequestHandler = { ok(it.url.encodedPath) }): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    sent += Sent(request.method.value, request.url.encodedPath, (request.body as? TextContent)?.text.orEmpty())
                    handler(request)
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private fun MockRequestHandleScope.ok(path: String) = when (path) {
        "/api/plants/p-1/diagnose" -> respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
        "/api/plants/p-1/photos" -> respond(CREATED_PHOTO, HttpStatusCode.Created, JSON)
        else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON)
    }

    private fun json(status: HttpStatusCode, body: String): MockRequestHandler = { respond(body, status, JSON) }

    private val basil = SmartCameraPlant(id = "p-1", name = "Базилик", photoUrl = "")
    private val photo = byteArrayOf(1, 2, 3, 4)

    private fun controller(
        plantDiagnoser: PlantDiagnoser,
        journal: DiagnosisJournalSaver? = null,
        capture: suspend (Boolean) -> PickResult = { PickResult.Picked(photo) },
        deleteCapture: (String) -> Unit = {},
        initialPhoto: (suspend () -> ByteArray)? = null,
    ) = SmartCameraController(
        initialMode = SmartCameraMode.Disease,
        identifier = null,
        diagnoser = StandaloneDiagnoser { _, _, _ -> error("a plant check never goes plant-less") },
        pick = { PickResult.Picked(photo) },
        preparePlant = { it },
        prepareDisease = { it },
        catalog = { null },
        capture = capture,
        journal = journal,
        deleteCapture = deleteCapture,
        plantDiagnoser = plantDiagnoser,
        plant = basil,
        initialPhoto = initialPhoto,
    )

    private fun TestScope.shoot(c: SmartCameraController) {
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
    }

    private fun body(s: Sent): JsonObject = Json.parseToJsonElement(s.body).jsonObject

    private fun routes(sent: List<Sent>) = sent.map { "${it.method} ${it.path}" }

    @Test
    fun theAnswerOfThePlantRouteBecomesTheResultAndABlankNoteIsOmitted() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent, json(HttpStatusCode.OK, TWO_CAUSES))
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        c.updateNote("   ")
        c.send(this, DiagnosisLogic.wireLanguage("en-US"))
        advanceUntilIdle()
        assertEquals(listOf("POST /api/plants/p-1/diagnose"), routes(sent))
        val json = body(sent.single())
        assertEquals(setOf("photo", "language"), json.keys, "a blank note is not sent")
        assertEquals("en", json["language"]!!.jsonPrimitive.content)
        assertEquals(photoDataUri(photo), json["photo"]!!.jsonPrimitive.content)
        val result = assertIs<SmartCameraPhase.DiseaseResult>(c.phase).result
        assertEquals(listOf("Overwatering", "Low light"), result.causes.map { it.title })
        assertEquals(listOf(Likelihood.High, Likelihood.Medium), result.causes.map { it.likelihood })
        assertEquals("Reduce watering by 20%.", result.causes[0].advice)
        assertEquals("Most likely too much water.", result.summary)
        client.close()
    }

    @Test
    fun theNoteIsCappedAt500CharactersBeforeItIsSent() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        c.updateNote("я".repeat(600))
        assertEquals(DiagnosisLogic.MAX_NOTE_LENGTH, c.note.length)
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals("я".repeat(500), body(sent.single())["note"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun anAnswerAfter60SecondsIsShown() = runTest {
        val (api, client) = api(handler = {
            delay(60_000)
            ok(it.url.encodedPath)
        })
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        val start = testScheduler.currentTime
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(60_000, testScheduler.currentTime - start)
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        client.close()
    }

    @Test
    fun aHangingAnswerEndsWithTheNetworkErrorAt75Seconds() = runTest(timeout = 10.seconds) {
        val (api, client) = api(handler = {
            delay(600_000)
            ok(it.url.encodedPath)
        })
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        val start = testScheduler.currentTime
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(DIAGNOSE_TIMEOUT_MILLIS, testScheduler.currentTime - start)
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Network)), c.phase)
        client.close()
    }

    @Test
    fun everyErrorEndsTheCheckWithItsFailureAndRetakeKeepsThePlant() = runTest {
        val offline: MockRequestHandler = { throw java.io.IOException("no route") }
        val cases = listOf(
            json(HttpStatusCode.ServiceUnavailable, """{"error":"Diagnosis is unavailable","code":"diagnosis_unavailable"}""") to DiagnosisError.Unavailable,
            json(HttpStatusCode.TooManyRequests, """{"error":"Daily diagnosis limit reached","code":"daily_limit"}""") to DiagnosisError.DailyLimit,
            json(HttpStatusCode.TooManyRequests, """{"error":"limit","code":"daily_limit","scope":"user"}""") to DiagnosisError.DailyLimit,
            json(HttpStatusCode.TooManyRequests, """{"error":"limit","code":"daily_limit","scope":"global"}""") to DiagnosisError.ServiceLimit,
            json(HttpStatusCode.InternalServerError, """{"error":"boom"}""") to DiagnosisError.Unavailable,
            json(HttpStatusCode.BadRequest, """{"error":"Invalid photo"}""") to DiagnosisError.Failed,
            json(HttpStatusCode.OK, """{"causes":[],"summary":"x"}""") to DiagnosisError.Unavailable,
            offline to DiagnosisError.Network,
        )
        for ((handler, expected) in cases) {
            val sent = mutableListOf<Sent>()
            val (api, client) = api(sent, handler)
            val c = controller(ApiPlantDiagnoser(api))
            shoot(c)
            c.send(this, "en")
            advanceUntilIdle()
            assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(expected)), c.phase, "$expected ends loading")
            assertEquals(listOf("POST /api/plants/p-1/diagnose"), routes(sent), "$expected: one request")
            assertNotNull(c.photo, "$expected: the photo stays on the failure screen")
            assertNull(c.diagnosedPlant)
            c.retake()
            assertEquals(SmartCameraPhase.Viewfinder, c.phase)
            assertEquals(SmartCameraMode.Disease, c.mode)
            assertEquals(basil, c.activePlant, "$expected: the chip stays for the next shot")
            assertEquals(1, sent.size, "$expected: retake sends nothing")
            client.close()
        }
    }

    @Test
    fun afterANetworkFailureANewShotGoesToThePlantRouteAgain() = runTest {
        val sent = mutableListOf<Sent>()
        var calls = 0
        val (api, client) = api(sent) {
            if (++calls == 1) throw java.io.IOException("airplane mode")
            ok(it.url.encodedPath)
        }
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        c.updateNote("Spots")
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Network)), c.phase)
        c.retake()
        assertEquals("Spots", c.note, "a failed send is no result: «Снять ещё раз» keeps the note of this session")
        shoot(c)
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(basil, c.diagnosedPlant)
        assertEquals(listOf("POST /api/plants/p-1/diagnose", "POST /api/plants/p-1/diagnose"), routes(sent))
        client.close()
    }

    @Test
    fun cancelKeepsThePhotoNoteAndChipAndALateAnswerIsNotShown() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent) {
            delay(30_000)
            ok(it.url.encodedPath)
        }
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        c.updateNote("Keep me")
        c.send(this, "ru")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SmartCameraPhase.Loading, c.phase)
        c.cancel()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase, "a late answer is not shown")
        assertEquals("Keep me", c.note)
        assertContentEquals(photo, c.photo)
        assertEquals(basil, c.activePlant)
        assertNull(c.diagnosedPlant)
        client.close()
    }

    @Test
    fun anAnswerThatArrivesAfterCancelIsIgnoredAndTheNextSendWorks() = runTest {
        val gate = CompletableDeferred<Unit>()
        val ids = mutableListOf<String>()
        val c = controller(PlantDiagnoser { id, _, _, _ ->
            ids += id
            // A transport that does not observe cancellation still completes later.
            if (ids.size == 1) withContext(NonCancellable) { gate.await() }
            DiagnosisResponse(listOf(DiagnosisCauseDto("Late", "high", "e", "a")), "late")
        })
        shoot(c)
        c.send(this, "ru")
        runCurrent()
        c.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(listOf("p-1", "p-1"), ids)
    }

    @Test
    fun closeCancelsARunningCheckAndDropsThePhoto() = runTest {
        val (api, client) = api(handler = {
            delay(30_000)
            ok(it.url.encodedPath)
        })
        val c = controller(ApiPlantDiagnoser(api))
        shoot(c)
        c.send(this, "ru")
        runCurrent()
        c.close()
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertNull(c.photo)
        assertNull(c.diagnosedPlant)
        client.close()
    }

    @Test
    fun theCaptureFileStaysThroughTheCheckAndIsDeletedOnRetakeAndClose() = runTest {
        val dir = File.createTempFile("gt-plant-cam-test", "").also { it.delete(); it.mkdirs() }
        val first = File(dir, "gt-cam-1.jpg").also { it.writeBytes(photo) }
        val second = File(dir, "gt-cam-2.jpg").also { it.writeBytes(photo) }
        val captures = ArrayDeque(listOf(first, second))
        val deleted = mutableListOf<String>()
        val (api, client) = api()
        val c = controller(
            ApiPlantDiagnoser(api),
            capture = { PickResult.Picked(photo, capturePath = captures.removeFirst().absolutePath) },
            deleteCapture = { path -> deleted += path; File(path).delete() },
        )
        shoot(c)
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertTrue(first.exists(), "the capture stays while the result is open")
        c.retake()
        assertFalse(first.exists(), "retake deletes the capture")
        shoot(c)
        assertTrue(second.exists())
        c.close()
        assertFalse(second.exists(), "the capture is deleted when the screen closes")
        assertEquals(listOf(first.absolutePath, second.absolutePath), deleted)
        assertNull(c.photo)
        dir.deleteRecursively()
        client.close()
    }

    @Test
    fun aJournalServerErrorKeepsTheResultAndARetrySaves() = runTest {
        val sent = mutableListOf<Sent>()
        var status = HttpStatusCode.InternalServerError
        val (api, client) = api(sent) { request ->
            if (request.url.encodedPath == "/api/plants/p-1/photos" && status != HttpStatusCode.Created) {
                respond("""{"error":"boom"}""", status, JSON)
            } else {
                ok(request.url.encodedPath)
            }
        }
        val c = controller(ApiPlantDiagnoser(api), journal = DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) })
        shoot(c)
        c.send(this, "ru")
        advanceUntilIdle()
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Failed, c.journalSave)
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        status = HttpStatusCode.Created
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        assertEquals(
            listOf("POST /api/plants/p-1/diagnose", "POST /api/plants/p-1/photos", "POST /api/plants/p-1/photos"),
            routes(sent),
        )
        client.close()
    }

    @Test
    fun theCoverDownloadedThroughTheApiClientIsThePhotoOfThePlantCheck() = runTest {
        val sent = mutableListOf<Sent>()
        val cover = byteArrayOf(9, 8, 7)
        val (api, client) = api(sent) { request ->
            if (request.url.encodedPath == "/photos/u/p-1/cover.jpg") respond(cover, HttpStatusCode.OK) else ok(request.url.encodedPath)
        }
        val diagnoser = ApiPlantDiagnoser(api)
        val c = controller(
            diagnoser,
            initialPhoto = { DiagnosisLogic.coverPhoto("https://greenthumb.xmpp.site/photos/u/p-1/cover.jpg", diagnoser::fetchPhoto) },
        )
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertContentEquals(cover, c.photo)
        assertEquals(listOf("GET /photos/u/p-1/cover.jpg"), routes(sent), "only the cover download before the send")
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(listOf("GET /photos/u/p-1/cover.jpg", "POST /api/plants/p-1/diagnose"), routes(sent))
        assertEquals(photoDataUri(cover), body(sent[1])["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun aLegacyDataUriCoverIsDecodedWithoutANetworkCall() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val diagnoser = ApiPlantDiagnoser(api)
        val c = controller(diagnoser, initialPhoto = { DiagnosisLogic.coverPhoto("data:image/jpeg;base64,AQID", diagnoser::fetchPhoto) })
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertContentEquals(byteArrayOf(1, 2, 3), c.photo)
        assertTrue(sent.isEmpty(), "no request: ${routes(sent)}")
        client.close()
    }

    private companion object {
        val JSON = headersOf("Content-Type", "application/json")
        const val DIAGNOSE_OK =
            """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"Wet soil.","advice":"Water less."}],"summary":"Too much water."}"""
        const val TWO_CAUSES = """{"causes":[
            {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Reduce watering by 20%."},
            {"title":"Low light","likelihood":"medium","explanation":"Pale leaves.","advice":"Move closer to a window."}
        ],"summary":"Most likely too much water."}"""
        const val CREATED_PHOTO = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-30",""" +
            """"caption":"c","created_at":"2026-10-30T10:00:00.000Z"}"""
    }
}
