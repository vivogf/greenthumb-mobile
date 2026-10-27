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
 * Photo timeline cache (architecture §5.5): Room keeps the last server copy
 * per plant; uploads go out only on a tap and are never queued, and a
 * delete removes the photo and its `photo` journal entry together.
 */
class PlantTimelineTest {

    private fun photo(id: String, takenOn: String, caption: String? = null, createdAt: String = "${takenOn}T08:00:00.000000Z"): String {
        val captionJson = if (caption == null) "null" else "\"$caption\""
        return """{"id":"$id","url":"https://photos.example/$id.jpg","taken_on":"$takenOn",""" +
            """"caption":$captionJson,"created_at":"$createdAt"}"""
    }

    private fun photoEvent(id: String, photoId: String, occurredOn: String, plantId: String = "p1"): String =
        """{"id":"$id","plant_id":"$plantId","user_id":"$USER","type":"photo","occurred_on":"$occurredOn",""" +
            """"note":null,"photo_id":"$photoId","created_at":"${occurredOn}T08:00:00.000000Z"}"""

    private fun photos(vararg rows: String): String = """{"photos":[${rows.joinToString(",")}]}"""

    private fun created(photoId: String, eventId: String, takenOn: String, caption: String?): String =
        photo(photoId, takenOn, caption).dropLast(1) + ""","event":${photoEvent(eventId, photoId, takenOn)}}"""

    @Test
    fun refresh_lists_newest_first_and_replaces_only_that_plant() = runBlocking<Unit> {
        var round = 0
        val urls = mutableListOf<String>()
        val harness = openHarness { request ->
            urls += request.url.encodedPath
            when {
                request.url.encodedPath == "/api/plants/p2/photos" -> ok(photos(photo("x1", "2026-10-01")))
                round == 0 -> ok(
                    photos(
                        photo("b", "2026-10-02", "second", createdAt = "2026-10-02T09:00:00.000000Z"),
                        photo("a", "2026-10-02", createdAt = "2026-10-02T07:00:00.000000Z"),
                        photo("old", "2026-09-01", "first"),
                    ),
                )
                else -> ok(photos(photo("c", "2026-10-05", "new")))
            }
        }
        harness.use { box ->
            box.repo.timeline.refresh("p1")
            box.repo.timeline.refresh("p2")
            val rows = box.repo.timeline.observe("p1").first()
            assertEquals(listOf("b", "a", "old"), rows.map { it.id })
            assertEquals(listOf("second", null, "first"), rows.map { it.caption })
            assertEquals("https://photos.example/b.jpg", rows.first().url)
            assertEquals("2026-10-02", rows.first().takenOn)
            round = 1
            box.repo.timeline.refresh("p1")
            assertEquals(listOf("c"), box.repo.timeline.observe("p1").first().map { it.id })
            assertEquals(listOf("x1"), box.repo.timeline.observe("p2").first().map { it.id })
            assertEquals("/api/plants/p1/photos", urls.first())
        }
    }

    @Test
    fun refresh_failure_keeps_cached_photos() = runBlocking<Unit> {
        var offline = false
        val harness = openHarness { _ ->
            if (offline) throw IOException("airplane mode")
            ok(photos(photo("a", "2026-10-02")))
        }
        harness.use { box ->
            box.repo.timeline.refresh("p1")
            offline = true
            val error = assertFailsWith<ApiError> { box.repo.timeline.refresh("p1") }
            assertTrue(isHeldForReplay(error))
            assertEquals(listOf("a"), box.repo.timeline.observe("p1").first().map { it.id })
        }
    }

