package site.xmpp.greenthumb.ui.screens.smartcam

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.JpegSegments
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.carriesGpsOrMake
import site.xmpp.greenthumb.core.platform.gpsExifJpeg
import site.xmpp.greenthumb.core.platform.imageSize
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.core.platform.withExifOrientation
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
import site.xmpp.greenthumb.ui.screens.species.ApiSpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic

/**
 * VAL-CAM11-019/020/021 and VAL-CARD-018 on the controller, over the real [ApiClient] stack with a MockEngine:
 * «Что с растением?» opens the camera in «Болезнь» with the plant chip, the check goes to
 * `POST /api/plants/<id>/diagnose` and its result saves to that plant's journal directly; ✕ on the chip makes it a
 * plant-less `POST /api/diagnose` with «Это моё растение?». The photo-sheet entry starts on the after-shot screen
 * with the plant cover; a failed cover download falls back to the viewfinder with the chip. Nothing is sent before
 * the explicit send.
 */
class SmartCameraPlantRouteTest {

    private class Sent(val method: String, val path: String, val body: String)

    private fun TestScope.api(sent: MutableList<Sent>, handler: MockRequestHandler = { ok(it.url.encodedPath) }): Pair<GreenThumbApi, ApiClient> {
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

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok(path: String) = when (path) {
        "/api/plants/p-1/diagnose", "/api/diagnose" -> respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
        "/api/species/identify" -> respond(IDENTIFY_OK, HttpStatusCode.OK, JSON)
        "/api/plants/p-1/photos", "/api/plants/p-2/photos" -> respond(CREATED_PHOTO, HttpStatusCode.Created, JSON)
        else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON)
    }

    private val basil = SmartCameraPlant(id = "p-1", name = "Базилик", photoUrl = "")
    private val photo = byteArrayOf(1, 2, 3, 4)

