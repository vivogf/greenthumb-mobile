package site.xmpp.greenthumb.ui.screens.smartcam

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.DIAGNOSE_TIMEOUT_MILLIS
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.JpegSegments
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.carriesGpsOrMake
import site.xmpp.greenthumb.core.platform.gpsExifJpeg
import site.xmpp.greenthumb.core.platform.imageSize
import site.xmpp.greenthumb.core.platform.jpegMarkerList
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.core.platform.withExifOrientation
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
import site.xmpp.greenthumb.ui.screens.species.ApiSpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic

/**
 * VAL-CAM-009/010/018 over the real [ApiClient] stack with a MockEngine: «Болезнь» sends a plant-less
 * `POST /api/diagnose` with a clean photo and the UI language under the 75 s deadline, «Растение» sends a
 * clean identify photo, and «Это моё растение?» uploads to the chosen plant once without a second diagnosis.
 */
class SmartCameraNetworkTest {

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
        "/api/diagnose" -> respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
        "/api/species/identify" -> respond(IDENTIFY_OK, HttpStatusCode.OK, JSON)
        "/api/plants/p-2/photos" -> respond(CREATED_PHOTO, HttpStatusCode.Created, JSON)
        else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, JSON)
    }

    private val fixture = gpsExifJpeg()

    /** A sensor-landscape 1600×1200 capture that the camera marks as rotated (EXIF Orientation 6) and geotagged. */
    private val captureFixture = withExifOrientation(gpsExifJpeg(1600, 1200), 6)

    private fun controller(api: GreenThumbApi, mode: SmartCameraMode, journal: DiagnosisJournalSaver? = null) = SmartCameraController(
        initialMode = mode,
        identifier = ApiSpeciesIdentifier(api),
        diagnoser = ApiPlantDiagnoser(api),
        pick = { PickResult.Picked(fixture) },
        capture = { PickResult.Picked(captureFixture) },
        preparePlant = { raw -> resizeJpeg(raw, maxSide = IdentifyLogic.PHOTO_MAX_SIDE, quality = 0.85) },
        prepareDisease = { raw -> DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) } },
        catalog = { null },
        journal = journal,
    )

    private fun assertCleanPhoto(route: String, dataUri: String) {
        assertTrue(dataUri.startsWith("data:image/jpeg;base64,"), "$route sends a JPEG data URI")
        val photo = Base64.decode(dataUri.removePrefix("data:image/jpeg;base64,"))
        val markers = jpegMarkerList(photo)
        val sha = MessageDigest.getInstance("SHA-256").digest(photo).joinToString("") { "%02x".format(it) }
        println("$route photo: ${photo.size} bytes, sha256 ${sha.take(16)}…, markers $markers")
        assertTrue(photo.size <= DiagnosisLogic.MAX_PHOTO_BYTES, "$route photo ≤ 5 MB")
        assertFalse(JpegSegments.hasExifApp1(photo), "$route: APP1 Exif in $markers")
        assertFalse(markers.any { it.startsWith("APP1") }, "$route: APP1 in $markers")
        assertFalse(carriesGpsOrMake(photo), "$route still carries GPS or Make")
        assertNotNull(imageSize(photo), "$route photo decodes")
    }

    private fun body(s: Sent): JsonObject = Json.parseToJsonElement(s.body).jsonObject

    @Test
    fun diseaseModePostsAPlantLessDiagnoseWithACleanPhotoAndTheUiLanguage() = runTest {
        for (language in listOf("ru", "en")) {
            val sent = mutableListOf<Sent>()
            val (api, client) = api(sent)
            val c = controller(api, SmartCameraMode.Disease)
            c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, DiagnosisLogic.wireLanguage(language))
            advanceUntilIdle()
            assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
            val request = sent.single()
            assertEquals("POST /api/diagnose", "${request.method} ${request.path}")
            val json = body(request)
            assertEquals(setOf("photo", "language"), json.keys, "no plant id, no note: ${json.keys}")
            assertEquals(language, json["language"]!!.jsonPrimitive.content)
            assertCleanPhoto("diagnose ($language)", json["photo"]!!.jsonPrimitive.content)
            client.close()
        }
    }

    @Test
    fun diseaseModeUsesThe75SecondDiagnoseDeadline() = runTest(timeout = 10.seconds) {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent) {
            delay(600_000)
            respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
        }
        val c = controller(api, SmartCameraMode.Disease)
        val start = testScheduler.currentTime
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(75_000L, DIAGNOSE_TIMEOUT_MILLIS)
        assertEquals(DIAGNOSE_TIMEOUT_MILLIS, testScheduler.currentTime - start)
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Network)), c.phase)
        client.close()
    }

    @Test
    fun plantModeIdentifyPhotoHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api, SmartCameraMode.Plant)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "en")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
        val request = sent.single()
        assertEquals("POST /api/species/identify", "${request.method} ${request.path}")
        assertCleanPhoto("identify", body(request)["photos"]!!.jsonArray.single().jsonPrimitive.content)
        client.close()
    }

    @Test
    fun itIsMyPlantUploadsOnceToTheChosenPlantWithoutASecondDiagnosis() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api, SmartCameraMode.Disease, DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) })
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.choosePlant("p-2")
        c.saveToJournal(this, "Что с растением?: Overwatering (Скорее всего)")
        advanceUntilIdle()
        c.saveToJournal(this, "again")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        assertEquals(listOf("POST /api/diagnose", "POST /api/plants/p-2/photos"), sent.map { "${it.method} ${it.path}" })
        val upload = body(sent[1])
        assertEquals("Что с растением?: Overwatering (Скорее всего)", upload["caption"]!!.jsonPrimitive.content)
        assertEquals(body(sent[0])["photo"]!!.jsonPrimitive.content, upload["photo"]!!.jsonPrimitive.content, "the diagnosed photo")
        assertCleanPhoto("journal save", upload["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun aCaptureWithRotationAndGpsIsSentUprightAndCleanOnEveryRoute() = runTest {
        val (rawWidth, rawHeight) = imageSize(captureFixture)!!
        assertTrue(rawWidth > rawHeight, "the sensor image is landscape")
        assertTrue(carriesGpsOrMake(captureFixture), "the fixture carries GPS")
        for (mode in SmartCameraMode.entries) {
            val sent = mutableListOf<Sent>()
            val (api, client) = api(sent)
            val c = controller(api, mode, DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) })
            c.onCameraStatus(CameraStatus.Ready(hasFlash = true))
            c.shutter(this)
            advanceUntilIdle()
            assertEquals(SmartCameraPhase.Captured, c.phase)
            assertTrue(sent.isEmpty(), "a shot sends nothing")
            val (shownWidth, shownHeight) = imageSize(c.photo!!)!!
            assertTrue(shownHeight > shownWidth, "the after-shot photo is upright: $shownWidth×$shownHeight")
            c.send(this, "ru")
            advanceUntilIdle()
            val routes = mutableListOf(sent.single())
            if (mode == SmartCameraMode.Disease) {
                c.choosePlant("p-2")
                c.saveToJournal(this, "caption")
                advanceUntilIdle()
                routes += sent[1]
            }
            for (request in routes) {
                val json = body(request)
                val photo = json["photo"]?.jsonPrimitive?.content ?: json["photos"]!!.jsonArray.single().jsonPrimitive.content
                assertCleanPhoto("capture ${request.path}", photo)
                val (width, height) = imageSize(Base64.decode(photo.removePrefix("data:image/jpeg;base64,")))!!
                assertTrue(height > width, "${request.path} photo is upright: $width×$height")
            }
            client.close()
        }
    }

    @Test
    fun theDiseaseNoteGoesWithTheDiagnoseRequest() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = controller(api, SmartCameraMode.Disease)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.updateNote("Жёлтые пятна")
        c.send(this, "ru")
        advanceUntilIdle()
        val json = body(sent.single())
        assertEquals(setOf("photo", "note", "language"), json.keys)
        assertEquals("Жёлтые пятна", json["note"]!!.jsonPrimitive.content)
        client.close()
    }

    private companion object {        val JSON = headersOf("Content-Type", "application/json")
        const val DIAGNOSE_OK =
            """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"Wet soil.","advice":"Water less."}],"summary":"Too much water."}"""
        const val IDENTIFY_OK =
            """{"results":[{"scientificName":"Monstera deliciosa","commonNames":["Swiss cheese plant"],"score":0.81,"gbifId":null}],"remainingToday":19}"""
        const val CREATED_PHOTO = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-30",""" +
            """"caption":"c","created_at":"2026-10-30T10:00:00.000Z"}"""
    }
}