    @Test
    fun addPhoto_online_caches_the_photo_and_its_journal_entry_once() = runBlocking<Unit> {
        var posts = 0
        var postBody: String? = null
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                postBody = requestBody(request.body)
                respond(created("ph1", "ev1", "2026-09-25", "New leaf"), HttpStatusCode.Created, JSON_HEADERS)
            } else {
                ok(photos())
            }
        }
        harness.use { box ->
            val saved = box.repo.timeline.addPhoto("p1", "data:image/jpeg;base64,AAAA", "  New leaf  ")
            assertEquals("ph1", saved.id)
            assertEquals("""{"photo":"data:image/jpeg;base64,AAAA","caption":"New leaf"}""", postBody)
            assertEquals(listOf("ph1"), box.repo.timeline.observe("p1").first().map { it.id })
            val journal = box.repo.journal.observe("p1").first()
            assertEquals(listOf("ev1"), journal.map { it.id })
            assertEquals("ph1", journal.single().photoId)
            assertEquals(1, posts)
        }
    }

    @Test
    fun addPhoto_without_caption_sends_no_caption() = runBlocking<Unit> {
        var postBody: String? = null
        val harness = openHarness { request ->
            postBody = requestBody(request.body)
            respond(created("ph2", "ev2", "2026-09-25", null), HttpStatusCode.Created, JSON_HEADERS)
        }
        harness.use { box ->
            box.repo.timeline.addPhoto("p1", "data:image/jpeg;base64,BBBB", "   ")
            assertEquals("""{"photo":"data:image/jpeg;base64,BBBB"}""", postBody)
            assertEquals(listOf<String?>(null), box.repo.timeline.observe("p1").first().map { it.caption })
        }
    }

    @Test
    fun addPhoto_too_long_caption_is_rejected_without_a_request() = runBlocking<Unit> {
        var calls = 0
        val harness = openHarness { _ ->
            calls++
            serverError()
        }
        harness.use { box ->
            assertFailsWith<IllegalArgumentException> {
                box.repo.timeline.addPhoto("p1", "data:image/jpeg;base64,AAAA", "x".repeat(301))
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun addPhoto_offline_throws_and_is_neither_stored_nor_queued() = runBlocking<Unit> {
        var posts = 0
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Post) {
                posts++
                throw IOException("airplane mode")
            }
            ok(photos())
        }
        harness.use { box ->
            val error = assertFailsWith<ApiError> { box.repo.timeline.addPhoto("p1", "data:image/jpeg;base64,AAAA", "x") }
            assertIs<ApiError.Network>(error)
            assertTrue(box.repo.timeline.observe("p1").first().isEmpty())
            assertTrue(box.repo.journal.observe("p1").first().isEmpty())
            assertTrue(box.repo.pendingJournal().isEmpty())
            assertEquals(ReplayResult.Idle, box.repo.replayPending())
            assertEquals(1, posts)
        }
    }

    @Test
    fun deletePhoto_removes_photo_and_its_journal_entry_and_404_counts_as_gone() = runBlocking<Unit> {
        var deleteStatus = HttpStatusCode.NoContent
        val deletes = mutableListOf<String>()
        val harness = openHarness { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Delete -> {
                    deletes += path
                    respond("", deleteStatus, JSON_HEADERS)
                }
                path.endsWith("/photos") -> ok(photos(photo("ph1", "2026-10-02"), photo("ph2", "2026-10-01"), photo("ph3", "2026-09-30")))
                else -> ok(
                    """{"events":[${photoEvent("e1", "ph1", "2026-10-02")},${photoEvent("e2", "ph2", "2026-10-01")},""" +
                        """${photoEvent("e3", "ph3", "2026-09-30")}],"nextCursor":null}""",
                )
            }
        }
        harness.use { box ->
            box.repo.timeline.refresh("p1")
            box.repo.journal.refresh("p1")
            box.repo.timeline.deletePhoto("p1", "ph1")
            deleteStatus = HttpStatusCode.NotFound
            box.repo.timeline.deletePhoto("p1", "ph2")
            assertEquals(listOf("/api/plants/p1/photos/ph1", "/api/plants/p1/photos/ph2"), deletes)
            assertEquals(listOf("ph3"), box.repo.timeline.observe("p1").first().map { it.id })
            assertEquals(listOf("e3"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }

    @Test
    fun deletePhoto_offline_keeps_photo_and_journal_entry() = runBlocking<Unit> {
        var offline = false
        val harness = openHarness { request ->
            if (offline && request.method == HttpMethod.Delete) throw IOException("airplane mode")
            if (request.url.encodedPath.endsWith("/photos")) {
                ok(photos(photo("ph1", "2026-10-02")))
            } else {
                ok("""{"events":[${photoEvent("e1", "ph1", "2026-10-02")}],"nextCursor":null}""")
            }
        }
        harness.use { box ->
            box.repo.timeline.refresh("p1")
            box.repo.journal.refresh("p1")
            offline = true
            val error = assertFailsWith<ApiError> { box.repo.timeline.deletePhoto("p1", "ph1") }
            assertTrue(isHeldForReplay(error))
            assertEquals(listOf("ph1"), box.repo.timeline.observe("p1").first().map { it.id })
            assertEquals(listOf("e1"), box.repo.journal.observe("p1").first().map { it.id })
        }
    }
}
