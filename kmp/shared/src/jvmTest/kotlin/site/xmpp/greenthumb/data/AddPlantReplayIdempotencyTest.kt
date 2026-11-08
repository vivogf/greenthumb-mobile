package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.IDEMPOTENCY_KEY_HEADER
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A queued add whose first answer was lost (the server created the plant, the phone saw a network error)
 * must not become a second plant on replay: every attempt carries the same Idempotency-Key.
 */
class AddPlantReplayIdempotencyTest {

    private val localId = "00000000-0000-4000-8000-0000000000a1"

    /** Fake idempotent server: one plant per key; [loseAnswers] drops the response after the insert. */
    private class FakeServer {
        val plants = mutableListOf<PlantDto>()
        val keys = mutableListOf<String?>()
        var loseAnswers = true

        val handler: MockRequestHandler = { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(plants))
            } else {
                val key = request.headers[IDEMPOTENCY_KEY_HEADER]
                keys += key
                val body = ApiClient.json.decodeFromString<InsertPlantDto>(requireNotNull(requestBody(request.body)))
                val existing = plants.firstOrNull { key != null && it.id == "server-$key" }
                val plant = existing ?: plant("server-${key ?: plants.size}", body.lastWateredDate, name = body.name)
                    .also { plants += it }
                if (loseAnswers) throw IOException("answer lost") else ok(plantJson(plant))
            }
        }
    }

    @Test
    fun replayAfterLostAnswer_sendsTheSameKey_andKeepsOnePlant() = runBlocking<Unit> {
        val server = FakeServer()
        openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler).use { box ->
            box.repo.refresh()
            assertIs<SaveOutcome.Queued>(box.repo.addOrQueue(insert("Fern")))
            assertEquals(1, server.plants.size)

            server.loseAnswers = false
            box.repo.replayPending()

            assertEquals(listOf<String?>(localId, localId), server.keys)
            assertEquals(1, server.plants.size)
            assertEquals(listOf("server-$localId"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun replayAfterRestart_usesTheJournalRowsPlantIdAsTheKey() = runBlocking<Unit> {
        // A row queued by an earlier run (0.8.0 rows look the same: ADD with a UUID plantId).
        val server = FakeServer()
        val harness = openHarness(ids = ArrayDeque(listOf(localId)), handler = server.handler)
        harness.repo.refresh()
        assertIs<SaveOutcome.Queued>(harness.repo.addOrQueue(insert("Fern")))
        harness.repo.close()
        harness.client.close()

        server.loseAnswers = false
        val (client, repo) = reopenRepository(harness.databases, server.handler)
        try {
            repo.replayPending()
            assertEquals(listOf<String?>(localId, localId), server.keys)
            assertEquals(1, server.plants.size)
            assertEquals(listOf("server-$localId"), repo.currentPlants().map { it.id })
        } finally {
            runCatching { repo.close() }
            runCatching { client.close() }
            harness.close()
        }
    }
}

private fun insert(name: String): InsertPlantDto = InsertPlantDto(
    name = name,
    location = "shelf",
    waterFrequencyDays = 7,
    lastWateredDate = TODAY,
)
