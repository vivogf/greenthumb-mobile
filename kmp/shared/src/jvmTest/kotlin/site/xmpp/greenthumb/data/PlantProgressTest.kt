package site.xmpp.greenthumb.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.SessionSuperseded
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Progress (architecture §5.8): `GET /api/progress` is cached in the per-user
 * database, so the last online load stays readable offline; ids the app does not
 * know are dropped and ids the server omits show as locked (VAL-PROGRESS-020).
 */
class PlantProgressTest {

    private val body = """{"streak":{"current":3,"best":8},"achievements":[
        {"id":"first_plant","unlocked":true,"unlocked_at":"2026-10-20T10:00:00.000Z"},
        {"id":"plants_5","unlocked":false,"unlocked_at":null},
        {"id":"first_water","unlocked":true,"unlocked_at":"2026-10-21T10:00:00.000Z"},
        {"id":"waterings_25","unlocked":false,"unlocked_at":null},
        {"id":"waterings_100","unlocked":false,"unlocked_at":null},
        {"id":"streak_7","unlocked":true,"unlocked_at":"2026-10-22T10:00:00.000Z"},
        {"id":"streak_30","unlocked":false,"unlocked_at":null},
        {"id":"first_note","unlocked":false,"unlocked_at":null},
        {"id":"first_photo","unlocked":false,"unlocked_at":null}
    ]}"""

    /** Unknown `moon_landing`, `first_note` omitted, `first_water` repeated. */
    private val oddBody = """{"streak":{"current":1,"best":2},"achievements":[
        {"id":"moon_landing","unlocked":true,"unlocked_at":"2026-10-20T10:00:00.000Z"},
        {"id":"first_photo","unlocked":true,"unlocked_at":"2026-10-20T10:00:00.000Z"},
        {"id":"first_water","unlocked":true,"unlocked_at":"2026-10-21T10:00:00.000Z"},
        {"id":"first_water","unlocked":false,"unlocked_at":null},
        {"id":"first_plant","unlocked":false}
    ],"extra_field":42}"""

    @Test
    fun parser_keeps_the_nine_known_ids_in_fixed_order() {
        val view = parseProgress(body)
        assertEquals(3, view.current)
        assertEquals(8, view.best)
        assertEquals(AchievementId.entries.toList(), view.cells.map { it.id })
        assertEquals(
            setOf(AchievementId.FirstPlant, AchievementId.FirstWater, AchievementId.Streak7),
            view.cells.filter { it.unlocked }.map { it.id }.toSet(),
        )
    }

    @Test
    fun parser_drops_unknown_ids_and_locks_missing_ones() {
        val view = parseProgress(oddBody)
        assertEquals(9, view.cells.size)
        assertEquals(AchievementId.entries.toList(), view.cells.map { it.id })
        assertTrue(view.cells.none { it.id.wire == "moon_landing" })
        val byId = view.cells.associateBy { it.id }
        assertFalse(byId.getValue(AchievementId.FirstNote).unlocked, "omitted id shows as locked")
        assertNull(byId.getValue(AchievementId.FirstNote).unlockedAt)
        assertTrue(byId.getValue(AchievementId.FirstPhoto).unlocked)
        assertTrue(byId.getValue(AchievementId.FirstWater).unlocked, "a repeated id keeps its unlocked entry")
        assertFalse(byId.getValue(AchievementId.FirstPlant).unlocked)
    }

    @Test
    fun parser_tolerates_missing_streak() {
        val view = parseProgress("""{"achievements":[]}""")
        assertEquals(0, view.current)
        assertEquals(0, view.best)
        assertTrue(view.cells.none { it.unlocked })
        assertEquals(9, view.cells.size)
    }

    @Test
    fun wire_ids_match_the_server_list() {
        assertEquals(
            listOf(
                "first_plant", "plants_5", "first_water", "waterings_25", "waterings_100",
                "streak_7", "streak_30", "first_note", "first_photo",
            ),
            AchievementId.entries.map { it.wire },
        )
    }

    @Test
    fun refresh_caches_progress_with_its_time() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        openHarness { request ->
            paths += request.url.encodedPath
            ok(body)
        }.use { box ->
            assertNull(box.repo.progress.observe().first(), "nothing cached before the first load")
            val view = box.repo.progress.refresh()
            assertEquals(listOf("/api/progress"), paths)
            val cached = box.repo.progress.observe().first()!!
            assertEquals(view, cached.view)
            assertEquals(SYNCED_AT, cached.updatedAtMillis)
        }
    }

    @Test
    fun failed_refresh_keeps_the_cache() = runBlocking<Unit> {
        var fail = false
        openHarness { _ ->
            if (fail) throw IOException("offline") else ok(body)
        }.use { box ->
            box.repo.progress.refresh()
            fail = true
            assertFailsWith<ApiError.Network> { box.repo.progress.refresh() }
            assertEquals(3, box.repo.progress.observe().first()!!.view.current)
        }
    }

    @Test
    fun server_error_without_cache_leaves_nothing() = runBlocking<Unit> {
        openHarness { _ -> serverError() }.use { box ->
            assertFailsWith<ApiError> { box.repo.progress.refresh() }
            assertNull(box.repo.progress.observe().first())
        }
    }

    @Test
    fun cache_survives_reopen_of_the_database() = runBlocking<Unit> {
        val box = openHarness { _ -> ok(body) }
        try {
            box.repo.progress.refresh()
            box.repo.close()
            box.client.close()
            val (client, repo) = reopenRepository(box.databases, { _ -> throw IOException("offline") })
            try {
                val cached = repo.progress.observe().first()!!
                assertEquals(8, cached.view.best)
                assertEquals(9, cached.view.cells.size)
            } finally {
                repo.close()
                client.close()
            }
        } finally {
            box.dir.deleteRecursively()
        }
    }

    @Test
    fun answer_of_a_replaced_session_is_not_cached() = runBlocking<Unit> {
        val session = AccountSession()
        openHarness(session = session) { _ ->
            session.advance()
            ok(body)
        }.use { box ->
            assertFailsWith<SessionSuperseded> { box.repo.progress.refresh() }
            assertNull(box.repo.progress.observe().first())
        }
    }
}
