package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.PlantDto
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Deleting a care entry (water, fertilize, repot, prune) from the journal: the row and, for fertilize,
 * repot and prune, the card date change at once; the DELETE waits in the offline journal like other
 * plant mutations. An old server (409) or another HTTP refusal restores both.
 */
class CareEntryDeleteTest {

    private val base: PlantDto = plant("p1", "2026-09-20").copy(
        fertilizeFrequencyDays = 30,
        lastFertilizedDate = "2026-09-24",
    )

    private fun event(id: String, type: String, occurredOn: String): String =
        """{"id":"$id","plant_id":"p1","user_id":"$USER","type":"$type","occurred_on":"$occurredOn",""" +
            """"note":null,"photo_id":null,"created_at":"${occurredOn}T08:00:00.000000Z"}"""

    private val serverEvents = listOf(
        event("f2", "fertilize", "2026-09-24"),
        event("w1", "water", "2026-09-20"),
        event("f1", "fertilize", "2026-09-10"),
        """{"id":"ph1","plant_id":"p1","user_id":"$USER","type":"photo","occurred_on":"2026-09-05",""" +
            """"note":null,"photo_id":"x1","created_at":"2026-09-05T08:00:00.000000Z"}""",
    )

    private fun page(events: List<String>): String = """{"events":[${events.joinToString(",")}],"nextCursor":null}"""

    private class Server(var deleteStatus: HttpStatusCode = HttpStatusCode.OK, var offline: Boolean = false) {
        val deletes = mutableListOf<String>()
        var answerPlant: PlantDto? = null
    }

