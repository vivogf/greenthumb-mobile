package site.xmpp.greenthumb.ui.screens.diagnosis

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri

/**
 * VAL-CARD-014/015/016/018: saving a diagnosis to the plant journal, its failure and retry, the
 * plant route of the save, the cover photo preset, and the limit scopes of a 429.
 */
class DiagnosisJournalSaveTest {

    private class Seen {
        val requests = mutableListOf<Pair<String, String>>()
        val bodies = mutableMapOf<String, MutableList<String>>()
        fun count(path: String): Int = requests.count { it.second == path }
    }

    private val okBody = """{"causes":[
        {"title":"Overwatering","likelihood":"high","explanation":"Soil stays wet.","advice":"Water less."},
        {"title":"Low light","likelihood":"medium","explanation":"Pale leaves.","advice":"Move to a window."}
    ],"summary":"Too much water."}"""

    private val createdPhoto = """{"id":"ph-1","url":"https://example.test/ph-1.jpg","taken_on":"2026-10-30",""" +
        """"caption":"c","created_at":"2026-10-30T10:00:00.000Z"}"""

    private val json = headersOf("Content-Type", "application/json")

    private fun TestScope.api(seen: Seen, handler: MockRequestHandler): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val path = request.url.encodedPath
                    seen.requests += request.method.value to path
                    (request.body as? TextContent)?.text?.let { seen.bodies.getOrPut(path) { mutableListOf() } += it }
                    handler(request)
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private val photoBytes = byteArrayOf(1, 2, 3, 4)

    private fun controller(
        api: GreenThumbApi,
        journal: Boolean = true,
        picks: MutableList<PickSource> = mutableListOf(),
        prepared: MutableList<ByteArray> = mutableListOf(),
        initialPhoto: (suspend () -> ByteArray)? = null,
    ) = DiagnosisController(
        plantId = "p-1",
        diagnoser = ApiPlantDiagnoser(api),
        pick = { source ->
            picks += source
            PickResult.Picked(photoBytes)
        },
        prepare = { raw ->
            prepared += raw
            raw
        },
        deleteCapture = {},
        journal = if (journal) DiagnosisJournalSaver { id, uri, caption -> api.addPhoto(id, uri, caption) } else null,
        initialPhoto = initialPhoto,
    )

    private suspend fun TestScope.diagnosed(c: DiagnosisController) {
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
    }

    private fun ok(seen: Seen, photoStatus: () -> HttpStatusCode = { HttpStatusCode.Created }): MockRequestHandler = { request ->
        when (request.url.encodedPath) {
            "/api/plants/p-1/diagnose" -> respond(okBody, HttpStatusCode.OK, json)
            "/api/plants/p-1/photos" -> {
                val status = photoStatus()
                respond(if (status.value < 300) createdPhoto else """{"error":"x"}""", status, json)
            }
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    @Test
    fun save_uploads_the_diagnosed_photo_once_to_this_plant() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, ok(seen))
        val c = controller(api)
        diagnosed(c)
        assertTrue(c.canSaveToJournal)
        assertEquals(JournalSaveState.Idle, c.journalSave)
        c.saveToJournal(this, "Что с растением?: Overwatering (Скорее всего)")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        // A second tap creates nothing.
        c.saveToJournal(this, "again")
        advanceUntilIdle()
        assertEquals(1, seen.count("/api/plants/p-1/photos"))
        assertEquals(1, seen.count("/api/plants/p-1/diagnose"), "no second diagnosis call")
        val sent = Json.parseToJsonElement(seen.bodies.getValue("/api/plants/p-1/photos").single()).jsonObject
        assertEquals(photoDataUri(photoBytes), sent["photo"]!!.jsonPrimitive.content)
        assertEquals("Что с растением?: Overwatering (Скорее всего)", sent["caption"]!!.jsonPrimitive.content)
        assertIs<DiagnosisPhase.Result>(c.phase, "the result stays on screen")
        client.close()
    }

