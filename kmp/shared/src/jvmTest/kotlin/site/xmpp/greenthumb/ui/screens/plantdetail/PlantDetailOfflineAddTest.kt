package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.data.JSON_HEADERS
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.SaveOutcome
import site.xmpp.greenthumb.data.TODAY
import site.xmpp.greenthumb.data.USER
import site.xmpp.greenthumb.data.ok
import site.xmpp.greenthumb.data.openHarness
import site.xmpp.greenthumb.data.plant
import site.xmpp.greenthumb.data.plantJson
import site.xmpp.greenthumb.data.plantsJson
import site.xmpp.greenthumb.data.requestBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A plant added offline is opened on its local id. When the connection returns, the queued add is replayed
 * and the server hands out its own id: the open detail screen must follow the rename and keep showing the
 * same plant instead of «Plant not found» (owner's smoke of 0.8.2, reproduced twice).
 */
@OptIn(ExperimentalTestApi::class)
class PlantDetailOfflineAddTest {

    private val localId = "00000000-0000-4000-8000-0000000000c1"
    private val serverId = "server-9"

    /** GET always answers (the list is cached), the add fails while [online] is false, like a dead network. */
    private inner class FakeServer {
        var online = false
        var addCount = 0
        val plants = mutableListOf<PlantDto>()

        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path.endsWith("/events") ->
                    ok("""{"events":[],"nextCursor":null}""")
                request.method == HttpMethod.Get && path.endsWith("/photos") -> ok("""{"photos":[]}""")
                request.method == HttpMethod.Get && path == "/api/plants" -> ok(plantsJson(plants))
                request.method == HttpMethod.Post && path == "/api/plants" -> {
                    if (!online) throw IOException("offline")
                    addCount++
                    val body = ApiClient.json.decodeFromString<InsertPlantDto>(requireNotNull(requestBody(request.body)))
                    val created = plant(serverId, body.lastWateredDate, name = body.name)
                    plants += created
                    ok(plantJson(created))
                }
                else -> respond(
                    """{"error":"unexpected ${request.method.value} $path"}""",
                    HttpStatusCode.BadRequest,
                    JSON_HEADERS,
                )
            }
        }
    }

    private fun insert() = InsertPlantDto(
        name = "Ficus offline",
        location = "shelf",
        waterFrequencyDays = 7,
        lastWateredDate = TODAY,
    )

    @Test
    fun detailOpenedOnTheLocalId_keepsShowingThePlant_afterTheAddReplayGivesItAServerId() {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            val queued = runBlocking {
                box.repo.refresh()
                box.repo.addOrQueue(insert())
            }
            assertIs<SaveOutcome.Queued>(queued)
            val opener = PlantRepositoryOpener { box.repo }
            val online = MutableStateFlow(false)
            runDesktopComposeUiTest {
                setContent {
                    site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "en") {
                        site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                            PlantDetailScreen(
                                userId = USER,
                                plantId = localId,
                                opener = opener,
                                onBack = {},
                                onDeleted = {},
                                deleteErrorState = PlantDeleteErrorState(),
                                isOnline = online,
                            )
                        }
                    }
                }
                waitUntilAtLeastOneExists(hasText("Ficus offline"), TIMEOUT)

                // The connection returns: the screen replays the queue and the add comes back under the server id.
                server.online = true
                online.value = true
                waitUntil(timeoutMillis = TIMEOUT) { server.addCount == 1 }
                waitUntil(timeoutMillis = TIMEOUT) {
                    runBlocking { box.repo.currentPlants() }.map { it.id } == listOf(serverId)
                }

                waitUntilAtLeastOneExists(hasText("Ficus offline"), TIMEOUT)
                waitUntilDoesNotExist(hasText("Plant not found"), TIMEOUT)
                assertEquals(1, server.plants.size, "the add was replayed once, the plant was not created twice")
            }
        }
    }

    @Test
    fun repositoryRemembersTheRename_soTheScreenCanFollowIt() {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            runBlocking {
                box.repo.refresh()
                assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insert()))
                assertEquals(localId, box.repo.liveId(localId), "nothing renamed yet")

                server.online = true
                box.repo.replayPending()

                assertEquals(serverId, box.repo.liveId(localId), "the local id follows the plant to the server id")
                assertEquals(serverId, box.repo.liveId(serverId), "a server id is already live")
                assertEquals("unknown-id", box.repo.liveId("unknown-id"), "an unknown id stays as it is")
                assertEquals(mapOf(localId to serverId), box.repo.renamedPlantIds.value)
                assertEquals(listOf(serverId), box.repo.currentPlants().map { it.id })
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