    private fun open(server: Server, plantNow: () -> PlantDto = { base }, events: () -> List<String> = { serverEvents }) =
        openHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Delete -> {
                    if (server.offline) throw IOException("airplane mode")
                    server.deletes += path
                    when (server.deleteStatus) {
                        HttpStatusCode.OK -> ok(plantJson(requireNotNull(server.answerPlant)))
                        else -> respond("""{"error":"x"}""", server.deleteStatus, JSON_HEADERS)
                    }
                }
                path == "/api/plants" -> ok(plantsJson(listOf(plantNow())))
                path == "/api/plants/p1/events" -> ok(page(events()))
                else -> serverError()
            }
        }

    private suspend fun RepoHarness.ids(): List<String> = repo.journal.observe("p1").first().map { it.id }

    private suspend fun RepoHarness.local(): PlantDto = repo.currentPlants().single { it.id == "p1" }

    @Test
    fun deleting_the_entry_that_holds_the_card_date_moves_it_back_and_takes_the_server_plant() = runBlocking<Unit> {
        val server = Server()
        server.answerPlant = base.copy(lastFertilizedDate = "2026-09-10", name = "from server")
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            val outcome = box.repo.deleteCareEntry("p1", "f2")
            assertIs<SaveOutcome.Saved>(outcome)
            assertEquals(listOf("/api/plants/p1/events/f2"), server.deletes)
            assertEquals(listOf("w1", "f1", "ph1"), box.ids())
            assertEquals("2026-09-10", box.local().lastFertilizedDate)
            assertEquals("from server", box.local().name)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun the_local_date_follows_before_the_server_answers() = runBlocking<Unit> {
        var seen: PlantDto? = null
        var seenIds: List<String> = emptyList()
        var box: RepoHarness? = null
        val server = Server()
        server.answerPlant = base.copy(lastFertilizedDate = "2026-09-10")
        box = openHarness { request ->
            when {
                request.method == HttpMethod.Delete -> {
                    seen = box!!.local()
                    seenIds = box!!.ids()
                    ok(plantJson(requireNotNull(server.answerPlant)))
                }
                request.url.encodedPath == "/api/plants" -> ok(plantsJson(listOf(base)))
                else -> ok(page(serverEvents))
            }
        }
        box.use { b ->
            b.repo.refresh()
            b.repo.journal.refresh("p1")
            b.repo.deleteCareEntry("p1", "f2")
            assertEquals("2026-09-10", seen?.lastFertilizedDate)
            assertEquals(listOf("w1", "f1", "ph1"), seenIds)
        }
    }

    @Test
    fun deleting_an_older_entry_or_a_water_entry_keeps_the_card_dates() = runBlocking<Unit> {
        val server = Server()
        server.answerPlant = base
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            box.repo.deleteCareEntry("p1", "f1")
            box.repo.deleteCareEntry("p1", "w1")
            assertEquals(listOf("f2", "ph1"), box.ids())
            assertEquals("2026-09-24", box.local().lastFertilizedDate)
            assertEquals("2026-09-20", box.local().lastWateredDate)
        }
    }

    @Test
    fun the_last_entry_of_a_type_clears_the_card_date() = runBlocking<Unit> {
        val server = Server()
        server.answerPlant = base.copy(lastFertilizedDate = null)
        val events = listOf(event("f2", "fertilize", "2026-09-24"))
        var seen: String? = "unset"
        var box: RepoHarness? = null
        box = openHarness { request ->
            when {
                request.method == HttpMethod.Delete -> {
                    seen = box!!.local().lastFertilizedDate
                    ok(plantJson(requireNotNull(server.answerPlant)))
                }
                request.url.encodedPath == "/api/plants" -> ok(plantsJson(listOf(base)))
                else -> ok(page(events))
            }
        }
        box.use { b ->
            b.repo.refresh()
            b.repo.journal.refresh("p1")
            b.repo.deleteCareEntry("p1", "f2")
            assertEquals(null, seen)
            assertEquals(null, b.local().lastFertilizedDate)
        }
    }

    @Test
    fun offline_delete_is_queued_hidden_from_journal_refresh_and_sent_on_replay() = runBlocking<Unit> {
        val server = Server(offline = true)
        server.answerPlant = base.copy(lastFertilizedDate = "2026-09-10")
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            val outcome = box.repo.deleteCareEntry("p1", "f2")
            assertIs<SaveOutcome.Queued>(outcome)
            assertEquals(listOf(MutationType.DELETE_EVENT), box.repo.pendingJournal().map { it.type })
            assertEquals(setOf("p1"), unsavedPlantIds(box.repo.pendingJournal()))
            assertEquals("2026-09-10", box.local().lastFertilizedDate)
            // The server still lists f2 until the DELETE arrives; a journal refresh must not bring it back.
            box.repo.journal.refresh("p1")
            assertEquals(listOf("w1", "f1", "ph1"), box.ids())
            server.offline = false
            assertEquals(ReplayResult.Converged, box.repo.replayPending())
            assertEquals(listOf("/api/plants/p1/events/f2"), server.deletes)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun an_old_server_409_restores_the_entry_and_the_date() = runBlocking<Unit> {
        val server = Server(deleteStatus = HttpStatusCode.Conflict)
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            val error = assertFailsWith<ApiError.Client> { box.repo.deleteCareEntry("p1", "f2") }
            assertEquals(409, error.status)
            assertTrue(isOldServerRefusal(error))
            assertEquals(listOf("f2", "w1", "f1", "ph1"), box.ids())
            assertEquals("2026-09-24", box.local().lastFertilizedDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun a_queued_delete_refused_on_replay_restores_the_entry_and_the_date() = runBlocking<Unit> {
        val server = Server(offline = true)
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            assertIs<SaveOutcome.Queued>(box.repo.deleteCareEntry("p1", "f2"))
            server.offline = false
            server.deleteStatus = HttpStatusCode.Conflict
            box.repo.replayPending()
            assertEquals(listOf("f2", "w1", "f1", "ph1"), box.ids())
            assertEquals("2026-09-24", box.local().lastFertilizedDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun a_404_counts_as_already_deleted() = runBlocking<Unit> {
        val server = Server(deleteStatus = HttpStatusCode.NotFound)
        open(server).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            assertIs<SaveOutcome.Saved>(box.repo.deleteCareEntry("p1", "f2"))
            assertEquals(listOf("w1", "f1", "ph1"), box.ids())
            assertEquals("2026-09-10", box.local().lastFertilizedDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun notes_photos_and_unknown_entries_are_refused_without_a_request() = runBlocking<Unit> {
        val server = Server()
        val withNote = serverEvents + event("n1", "note", "2026-09-01")
        open(server, events = { withNote }).use { box ->
            box.repo.refresh()
            box.repo.journal.refresh("p1")
            assertFailsWith<IllegalArgumentException> { box.repo.deleteCareEntry("p1", "ph1") }
            assertFailsWith<IllegalArgumentException> { box.repo.deleteCareEntry("p1", "n1") }
            assertFailsWith<IllegalArgumentException> { box.repo.deleteCareEntry("p1", "nope") }
            assertTrue(server.deletes.isEmpty())
            assertTrue(box.repo.pendingJournal().isEmpty())
            assertEquals(5, box.ids().size)
        }
    }
}
