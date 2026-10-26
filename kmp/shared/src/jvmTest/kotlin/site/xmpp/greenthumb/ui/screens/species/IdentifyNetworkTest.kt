package site.xmpp.greenthumb.ui.screens.species

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.IDENTIFY_TIMEOUT_MILLIS
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * VAL-IDENT-014: identify over the real [ApiClient] stack with a MockEngine.
 * The engine runs on the test dispatcher, so the 20 s, 25 s and 10 s waits are
 * virtual time and the deadlines are measured on the test clock.
 */
class IdentifyNetworkTest {

    private val monstera = SpeciesEntry(
        id = "monstera-deliciosa",
        scientificName = "Monstera deliciosa",
        synonyms = listOf("Philodendron pertusum"),
        names = SpeciesText(ru = "Монстера деликатесная", en = "Swiss cheese plant"),
        aliases = SpeciesAliases(),
        waterIntervalDays = 8,
        fertilizeIntervalDays = 21,
        repotIntervalMonths = 24,
        light = SpeciesLight.BrightIndirect,
        tip = SpeciesText(ru = "Совет", en = "Tip"),
    )
    private val catalog = CatalogRepository(listOf(monstera))

    private class Seen {
        var method = ""
        var path = ""
        var body: String? = null
    }

    private fun TestScope.api(seen: Seen = Seen(), handler: MockRequestHandler): Pair<GreenThumbApi, ApiClient> {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    seen.method = request.method.value
                    seen.path = request.url.encodedPath
                    seen.body = (request.body as? TextContent)?.text
                    handler(request)
                }
            },
        )
        val client = ApiClient(engine)
        return GreenThumbApi(client) to client
    }

    private fun controller(api: GreenThumbApi) = IdentifyController(
        identifier = ApiSpeciesIdentifier(api),
        pick = { error("not used") },
        resize = { it },
    )

    private val okBody = """{"results":[
        {"scientificName":"Monstera deliciosa","commonNames":["Swiss cheese plant"],"score":0.81,"gbifId":"2868241"},
        {"scientificName":"Monstera adansonii","commonNames":[],"score":0.07,"gbifId":null},
        {"scientificName":"Epipremnum aureum","commonNames":["Pothos"],"score":0.03,"gbifId":null},
        {"scientificName":"Extra one","commonNames":[],"score":0.01,"gbifId":null}
    ],"remainingToday":19}"""

    private fun json(status: HttpStatusCode, body: String): MockRequestHandler = {
        respond(body, status, headersOf("Content-Type", "application/json"))
    }

    @Test
    fun posts_photo_and_lang_to_identify_and_maps_three_candidates() = runTest {
        val seen = Seen()
        val (api, client) = api(seen, json(HttpStatusCode.OK, okBody))
        val c = controller(api)
        c.identify("data:image/jpeg;base64,/9j/AA==", "ru") { catalog }
        assertEquals("POST", seen.method)
        assertEquals("/api/species/identify", seen.path)
        assertEquals("""{"photos":["data:image/jpeg;base64,/9j/AA=="],"lang":"ru"}""", seen.body)
        val results = assertIs<IdentifyState.Results>(c.state)
        assertEquals(listOf("Monstera deliciosa", "Monstera adansonii", "Epipremnum aureum"), results.candidates.map { it.scientificName })
        assertEquals("monstera-deliciosa", results.candidates[0].catalogEntry?.id)
        assertEquals(null, results.candidates[1].catalogEntry)
        assertEquals(listOf(ScoreLevel.High, ScoreLevel.Low, ScoreLevel.Low), results.candidates.map { it.level })
        client.close()
    }

    @Test
    fun quota_429_settles_as_quota_exceeded() = runTest {
        val (api, client) = api(handler = json(HttpStatusCode.TooManyRequests, """{"error":"Daily identification limit reached","code":"quota_exceeded"}"""))
        val c = controller(api)
        c.identify("data:image/jpeg;base64,AA==", "ru") { catalog }
        assertEquals(IdentifyState.QuotaExceeded, c.state)
        client.close()
    }

    @Test
    fun unavailable_503_settles_as_unavailable() = runTest {
        val (api, client) = api(handler = json(HttpStatusCode.ServiceUnavailable, """{"error":"Identification is unavailable","code":"identify_unavailable"}"""))
        val c = controller(api)
        c.identify("data:image/jpeg;base64,AA==", "en") { catalog }
        assertEquals(IdentifyState.Unavailable, c.state)
        client.close()
    }

    @Test
    fun network_error_settles_as_unavailable() = runTest {
        val (api, client) = api(handler = { throw java.io.IOException("no route") })
        val c = controller(api)
        c.identify("data:image/jpeg;base64,AA==", "ru") { catalog }
        assertEquals(IdentifyState.Unavailable, c.state)
        client.close()
    }

    @Test
    fun empty_results_settle_as_empty_without_invented_candidates() = runTest {
        val (api, client) = api(handler = json(HttpStatusCode.OK, """{"results":[],"remainingToday":3}"""))
        val c = controller(api)
        c.identify("data:image/jpeg;base64,AA==", "ru") { catalog }
        assertEquals(IdentifyState.Empty, c.state)
        client.close()
    }

    @Test
    fun response_after_20_seconds_succeeds() = runTest {
        val (api, client) = api(handler = {
            delay(20_000)
            respond(okBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val c = controller(api)
        val start = testScheduler.currentTime
        c.identify("data:image/jpeg;base64,AA==", "ru") { catalog }
        assertEquals(20_000, testScheduler.currentTime - start)
        assertIs<IdentifyState.Results>(c.state)
        client.close()
    }

    @Test
    fun hanging_response_ends_as_unavailable_at_25_seconds() = runTest(timeout = 10.seconds) {
        val (api, client) = api(handler = {
            delay(600_000)
            respond(okBody, HttpStatusCode.OK)
        })
        val c = controller(api)
        val start = testScheduler.currentTime
        c.identify("data:image/jpeg;base64,AA==", "ru") { catalog }
        assertEquals(IDENTIFY_TIMEOUT_MILLIS, testScheduler.currentTime - start)
        assertEquals(IdentifyState.Unavailable, c.state)
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
}
