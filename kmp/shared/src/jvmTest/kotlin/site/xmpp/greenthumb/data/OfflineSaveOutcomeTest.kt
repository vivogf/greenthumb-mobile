package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * VAL-APP-009: an offline save that the journal already holds is a success
 * for the screen ([SaveOutcome.Queued]), not an error. Only confirmed
 * failures (HTTP 4xx/5xx, local write failure) still throw.
 */
class OfflineSaveOutcomeTest {

    @Test
    fun addOrQueue_returnsQueued_onIOExceptionAfterQueueWrite() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) ok("[]") else throw IOException("airplane mode")
        }
        harness.use { box ->
            box.repo.refresh()
            val outcome = box.repo.addOrQueue(insert("ValOffline"))
            val queued = assertIs<SaveOutcome.Queued>(outcome)
            assertEquals("temp-1", queued.plantId)
            assertEquals(listOf("ValOffline"), box.repo.currentPlants().map { it.name })
            val journal = box.repo.pendingJournal().single()
            assertEquals(MutationType.ADD, journal.type)
            assertEquals("temp-1", journal.plantId)
            assertEquals(setOf("temp-1"), box.repo.observeUnsavedPlantIdsOnce())
        }
    }

    @Test
    fun addOrQueue_returnsQueued_onDnsFailure() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) ok("[]") else throw UnknownHostException("greenthumb.xmpp.site")
        }
        harness.use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insert("Dns")))
            assertEquals(1, box.repo.pendingJournal().size)
        }
    }

    @Test
    fun addOrQueue_throwsAndRollsBack_on400() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) {
                ok("[]")
            } else {
                respond("""{"error":"name required"}""", HttpStatusCode.BadRequest, JSON_HEADERS)
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val error = assertFailsWith<ApiError.Client> { box.repo.addOrQueue(insert("Bad")) }
            assertEquals(400, error.status)
            assertTrue(box.repo.currentPlants().isEmpty())
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun addOrQueue_throws_on5xxWhenOnline() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) ok("[]") else serverError()
        }
        harness.use { box ->
            box.repo.refresh()
            assertFailsWith<ApiError.Server> { box.repo.addOrQueue(insert("Boom")) }
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun addOrQueue_returnsSaved_on200() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) ok("[]")
            else ok(plantJson(healthyPlant().copy(id = "server-1", name = "Ficus")))
        }
        harness.use { box ->
            box.repo.refresh()
            val saved = assertIs<SaveOutcome.Saved>(box.repo.addOrQueue(insert("Ficus")))
            assertEquals("server-1", saved.plant.id)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun updateOrQueue_returnsQueued_onNetwork_andThrows_on5xx() = runBlocking<Unit> {
        var offline = true
        val harness = openHarness { request ->
            when {
                request.method == HttpMethod.Get -> ok(plantsJson(listOf(healthyPlant())))
                offline -> throw IOException("offline")
                else -> serverError()
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val photo = PatchPlantDto(photoUrl = Patch.Value("data:image/jpeg;base64,AAAA"))
            val queued = assertIs<SaveOutcome.Queued>(box.repo.updateOrQueue("healthy", photo))
            assertEquals("healthy", queued.plantId)
            assertEquals("data:image/jpeg;base64,AAAA", box.repo.currentPlants().single().photoUrl)
            assertEquals(MutationType.UPDATE, box.repo.pendingJournal().single().type)

            // The older queued row goes first; a confirmed 5xx still surfaces.
            offline = false
            assertFailsWith<ApiError.Server> {
                box.repo.updateOrQueue("healthy", PatchPlantDto(name = Patch.Value("Renamed")))
            }
        }
    }
}

private suspend fun PlantRepository.observeUnsavedPlantIdsOnce(): Set<String> =
    unsavedPlantIds(pendingJournal())

private fun insert(name: String): InsertPlantDto = InsertPlantDto(
    name = name,
    location = "shelf",
    waterFrequencyDays = 7,
    lastWateredDate = TODAY,
)
