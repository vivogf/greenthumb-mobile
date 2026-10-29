package site.xmpp.greenthumb.core.platform

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.ui.components.PhotoPickerController
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantFields
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantForm
import site.xmpp.greenthumb.ui.screens.diagnosis.ApiPlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisController
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisPhase
import site.xmpp.greenthumb.ui.screens.species.ApiSpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.IdentifyController
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic
import site.xmpp.greenthumb.ui.screens.species.IdentifyState
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Privacy guard per route (VAL-LOOK-016): the GPS fixture goes through each photo flow with its production
 * helpers, a MockEngine captures the request body, and the decoded photo has no APP1 `Exif` and no GPS.
 */
class ExifGuardRoutesTest {

    private val fixture = gpsExifJpeg()

    private class Sent(val method: String, val path: String, val body: String)

    private fun TestScope.api(sent: MutableList<Sent>): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    sent += Sent(request.method.value, request.url.encodedPath, body)
                    when (request.url.encodedPath) {
                        "/api/species/identify" -> respond(IDENTIFY_OK, HttpStatusCode.OK, JSON)
                        "/api/plants/p-1/diagnose" -> respond(DIAGNOSE_OK, HttpStatusCode.OK, JSON)
                        // Only the request matters here; a 400 ends the upload calls without a response DTO.
                        else -> respond("""{"error":"test"}""", HttpStatusCode.BadRequest, JSON)
                    }
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private fun assertClean(route: String, dataUri: String) {
        assertTrue(dataUri.startsWith("data:image/jpeg;base64,"), "$route sends a JPEG data URI")
        val photo = Base64.decode(dataUri.removePrefix("data:image/jpeg;base64,"))
        val markers = jpegMarkerList(photo)
        println("$route markers: $markers")
        assertEquals("SOI", markers.first(), route)
        assertFalse(JpegSegments.hasExifApp1(photo), "$route: APP1 Exif in $markers")
        assertFalse(markers.any { it.startsWith("APP1") }, "$route: APP1 in $markers")
        assertFalse(carriesGpsOrMake(photo), "$route still carries GPS or Make")
        assertNotNull(imageSize(photo), "$route photo decodes")
    }

    /** Pick → crop with the real [cropSquareJpeg] → bytes handed to the screen, as `rememberPhotoPicker` does. */
    private fun TestScope.pickAndCrop(): ByteArray {
        var out: ByteArray? = null
        val picker = PhotoPickerController(
            pick = { PickResult.Picked(fixture) },
            crop = { bytes, rect -> cropSquareJpeg(bytes, rect) },
            onPicked = { out = it },
        )
        picker.launch(backgroundScope, PickSource.Gallery)
        runCurrent()
        picker.confirmCrop(CropRect(0f, 0f, 1f, 1f))
        runCurrent()
        return assertNotNull(out, "crop produced bytes")
    }

    @Test
    fun identifyRequestHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = IdentifyController(
            identifier = ApiSpeciesIdentifier(api),
            pick = { PickResult.Picked(fixture) },
            resize = { bytes -> resizeJpeg(bytes, maxSide = IdentifyLogic.PHOTO_MAX_SIDE, quality = 0.85) },
        )
        c.start(this, PickSource.Gallery, "ru") { null }
        // The resize runs on Dispatchers.Default; wait for its continuation to come back.
        repeat(500) {
            advanceUntilIdle()
            if (sent.isNotEmpty() && c.state != IdentifyState.Loading) return@repeat
            Thread.sleep(10)
        }
        assertIs<IdentifyState.Results>(c.state)
        val request = sent.single()
        assertEquals("POST /api/species/identify", "${request.method} ${request.path}")
        assertClean("identify", Json.parseToJsonElement(request.body).jsonObject["photos"]!!.jsonArray.single().jsonPrimitive.content)
        client.close()
    }

    @Test
    fun diagnosisRequestHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = DiagnosisController(
            plantId = "p-1",
            diagnoser = ApiPlantDiagnoser(api),
            pick = { PickResult.Picked(fixture) },
            prepare = { raw -> DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) } },
            deleteCapture = {},
        )
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
        val request = sent.single()
        assertEquals("POST /api/plants/p-1/diagnose", "${request.method} ${request.path}")
        assertClean("diagnose", Json.parseToJsonElement(request.body).jsonObject["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun diagnosisWithoutReencodeStillSendsNoExif() = runTest {
        // A future path that forgets to re-encode still goes through photoDataUri.
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val c = DiagnosisController(
            plantId = "p-1",
            diagnoser = ApiPlantDiagnoser(api),
            pick = { PickResult.Picked(fixture) },
            prepare = { it },
            deleteCapture = {},
        )
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "en")
        advanceUntilIdle()
        assertClean("diagnose (raw bytes)", Json.parseToJsonElement(sent.single().body).jsonObject["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun newPlantCoverRequestHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val bytes = pickAndCrop()
        val fields = AddPlantFields(name = "Fern", waterFrequencyText = "7", lastWateredDate = "2026-10-29")
            .copy(photoUrl = photoDataUri(bytes))
        runCatching { api.addPlant(AddPlantForm.toInsertPlantDto(fields)) }
        val request = sent.single()
        assertEquals("POST /api/plants", "${request.method} ${request.path}")
        assertClean("cover (add plant)", Json.parseToJsonElement(request.body).jsonObject["photo_url"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun coverChangeRequestHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val bytes = pickAndCrop()
        runCatching { api.updatePlant("p-1", PatchPlantDto(photoUrl = Patch.Value(photoDataUri(bytes)))) }
        val request = sent.single()
        assertEquals("PATCH /api/plants/p-1", "${request.method} ${request.path}")
        assertClean("cover (plant detail)", Json.parseToJsonElement(request.body).jsonObject["photo_url"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun timelineUploadHasNoExif() = runTest {
        val sent = mutableListOf<Sent>()
        val (api, client) = api(sent)
        val bytes = pickAndCrop()
        runCatching { api.addPhoto("p-1", photoDataUri(bytes), "New leaf") }
        val request = sent.single()
        assertEquals("POST /api/plants/p-1/photos", "${request.method} ${request.path}")
        assertClean("timeline", Json.parseToJsonElement(request.body).jsonObject["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    private companion object {
        val JSON = headersOf("Content-Type", "application/json")
        const val IDENTIFY_OK =
            """{"results":[{"scientificName":"Monstera deliciosa","commonNames":["Swiss cheese plant"],"score":0.81,"gbifId":null}],"remainingToday":19}"""
        const val DIAGNOSE_OK =
            """{"causes":[{"title":"Overwatering","likelihood":"high","explanation":"Wet soil.","advice":"Water less."}],"summary":"Too much water."}"""
    }
}
