package site.xmpp.greenthumb.ui.screens.smartcam

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.CropRect
import site.xmpp.greenthumb.core.platform.JpegSegments
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.carriesGpsOrMake
import site.xmpp.greenthumb.core.platform.colorAt
import site.xmpp.greenthumb.core.platform.imageSize
import site.xmpp.greenthumb.core.platform.jpegMarkerList
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.core.platform.withExifOrientation
import site.xmpp.greenthumb.core.platform.withGpsExif
import site.xmpp.greenthumb.ui.components.PhotoPickerController
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantForm
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.species.ApiSpeciesIdentifier

/**
 * M11c item 4 (VAL-CAM12-004/005, VAL-CAM-007): the smart camera keeps the 3:4 sensor frame. A sensor-landscape
 * capture with EXIF Orientation 6 and GPS goes through the production [SmartCameraPhoto.prepare] and reaches
 * identify, both diagnosis routes, the journal save and the Add Plant cover as an upright 600×800 JPEG without
 * APP1, with all four corner markers in place (no square crop). Gallery picks keep their own aspect.
 */
class SmartCameraFrameTest {

    private class Sent(val method: String, val path: String, val body: String)

    private fun TestScope.api(sent: MutableList<Sent>): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    sent += Sent(request.method.value, request.url.encodedPath, (request.body as? TextContent)?.text.orEmpty())
                    when (request.url.encodedPath) {
                        "/api/species/identify" -> respond(IDENTIFY_OK, HttpStatusCode.OK, JSON)
                        "/api/diagnose", "/api/plants/p-1/diagnose" -> respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
                        "/api/plants/p-1/photos" -> respond(CREATED_PHOTO, HttpStatusCode.Created, JSON)
                        // Only the request body matters for the cover; a 400 ends the call without a response DTO.
                        else -> respond("""{"error":"test"}""", HttpStatusCode.BadRequest, JSON)
                    }
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private fun body(s: Sent): JsonObject = Json.parseToJsonElement(s.body).jsonObject