    private fun controller(
        api: GreenThumbApi,
        plant: SmartCameraPlant? = basil,
        mode: SmartCameraMode = SmartCameraMode.Disease,
        journal: Boolean = true,
        picks: MutableList<PickSource> = mutableListOf(),
        prepared: MutableList<ByteArray> = mutableListOf(),
        initialPhoto: (suspend () -> ByteArray)? = null,
        capture: suspend (Boolean) -> PickResult = { PickResult.Picked(photo) },
        prepareDisease: (ByteArray) -> ByteArray = { it },
    ): SmartCameraController {
        val diagnoser = ApiPlantDiagnoser(api)
        return SmartCameraController(
            initialMode = mode,
            identifier = ApiSpeciesIdentifier(api),
            diagnoser = diagnoser,
            pick = { source ->
                picks += source
                PickResult.Picked(photo)
            },
            preparePlant = { it },
            prepareDisease = { raw ->
                prepared += raw
                prepareDisease(raw)
            },
            catalog = { null },
            capture = capture,
            journal = if (journal) DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) } else null,
            plantDiagnoser = diagnoser,
            plant = plant,
            initialPhoto = initialPhoto,
        )
    }

    private fun body(s: Sent): JsonObject = Json.parseToJsonElement(s.body).jsonObject

    private fun routes(sent: List<Sent>) = sent.map { "${it.method} ${it.path}" }

    @Test
    fun thePlantEntryOpensTheViewfinderInDiseaseModeWithThePlantChip() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertEquals(basil, c.plant)
        assertEquals(basil, c.activePlant)
        assertTrue(sent.isEmpty())
        client.close()
    }

    @Test
    fun withTheChipTheCheckGoesToThePlantRouteAndSavesToItsJournalOnce() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.shutter(this)
        advanceUntilIdle()
        c.updateNote("  Жёлтые листья  ")
        assertTrue(sent.isEmpty(), "a shot and a note send nothing")
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(listOf("POST /api/plants/p-1/diagnose"), routes(sent))
        val json = body(sent.single())
        assertEquals(setOf("photo", "note", "language"), json.keys)
        assertEquals("Жёлтые листья", json["note"]!!.jsonPrimitive.content)
        assertEquals("ru", json["language"]!!.jsonPrimitive.content)
        assertEquals(photoDataUri(photo), json["photo"]!!.jsonPrimitive.content)

        assertEquals(basil, c.diagnosedPlant, "the result belongs to the chip's plant")
        assertEquals("p-1", c.journalPlantId)
        c.openPlantPicker()
        assertFalse(c.plantPickerOpen, "no «Это моё растение?» step for a plant check")
        c.choosePlant("p-2")
        assertEquals("p-1", c.journalPlantId, "the plant of a plant check cannot change")

        c.saveToJournal(this, "Что с растением?: Overwatering (Скорее всего)")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        c.saveToJournal(this, "again")
        advanceUntilIdle()
        assertEquals(listOf("POST /api/plants/p-1/diagnose", "POST /api/plants/p-1/photos"), routes(sent))
        val upload = body(sent[1])
        assertEquals("Что с растением?: Overwatering (Скорее всего)", upload["caption"]!!.jsonPrimitive.content)
        assertEquals(json["photo"]!!.jsonPrimitive.content, upload["photo"]!!.jsonPrimitive.content, "the diagnosed photo")
        client.close()
    }

    @Test
    fun clearingTheChipKeepsDiseaseModeAndMakesAPlantLessCheck() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        c.clearPlant()
        assertNull(c.plant)
        assertNull(c.activePlant)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertTrue(sent.isEmpty(), "✕ on the chip sends nothing")
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "en")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(listOf("POST /api/diagnose"), routes(sent))
        assertEquals(setOf("photo", "language"), body(sent.single()).keys, "no plant id")
        assertNull(c.diagnosedPlant)
        assertNull(c.journalPlantId)
        c.openPlantPicker()
        assertTrue(c.plantPickerOpen, "«Это моё растение?» is offered")
        client.close()
    }

    @Test
    fun theChipCanBeClearedOnTheAfterShotScreenButNotOnceSent() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        c.clearPlant()
        assertNull(c.plant)
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(listOf("POST /api/diagnose"), routes(sent))

        val other = mutableListOf<Sent>()
        val (api2, client2) = api(other)
        val d = controller(api2)
        d.chooseFromGallery(this)
        advanceUntilIdle()
        d.send(this, "ru")
        advanceUntilIdle()
        d.clearPlant()
        assertEquals(basil, d.plant, "the chip stays on a result")
        assertEquals("p-1", d.journalPlantId)
        client.close()
        client2.close()
    }

    @Test
    fun plantModeHidesTheChipAndIdentifiesWithoutThePlantThenDiseaseBringsItBack() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        c.selectMode(SmartCameraMode.Plant)
        assertNull(c.activePlant, "the chip belongs to «Болезнь»")
        assertEquals(basil, c.plant)
        c.selectMode(SmartCameraMode.Disease)
        assertEquals(basil, c.activePlant)
        c.selectMode(SmartCameraMode.Plant)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "en")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
        assertEquals(listOf("POST /api/species/identify"), routes(sent))
        client.close()
    }

    @Test
    fun retakeKeepsThePlantAndDiseaseModeAndANewResultStartsUnsaved() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        c.retake()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertEquals(basil, c.activePlant)
        assertNull(c.diagnosedPlant)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Idle, c.journalSave)
        assertEquals("p-1", c.journalPlantId)
        assertEquals(
            listOf("POST /api/plants/p-1/diagnose", "POST /api/plants/p-1/photos", "POST /api/plants/p-1/diagnose"),
            routes(sent),
        )
        client.close()
    }

    @Test
    fun aJournalSaveFailureKeepsTheResultAndARetrySendsTheSamePhotoOnce() = runTest {
        val sent = mutableListOf<Sent>()
        var uploads = 0
        val (api, client) = api(sent) { request ->
            if (request.url.encodedPath == "/api/plants/p-1/photos" && ++uploads == 1) throw java.io.IOException("airplane mode")
            ok(request.url.encodedPath)
        }
        val c = controller(api)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Failed, c.journalSave)
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        val bodies = sent.filter { it.path == "/api/plants/p-1/photos" }.map { it.body }
        assertEquals(2, bodies.size)
        assertEquals(bodies[0], bodies[1], "the retry sends the same photo and caption")
        assertEquals(1, sent.count { it.path.endsWith("/diagnose") }, "no second diagnosis")
        client.close()
    }

    @Test
    fun withoutAJournalAPlantCheckOffersNoSave() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api, journal = false)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertFalse(c.canSaveToJournal)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(listOf("POST /api/plants/p-1/diagnose"), routes(sent))
        client.close()
    }

    @Test
    fun theCoverPresetStartsOnTheAfterShotScreenWithoutAPickerOrAShot() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val picks = mutableListOf<PickSource>()
        val prepared = mutableListOf<ByteArray>()
        val cover = byteArrayOf(9, 8, 7)
        var shots = 0
        val c = controller(api, picks = picks, prepared = prepared, initialPhoto = { cover }, capture = { shots++; PickResult.Picked(photo) })
        assertEquals(SmartCameraPhase.PhotoLoading, c.phase, "no viewfinder while the cover loads")
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertContentEquals(cover, c.photo)
        assertFalse(c.photoLoadFailed)
        assertEquals(1, prepared.size, "the cover goes through the disease preparation")
        assertTrue(picks.isEmpty(), "no picker")
        assertEquals(0, shots, "no shot")
        assertTrue(sent.isEmpty(), "nothing is sent before «Проверить растение»")
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals(listOf("POST /api/plants/p-1/diagnose"), routes(sent))
        assertEquals(photoDataUri(cover), body(sent.single())["photo"]!!.jsonPrimitive.content)
        assertEquals("p-1", c.journalPlantId)
        client.close()
    }

    @Test
    fun aFailedCoverDownloadFallsBackToTheViewfinderWithTheChipAndSendsNothing() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api, initialPhoto = { throw ApiError.Network })
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertEquals(basil, c.activePlant)
        assertTrue(c.photoLoadFailed)
        assertNull(c.photo)
        c.send(this, "ru")
        advanceUntilIdle()
        assertTrue(sent.isEmpty(), "no diagnose request: ${routes(sent)}")
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertFalse(c.photoLoadFailed, "a new photo clears the message")
        client.close()
    }

    @Test
    fun closingWhileTheCoverLoadsDropsIt() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val gate = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val c = controller(api, initialPhoto = { gate.await() })
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        c.close()
        gate.complete(byteArrayOf(5))
        advanceUntilIdle()
        assertNull(c.photo)
        assertTrue(sent.isEmpty())
        client.close()
    }

    @Test
    fun aRotatedGeotaggedCaptureIsSentUprightAndCleanOnThePlantRouteAndTheJournal() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val capture = withExifOrientation(gpsExifJpeg(1600, 1200), 6)
        val c = controller(
            api,
            capture = { PickResult.Picked(capture) },
            prepareDisease = { raw -> DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) } },
        )
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.shutter(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.saveToJournal(this, "caption")
        advanceUntilIdle()
        assertEquals(listOf("POST /api/plants/p-1/diagnose", "POST /api/plants/p-1/photos"), routes(sent))
        for (request in sent) {
            val uri = body(request)["photo"]!!.jsonPrimitive.content
            val jpeg = Base64.decode(uri.removePrefix("data:image/jpeg;base64,"))
            assertFalse(JpegSegments.hasExifApp1(jpeg), "${request.path}: APP1 Exif")
            assertFalse(carriesGpsOrMake(jpeg), "${request.path}: GPS")
            val (width, height) = assertNotNull(imageSize(jpeg))
            assertTrue(height > width, "${request.path} is upright: $width×$height")
        }
        client.close()
    }

    @Test
    fun withoutAPlantDiagnoserAPlantCheckFailsInsteadOfGoingPlantLess() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Disease,
            identifier = null,
            diagnoser = ApiPlantDiagnoser(api),
            pick = { PickResult.Picked(photo) },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
            plant = basil,
        )
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.Failed>(c.phase)
        assertTrue(sent.isEmpty(), "the photo of a plant check never goes to the plant-less route")
        client.close()
    }

    private companion object {
        val JSON = headersOf("Content-Type", "application/json")
        const val DIAGNOSE_OK =
            """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"Wet soil.","advice":"Water less."}],"summary":"Too much water."}"""
        const val IDENTIFY_OK =
            """{"results":[{"scientificName":"Ocimum basilicum","commonNames":["Basil"],"score":0.81,"gbifId":null}],"remainingToday":19}"""
        const val CREATED_PHOTO = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-30",""" +
            """"caption":"c","created_at":"2026-10-30T10:00:00.000Z"}"""
    }
}
