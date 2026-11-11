package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A water or an edit made while the plant's own "add" still waits in the offline journal must reach the
 * server after the add is replayed: the later journal rows carry the local id, the server knows the plant
 * by its own id.
 *
 * Morning notes F7: the add replay used to swap the local id only in the plants table, so the later PATCH hit
 * the local id, got 404 and was rolled back.
 */
class QueuedEditOfUnsyncedPlantTest {

    private val localId = "00000000-0000-4000-8000-0000000000b1"

    private class FakeServer {
        val plants = mutableListOf<PlantDto>()
        val patchedIds = mutableListOf<String>()
        var online = false
        var refusePatch = false
        var listDown = false
        var patchOffline = false

        val handler: MockRequestHandler = { request ->
            when {
                request.method == HttpMethod.Get && listDown -> throw IOException("list down")
                request.method == HttpMethod.Get -> ok(plantsJson(plants))
                !online -> throw IOException("offline")
                request.method == HttpMethod.Post -> {
                    val body = ApiClient.json.decodeFromString<InsertPlantDto>(requireNotNull(requestBody(request.body)))
                    val created = plant("server-1", body.lastWateredDate, name = body.name)
                    plants += created
                    ok(plantJson(created))
                }
                request.method == HttpMethod.Patch && patchOffline -> throw IOException("offline")
                request.method == HttpMethod.Patch -> {
                    val id = request.url.encodedPath.substringAfterLast('/')
                    patchedIds += id
                    val index = plants.indexOfFirst { it.id == id }
                    if (refusePatch) {
                        respond("""{"error":"Invalid"}""", HttpStatusCode.BadRequest, JSON_HEADERS)
                    } else if (index < 0) {
                        respond("""{"error":"Plant not found"}""", HttpStatusCode.NotFound, JSON_HEADERS)
                    } else {
                        val patch = ApiClient.json.decodeFromString<PatchPlantDto>(requireNotNull(requestBody(request.body)))
                        var updated = plants[index]
                        (patch.lastWateredDate as? Patch.Value)?.let { updated = updated.copy(lastWateredDate = it.value) }
                        (patch.name as? Patch.Value)?.let { updated = updated.copy(name = it.value) }
                        plants[index] = updated
                        ok(plantJson(updated))
                    }
                }
                else -> error("unexpected ${request.method} ${request.url}")
            }
        }
    }

    @Test
    fun waterOfQueuedPlant_reachesTheServerAfterTheAddIsReplayed() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insertFern()))
            runCatching { box.repo.water(localId) }

            server.online = true
            box.repo.replayPending()

            assertEquals(listOf("server-1"), server.patchedIds, "the water PATCH goes to the server id")
            assertEquals(TODAY, server.plants.single().lastWateredDate)
            assertEquals(listOf("server-1"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun editOfQueuedPlant_reachesTheServerAfterTheAddIsReplayed() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insertFern()))
            assertIs<SaveOutcome.Queued>(box.repo.updateOrQueue(localId, PatchPlantDto(name = Patch.Value("Big fern"))))

            server.online = true
            box.repo.replayPending()

            assertEquals(listOf("server-1"), server.patchedIds, "the edit PATCH goes to the server id")
            assertEquals("Big fern", server.plants.single().name)
            assertEquals(listOf("server-1"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    /** Online again, a new edit or water of the plant replays the add first and then targets the server id. */
    @Test
    fun editAndWaterOnlineAfterQueuedAdd_targetTheServerId() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insertFern()))

            server.online = true
            assertIs<SaveOutcome.Saved>(box.repo.updateOrQueue(localId, PatchPlantDto(name = Patch.Value("Big fern"))))
            box.repo.water("server-1")

            assertEquals(listOf("server-1", "server-1"), server.patchedIds)
            assertEquals("Big fern", server.plants.single().name)
            assertEquals(listOf("server-1"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    /**
     * The add replay succeeds, the edit after it meets no network: the edit stays queued under the server id.
     * Until it is sent the card shows the server's copy (the add answer), not the edit.
     */
    @Test
    fun editAfterQueuedAdd_heldOnTheEdit_isQueuedUnderTheServerId() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insertFern()))

            server.online = true
            server.patchOffline = true
            val outcome = box.repo.updateOrQueue(localId, PatchPlantDto(name = Patch.Value("Big fern")))

            assertEquals(SaveOutcome.Queued("server-1"), outcome)
            assertEquals(listOf("server-1"), box.repo.pendingJournal().map { it.plantId })
            assertEquals(listOf("server-1"), box.repo.currentPlants().map { it.id })

            server.patchOffline = false
            box.repo.replayPending()
            assertEquals("Big fern", server.plants.single().name)
            assertEquals(listOf("server-1" to "Big fern"), box.repo.currentPlants().map { it.id to it.name })
        }
    }

    /** A refused edit rolls back to its snapshot, which now names the server id: no ghost plant on the local id. */
    @Test
    fun refusedEditOfQueuedPlant_rollsBackOntoTheServerId() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insertFern()))
            assertIs<SaveOutcome.Queued>(box.repo.updateOrQueue(localId, PatchPlantDto(name = Patch.Value("Big fern"))))

            server.online = true
            server.refusePatch = true
            // No list refresh after the replay: the local cache alone must be right.
            server.listDown = true
            runCatching { box.repo.replayPending() }

            assertEquals(listOf("server-1"), server.patchedIds)
            assertEquals(listOf("server-1" to "Fern"), box.repo.currentPlants().map { it.id to it.name })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    private fun insertFern(): InsertPlantDto = InsertPlantDto(
        name = "Fern",
        location = "shelf",
        waterFrequencyDays = 7,
        lastWateredDate = "2026-09-20",
    )
}