    @Test
    fun network_error_keeps_the_result_and_retry_sends_the_same_photo_once() = runTest {
        val seen = Seen()
        var calls = 0
        val (api, client) = api(seen) { request ->
            if (request.url.encodedPath == "/api/plants/p-1/photos" && ++calls == 1) throw java.io.IOException("airplane mode")
            ok(seen)(request)
        }
        val c = controller(api)
        diagnosed(c)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Failed, c.journalSave)
        assertIs<DiagnosisPhase.Result>(c.phase)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        val bodies = seen.bodies.getValue("/api/plants/p-1/photos")
        assertEquals(2, bodies.size, "one failed and one successful upload")
        assertEquals(bodies[0], bodies[1], "retry sends the same photo and caption")
        assertEquals(1, seen.count("/api/plants/p-1/diagnose"))
        client.close()
    }

    @Test
    fun server_error_gives_the_same_failure_and_retry_works() = runTest {
        val seen = Seen()
        var status = HttpStatusCode.InternalServerError
        val (api, client) = api(seen, ok(seen) { status })
        val c = controller(api)
        diagnosed(c)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Failed, c.journalSave)
        status = HttpStatusCode.Created
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        client.close()
    }

    @Test
    fun without_a_journal_nothing_is_offered_or_sent() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, ok(seen))
        val c = controller(api, journal = false)
        diagnosed(c)
        assertFalse(c.canSaveToJournal)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(0, seen.count("/api/plants/p-1/photos"))
        client.close()
    }

    @Test
    fun a_new_result_starts_with_an_unsaved_journal_state() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, ok(seen))
        val c = controller(api)
        diagnosed(c)
        c.saveToJournal(this, "cap")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        c.startOver()
        c.submit(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
        assertEquals(JournalSaveState.Idle, c.journalSave)
        client.close()
    }

    @Test
    fun cover_photo_preset_skips_the_picker() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, ok(seen))
        val picks = mutableListOf<PickSource>()
        val prepared = mutableListOf<ByteArray>()
        val cover = byteArrayOf(9, 8, 7)
        val c = controller(api, picks = picks, prepared = prepared, initialPhoto = { cover })
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertFalse(c.photoLoading)
        assertFalse(c.photoLoadFailed)
        assertContentEquals(cover, c.photo)
        assertEquals(1, prepared.size, "the cover goes through the same preparation as a picked photo")
        assertTrue(picks.isEmpty(), "no picker opened")
        c.submit(this, "ru")
        advanceUntilIdle()
        assertIs<DiagnosisPhase.Result>(c.phase)
        val sent = Json.parseToJsonElement(seen.bodies.getValue("/api/plants/p-1/diagnose").single()).jsonObject
        assertEquals(photoDataUri(cover), sent["photo"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun failed_cover_download_falls_back_to_the_input_and_sends_nothing() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, ok(seen))
        val c = controller(api, initialPhoto = { throw ApiError.Network })
        c.loadInitialPhoto(this)
        advanceUntilIdle()
        assertTrue(c.photoLoadFailed)
        assertFalse(c.photoLoading)
        assertNull(c.photo)
        assertEquals(DiagnosisPhase.Input, c.phase)
        c.submit(this, "ru")
        advanceUntilIdle()
        assertTrue(seen.requests.isEmpty(), "no diagnose request: ${seen.requests}")
        // Picking a photo by hand clears the message.
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        assertFalse(c.photoLoadFailed)
        client.close()
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
        client.close()
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
    fun no_retry_after_either_limit() = runTest {
        val seen = Seen()
        val (api, client) = api(seen) {
            respond("""{"error":"limit","code":"daily_limit","scope":"global"}""", HttpStatusCode.TooManyRequests, json)
        }
        val c = controller(api)
        c.pickPhoto(this, PickSource.Gallery)
        advanceUntilIdle()
        c.submit(this, "ru")
        advanceUntilIdle()
        assertEquals(DiagnosisError.ServiceLimit, c.error)
        assertFalse(c.canRetry)
        client.close()
    }
}
