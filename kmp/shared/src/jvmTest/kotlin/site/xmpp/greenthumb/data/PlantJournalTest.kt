package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Care journal cache (architecture §5.5): Room keeps the last server copy
 * per plant; notes are written only after the server confirms them, so an
 * offline note is never queued (VAL-JOURNAL-018).
 */
class PlantJournalTest {

    private fun event(
        id: String,
        type: String,
        occurredOn: String,
        note: String? = null,
        plantId: String = "p1",
        createdAt: String = "${occurredOn}T08:00:00.000000Z",
    ): String {
        val noteJson = if (note == null) "null" else "\"$note\""
        return """{"id":"$id","plant_id":"$plantId","user_id":"$USER","type":"$type","occurred_on":"$occurredOn",""" +
            """"note":$noteJson,"photo_id":null,"created_at":"$createdAt"}"""
    }

    private fun page(vararg events: String, next: String? = null): String =
        """{"events":[${events.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun refresh_follows_cursor_and_lists_newest_first() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val harness = openHarness { request ->
            urls += request.url.toString()
            when (request.url.parameters["before"]) {
                null -> ok(page(event("e3", "note", "2026-10-03", "third"), event("e2", "water", "2026-10-02"), next = "c1"))
                "c1" -> ok(page(event("e1", "fertilize", "2026-10-01")))
                else -> serverError()
            }
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            assertEquals(
                listOf(
                    "https://greenthumb.xmpp.site/api/plants/p1/events?limit=100",
                    "https://greenthumb.xmpp.site/api/plants/p1/events?limit=100&before=c1",
                ),
                urls,
            )
            val rows = box.repo.journal.observe("p1").first()
            assertEquals(listOf("e3", "e2", "e1"), rows.map { it.id })
            assertEquals(listOf("note", "water", "fertilize"), rows.map { it.type })
            assertEquals("third", rows.first().note)
        }
    }

    @Test
    fun observe_orders_same_day_by_created_at() = runBlocking<Unit> {
        val harness = openHarness { _ ->
            ok(
                page(
                    event("a", "water", "2026-10-02", createdAt = "2026-10-02T07:00:00.000000Z"),
                    event("b", "note", "2026-10-02", "later", createdAt = "2026-10-02T09:00:00.000000Z"),
                    event("c", "prune", "2026-10-05"),
                ),
            )
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            assertEquals(listOf("c", "b", "a"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }

    @Test
    fun refresh_replaces_only_that_plant() = runBlocking<Unit> {
        var round = 0
        val harness = openHarness { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/plants/p2/events" -> ok(page(event("x1", "water", "2026-10-01", plantId = "p2")))
                round == 0 -> ok(page(event("old", "note", "2026-10-01", "gone soon")))
                else -> ok(page(event("new", "water", "2026-10-04")))
            }
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            box.repo.journal.refresh("p2")
            round = 1
            box.repo.journal.refresh("p1")
            assertEquals(listOf("new"), box.repo.journal.observe("p1").first().map { it.id })
            assertEquals(listOf("x1"), box.repo.journal.observe("p2").first().map { it.id })
        }
    }

    @Test
    fun refresh_failure_keeps_cached_entries() = runBlocking<Unit> {
        var offline = false
        val harness = openHarness { _ ->
            if (offline) throw IOException("airplane mode")
            ok(page(event("e1", "water", "2026-10-02")))
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            offline = true
            val error = assertFailsWith<ApiError> { box.repo.journal.refresh("p1") }
            assertTrue(isHeldForReplay(error))
            assertEquals(listOf("e1"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }

    @Test
    fun addNote_online_stores_the_server_row_once() = runBlocking<Unit> {
        var posts = 0
        var postBody: String? = null
        val created = event("n1", "note", "2026-09-25", "Hello")
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                postBody = requestBody(request.body)
                respond(created, HttpStatusCode.Created, JSON_HEADERS)
            } else {
                ok(page(created))
            }
        }
        harness.use { box ->
            val saved = box.repo.journal.addNote("p1", "  Hello  ")
            assertEquals("n1", saved.id)
            assertEquals("""{"type":"note","note":"Hello"}""", postBody)
            assertEquals(listOf("Hello"), box.repo.journal.observe("p1").first().map { it.note })
            box.repo.journal.refresh("p1")
            assertEquals(listOf("n1"), box.repo.journal.observe("p1").first().map { it.id })
            assertEquals(1, posts)
        }
    }

    @Test
    fun addNote_offline_throws_and_is_neither_stored_nor_queued() = runBlocking<Unit> {
        var posts = 0
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                throw IOException("airplane mode")
            }
            ok("[]")
        }
        harness.use { box ->
            val error = assertFailsWith<ApiError> { box.repo.journal.addNote("p1", "Offline text") }
            assertIs<ApiError.Network>(error)
            assertTrue(box.repo.journal.observe("p1").first().isEmpty())
            assertTrue(box.repo.pendingJournal().isEmpty())
            assertEquals(ReplayResult.Idle, box.repo.replayPending())
            assertEquals(1, posts)
        }
    }

    @Test
    fun addNote_blank_or_too_long_is_rejected_without_a_request() = runBlocking<Unit> {
        var calls = 0
        val harness = openHarness { _ ->
            calls++
            serverError()
        }
        harness.use { box ->
            assertFailsWith<IllegalArgumentException> { box.repo.journal.addNote("p1", "   ") }
            assertFailsWith<IllegalArgumentException> { box.repo.journal.addNote("p1", "x".repeat(1001)) }
            assertEquals(0, calls)
        }
    }

    @Test
    fun deleteNote_removes_the_row_and_404_counts_as_gone() = runBlocking<Unit> {
        var deleteStatus = HttpStatusCode.NoContent
        val deletes = mutableListOf<String>()
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Delete) {
                deletes += request.url.encodedPath
                respond("", deleteStatus, JSON_HEADERS)
            } else {
                ok(page(event("n1", "note", "2026-10-02", "one"), event("n2", "note", "2026-10-01", "two")))
            }
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            box.repo.journal.deleteNote("p1", "n1")
            deleteStatus = HttpStatusCode.NotFound
            box.repo.journal.deleteNote("p1", "n2")
            assertEquals(listOf("/api/plants/p1/events/n1", "/api/plants/p1/events/n2"), deletes)
            assertTrue(box.repo.journal.observe("p1").first().isEmpty())
        }
    }

    @Test
    fun deleteNote_offline_keeps_the_row() = runBlocking<Unit> {
        var offline = false
        val harness = openHarness { request ->
            if (offline && request.method == HttpMethod.Delete) throw IOException("airplane mode")
            ok(page(event("n1", "note", "2026-10-02", "keep")))
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            offline = true
            val error = assertFailsWith<ApiError> { box.repo.journal.deleteNote("p1", "n1") }
            assertTrue(isHeldForReplay(error))
            assertEquals(listOf("n1"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }

    @Test
    fun deleteNote_refuses_automatic_entries_locally() = runBlocking<Unit> {
        var deletes = 0
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Delete) deletes++
            ok(page(event("w1", "water", "2026-10-02")))
        }
        harness.use { box ->
            box.repo.journal.refresh("p1")
            assertFailsWith<IllegalArgumentException> { box.repo.journal.deleteNote("p1", "w1") }
            assertEquals(0, deletes)
            assertEquals(listOf("w1"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }
}