    private fun photoOf(s: Sent): ByteArray {
        val json = body(s)
        val uri = json["photo"]?.jsonPrimitive?.content
            ?: json["photo_url"]?.jsonPrimitive?.content
            ?: json["photos"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(uri.startsWith("data:image/jpeg;base64,"), "${s.path} sends a JPEG data URI")
        return Base64.decode(uri.removePrefix("data:image/jpeg;base64,"))
    }

    private fun controller(
        api: GreenThumbApi,
        mode: SmartCameraMode,
        shot: ByteArray = captureFixture,
        picked: ByteArray = captureFixture,
        plant: SmartCameraPlant? = null,
    ): SmartCameraController {
        val diagnoser = ApiPlantDiagnoser(api)
        return SmartCameraController(
            initialMode = mode,
            identifier = ApiSpeciesIdentifier(api),
            diagnoser = diagnoser,
            pick = { PickResult.Picked(picked) },
            capture = { PickResult.Picked(shot) },
            preparePlant = SmartCameraPhoto::prepare,
            prepareDisease = SmartCameraPhoto::prepare,
            catalog = { null },
            journal = DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) },
            plantDiagnoser = diagnoser,
            plant = plant,
        )
    }

    /** The cover the plant gets through «Добавить к моим растениям»: the draft as Add Plant posts it. */
    private suspend fun postCover(api: GreenThumbApi, draft: AddPlantDraft) {
        val fields = AddPlantDraft.initialFields(draft, lastWateredDate = "2026-10-31", language = "ru").copy(name = "Monstera")
        runCatching { api.addPlant(AddPlantForm.toInsertPlantDto(fields)) }
    }

    @Test
    fun aRotatedGeotaggedCaptureBecomesAnUpright600x800FrameWithoutExif() {
        assertEquals(1600 to 1200, imageSize(captureFixture), "the sensor image is landscape 4:3")
        assertTrue(carriesGpsOrMake(captureFixture), "the fixture carries GPS")
        val out = SmartCameraPhoto.prepare(captureFixture)
        assertEquals(600 to 800, imageSize(out), "portrait 3:4, long side 800")
        assertFalse(JpegSegments.hasExifApp1(out), "no APP1 Exif: ${jpegMarkerList(out)}")
        assertFalse(carriesGpsOrMake(out), "no GPS")
        assertUprightCorners("prepared capture", out)
    }

    @Test
    fun theSmartCameraLongSideIs800() {
        assertEquals(800, SmartCameraPhoto.MAX_SIDE)
    }

    @Test
    fun everyRouteAndTheCoverGetTheSame3x4Capture() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)

        // Plant: shot → «Узнать вид» → «Добавить к моим растениям» → Add Plant cover.
        val plantCam = controller(api, SmartCameraMode.Plant)
        plantCam.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        plantCam.shutter(this)
        advanceUntilIdle()
        assertEquals(600 to 800, imageSize(plantCam.photo!!), "the after-shot photo is the 3:4 frame")
        assertTrue(sent.isEmpty(), "a shot sends nothing")
        plantCam.send(this, "ru")
        advanceUntilIdle()
        val draft = assertNotNull(plantCam.addPlantDraft(), "a plant result hands a draft to Add Plant")
        assertEquals("Monstera deliciosa", draft.candidate.scientificName)
        postCover(api, draft)
        advanceUntilIdle()

        // Disease without a plant, then «Это моё растение?» → journal.
        val diseaseCam = controller(api, SmartCameraMode.Disease)
        diseaseCam.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        diseaseCam.shutter(this)
        advanceUntilIdle()
        diseaseCam.send(this, "en")
        advanceUntilIdle()
        diseaseCam.choosePlant("p-1")
        diseaseCam.saveToJournal(this, "caption")
        advanceUntilIdle()

        // Disease from the plant chip.
        val chipCam = controller(api, SmartCameraMode.Disease, plant = SmartCameraPlant("p-1", "Fern", ""))
        chipCam.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        chipCam.shutter(this)
        advanceUntilIdle()
        chipCam.send(this, "ru")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "POST /api/species/identify",
                "POST /api/plants",
                "POST /api/diagnose",
                "POST /api/plants/p-1/photos",
                "POST /api/plants/p-1/diagnose",
            ),
            sent.map { "${it.method} ${it.path}" },
        )
        for (request in sent) {
            val photo = photoOf(request)
            println("${request.path}: ${imageSize(photo)} ${photo.size} bytes, markers ${jpegMarkerList(photo)}")
            assertEquals(600 to 800, imageSize(photo), "${request.path} keeps the 3:4 frame")
            assertTrue(photo.size <= DiagnosisLogic.MAX_PHOTO_BYTES, "${request.path} ≤ 5 MB")
            assertFalse(JpegSegments.hasExifApp1(photo), "${request.path}: APP1 Exif")
            assertFalse(jpegMarkerList(photo).any { it.startsWith("APP1") }, "${request.path}: APP1")
            assertFalse(carriesGpsOrMake(photo), "${request.path}: GPS")
            assertUprightCorners(request.path, photo)
        }
        client.close()
    }

    @Test
    fun theCoverPresetOfAPlantIsPreparedTo800LongSideToo() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val diagnoser = ApiPlantDiagnoser(api)
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Disease,
            identifier = null,
            diagnoser = diagnoser,
            pick = { PickResult.Cancelled },
            preparePlant = SmartCameraPhoto::prepare,
            prepareDisease = SmartCameraPhoto::prepare,
            catalog = { null },
            plantDiagnoser = diagnoser,
            plant = SmartCameraPlant("p-1", "Fern", ""),
            initialPhoto = { quadrantJpeg(1200, 1600) },
        )
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(600 to 800, imageSize(photoOf(sent.single())))
        client.close()
    }

    @Test
    fun galleryPicksKeepTheirOwnAspectOnEveryRoute() = runTest {
        val cases = listOf(
            quadrantJpeg(1600, 900) to (800 to 450),
            quadrantJpeg(1000, 1600) to (500 to 800),
        )
        for ((source, expected) in cases) {
            val sent = mutableListOf<Sent>()
            val (api, client) = api(sent)
            val plantCam = controller(api, SmartCameraMode.Plant, picked = source)
            plantCam.chooseFromGallery(this)
            advanceUntilIdle()
            assertEquals(expected, imageSize(plantCam.photo!!), "after-shot keeps the picked aspect")
            plantCam.send(this, "en")
            advanceUntilIdle()
            postCover(api, assertNotNull(plantCam.addPlantDraft()))
            advanceUntilIdle()
            for (plant in listOf(null, SmartCameraPlant("p-1", "Fern", ""))) {
                val c = controller(api, SmartCameraMode.Disease, picked = source, plant = plant)
                c.chooseFromGallery(this)
                advanceUntilIdle()
                c.send(this, "ru")
                advanceUntilIdle()
            }
            assertEquals(
                listOf("/api/species/identify", "/api/plants", "/api/diagnose", "/api/plants/p-1/diagnose"),
                sent.map { it.path },
            )
            for (request in sent) {
                val photo = photoOf(request)
                val (w, h) = assertNotNull(imageSize(photo))
                assertTrue(abs(w - expected.first) <= 1 && abs(h - expected.second) <= 1, "${request.path}: $w×$h, want $expected")
                assertFalse(JpegSegments.hasExifApp1(photo), "${request.path}: APP1 Exif")
                assertTrue(sourceCornersKept(photo), "${request.path}: corners of the picked photo are kept")
            }
            client.close()
        }
    }

    @Test
    fun addPlantGalleryCoverKeepsItsAspectWithoutTheCropScreen() = runTest {
        var out: ByteArray? = null
        var crops = 0
        val picker = PhotoPickerController(
            pick = { PickResult.Picked(withGpsExif(quadrantJpeg(1600, 900))) },
            crop = { bytes, _ -> crops++; bytes },
            onPicked = { out = it },
            fit = { bytes -> resizeJpeg(bytes) },
        )
        picker.launch(backgroundScope, PickSource.Gallery)
        runCurrent()
        val cover = assertNotNull(out, "the picked photo becomes the cover at once")
        assertEquals(null, picker.cropping, "no crop screen")
        assertEquals(0, crops)
        assertEquals(800 to 450, imageSize(cover))
        assertFalse(carriesGpsOrMake(cover))
        assertTrue(sourceCornersKept(cover))
        // An explicit crop rect still squares only through the crop screen helper, never for this picker.
        assertEquals(CropRect(0f, 0f, 1f, 1f), CropRect.centerSquare(10, 10))
    }

    @Test
    fun theSmartCameraSourceHasNoSquareCrop() {
        val dir = File("src/commonMain/kotlin/site/xmpp/greenthumb/ui/screens/smartcam")
        val files = dir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(files.isNotEmpty(), "smart camera sources found in ${dir.absolutePath}")
        for (file in files) {
            val text = file.readText()
            assertFalse(text.contains("cropSquareJpeg"), "${file.name} squares the camera photo")
            assertFalse(text.contains("centerSquare"), "${file.name} squares the camera photo")
            assertFalse(text.contains("data:image/jpeg;base64,"), "${file.name} builds its own data URI")
        }
    }

    private enum class Mark { Red, Green, Blue, Yellow }

    private fun markAt(jpeg: ByteArray, fx: Float, fy: Float): Mark? {
        val (w, h) = imageSize(jpeg)!!
        val (r, g, b) = colorAt(jpeg, (w * fx).toInt(), (h * fy).toInt())
        return when {
            r > 180 && g > 180 && b < 90 -> Mark.Yellow
            r > 180 && g < 90 && b < 90 -> Mark.Red
            g > 150 && r < 90 && b < 90 -> Mark.Green
            b > 180 && r < 90 && g < 90 -> Mark.Blue
            else -> null
        }
    }

    private fun corners(jpeg: ByteArray): List<Mark?> =
        listOf(markAt(jpeg, 0.05f, 0.05f), markAt(jpeg, 0.95f, 0.05f), markAt(jpeg, 0.95f, 0.95f), markAt(jpeg, 0.05f, 0.95f))

    /** The landscape quadrants red/green (top) and blue/yellow (bottom) turned 90° clockwise by Orientation 6. */
    private fun assertUprightCorners(route: String, jpeg: ByteArray) {
        assertEquals(listOf(Mark.Blue, Mark.Red, Mark.Green, Mark.Yellow), corners(jpeg), "$route: TL, TR, BR, BL corners")
    }

    private fun sourceCornersKept(jpeg: ByteArray): Boolean =
        corners(jpeg) == listOf(Mark.Red, Mark.Green, Mark.Yellow, Mark.Blue)

    private companion object {
        val JSON = headersOf("Content-Type", "application/json")
        const val IDENTIFY_OK =
            """{"results":[{"scientificName":"Monstera deliciosa","commonNames":["Swiss cheese plant"],"score":0.81,"gbifId":null}],"remainingToday":19}"""
        const val DIAGNOSE_OK =
            """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"Wet soil.","advice":"Water less."}],"summary":"Too much water."}"""
        const val CREATED_PHOTO = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-31",""" +
            """"caption":"c","created_at":"2026-10-31T10:00:00.000Z"}"""

        /** Four coloured quadrants: red top-left, green top-right, blue bottom-left, yellow bottom-right. */
        fun quadrantJpeg(width: Int, height: Int): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.RED
            g.fillRect(0, 0, width / 2, height / 2)
            g.color = Color.GREEN
            g.fillRect(width / 2, 0, width - width / 2, height / 2)
            g.color = Color.BLUE
            g.fillRect(0, height / 2, width / 2, height - height / 2)
            g.color = Color.YELLOW
            g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2)
            g.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "jpg", out)
            return out.toByteArray()
        }

        /** What CameraX writes in portrait: a 1600×1200 sensor frame marked Orientation 6, geotagged. */
        val captureFixture: ByteArray = withExifOrientation(withGpsExif(quadrantJpeg(1600, 1200)), 6)
    }
}
