package site.xmpp.greenthumb.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.storage.PendingMutationEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage 4 п.4 + п.6: досылка журнала после «краша», refresh не затирает
 * открытую строку, HTTP-отказ откатывает снимок, офлайн-очередь идёт
 * по created_at и помечается «не сохранено».
 */
class PendingJournalTest {

    @Test
    fun unsaved_label_is_exported_and_includes_water_all_ids() {
        assertEquals("не сохранено", UnsavedMutation.LABEL)
        val ids = unsavedPlantIds(
            listOf(
                PendingMutationEntity(
                    id = "j-1",
                    type = MutationType.WATER_ALL,
                    plantId = null,
                    payload = ApiClient.json.encodeToString(WaterAllPayload(listOf("a", "b"), TODAY)),
                    snapshotJson = null,
                    createdAt = 1L,
                ),
            ),
        )
        assertEquals(setOf("a", "b"), ids)
    }

    @Test
    fun crash_after_optimistic_water_replays_payload_date_and_converges() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var now = SYNCED_AT
        val harness = openHarness(nowMillis = { now }) { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant())))
            } else {
                started.complete(Unit)
                release.await()
                error("response handler must not run")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val call = async(Dispatchers.IO) { runCatching { box.repo.water("overdue") } }
            withTimeout(10_000) { started.await() }
            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)
            call.cancel()
            release.complete(Unit)
            runCatching { withTimeout(10_000) { call.await() } }
            assertEquals("overdue", box.repo.currentPlants().single().name)
            val journal = box.repo.pendingJournal().single()
            assertEquals(MutationType.WATER, journal.type)
            assertTrue(journal.payload.contains(TODAY))

            box.repo.close()
            now = SYNCED_AT + 86_400_000L
            var patchBody: String? = null
            val paths = mutableListOf<String>()
            val (client2, repo2) = reopenRepository(box.databases, { request ->
                paths += "${request.method.value} ${request.url.encodedPath}"
                if (request.method == HttpMethod.Get) {
                    ok(plantsJson(listOf(overduePlant().copy(lastWateredDate = TODAY, name = "from-server"))))
                } else {
                    patchBody = requestBody(request.body)
                    ok(plantJson(overduePlant().copy(lastWateredDate = TODAY, name = "patched")))
                }
            }, nowMillis = { now })
            try {
                val result = repo2.replayPending()
                assertIs<ReplayResult.Converged>(result)
                assertEquals("""{"last_watered_date":"$TODAY"}""", patchBody)
                assertEquals(
                    listOf("PATCH /api/plants/overdue", "GET /api/plants"),
                    paths,
                )
                val row = repo2.currentPlants().single()
                assertEquals(TODAY, row.lastWateredDate)
                assertEquals("from-server", row.name)
                assertTrue(repo2.pendingJournal().isEmpty())
            } finally {
                repo2.close()
                client2.close()
            }
        }
    }

    @Test
    fun refresh_does_not_overwrite_row_with_open_mutation() = runBlocking<Unit> {
        var remoteName = "overdue"
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant().copy(name = remoteName))))
            } else {
                throw IllegalStateException("offline")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            assertEquals(setOf("overdue"), box.repo.observeUnsavedPlantIds().first())

            remoteName = "server-old"
            box.repo.refresh()

            val row = box.repo.currentPlants().single()
            assertEquals(TODAY, row.lastWateredDate)
            assertEquals("overdue", row.name)
            assertEquals(1, box.repo.pendingJournal().size)
            assertEquals(setOf("overdue"), unsavedPlantIds(box.repo.pendingJournal()))
        }
    }

    @Test
    fun refresh_keeps_pending_add_and_does_not_resurrect_pending_delete() = runBlocking<Unit> {
        var offline = false
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (offline && request.method != HttpMethod.Get) {
                throw IllegalStateException("offline")
            }
            ok(plantsJson(listOf(overduePlant(), healthyPlant())))
        }
        harness.use { box ->
            box.repo.refresh()
            offline = true
            assertFailsWith<ApiError.Network> { box.repo.delete("healthy") }
            assertTrue(box.repo.currentPlants().none { it.id == "healthy" })

            box.repo.refresh()
            assertTrue(box.repo.currentPlants().none { it.id == "healthy" })
            assertEquals(setOf("healthy"), unsavedPlantIds(box.repo.pendingJournal()))

            assertFailsWith<ApiError.Network> { box.repo.add(insert(name = "Queued")) }
            box.repo.refresh()

            val ids = box.repo.currentPlants().map { it.id }.toSet()
            val added = box.repo.pendingJournal().first { it.type == MutationType.ADD }.plantId
            assertTrue(added != null && added in ids)
            assertTrue("healthy" !in ids)
            assertTrue("overdue" in ids)
            assertEquals(setOf("healthy", added), unsavedPlantIds(box.repo.pendingJournal()))
        }
    }

    @Test
    fun replay_server_error_rolls_back_snapshot_and_deletes_journal_row() = runBlocking<Unit> {
        var mode = "list"
        val harness = openHarness { request ->
            when (mode) {
                "list" -> ok(plantsJson(listOf(overduePlant())))
                "offline" -> if (request.method == HttpMethod.Get) {
                    ok(plantsJson(listOf(overduePlant())))
                } else {
                    throw IllegalStateException("offline")
                }
                else -> if (request.method == HttpMethod.Get) {
                    ok(plantsJson(listOf(overduePlant().copy(name = "refreshed"))))
                } else {
                    serverError()
                }
            }
        }
        harness.use { box ->
            box.repo.refresh()
            mode = "offline"
            assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)

            mode = "reject"
            val result = box.repo.replayPending()

            assertIs<ReplayResult.Converged>(result)
            val row = box.repo.currentPlants().single()
            assertEquals("2026-09-01", row.lastWateredDate)
            assertEquals("refreshed", row.name)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun offline_queue_flushes_in_created_at_order_on_replay() = runBlocking<Unit> {
        var now = SYNCED_AT
        var online = false
        val calls = mutableListOf<String>()
        val harness = openHarness(nowMillis = { now }) { request ->
            if (request.method == HttpMethod.Get) {
                calls += "GET ${request.url.encodedPath}"
                val overdueDate = if (online) TODAY else "2026-09-01"
                ok(
                    plantsJson(
                        listOf(
                            overduePlant().copy(lastWateredDate = overdueDate),
                            healthyPlant().copy(lastWateredDate = TODAY),
                        ),
                    ),
                )
            } else {
                calls += "${request.method.value} ${request.url.encodedPath}"
                if (!online) throw IllegalStateException("offline")
                val id = request.url.encodedPath.substringAfterLast('/')
                ok(plantJson(plant(id, TODAY)))
            }
        }
        harness.use { box ->
            box.repo.refresh()
            calls.clear()
            assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            now += 10
            assertFailsWith<ApiError.Network> { box.repo.water("healthy") }
            assertEquals(setOf("overdue", "healthy"), unsavedPlantIds(box.repo.pendingJournal()))
            assertEquals(
                listOf("overdue", "healthy"),
                box.repo.pendingJournal().map { it.plantId },
            )

            calls.clear()
            online = true
            val result = box.repo.replayPending()

            assertIs<ReplayResult.Converged>(result)
            assertEquals(
                listOf(
                    "PATCH /api/plants/overdue",
                    "PATCH /api/plants/healthy",
                    "GET /api/plants",
                ),
                calls,
            )
            assertTrue(box.repo.pendingJournal().isEmpty())
            assertEquals(TODAY, box.repo.currentPlants().byId().getValue("overdue").lastWateredDate)
            assertEquals(TODAY, box.repo.currentPlants().byId().getValue("healthy").lastWateredDate)
        }
    }

    @Test
    fun replay_stops_on_network_and_does_not_send_later_mutation() = runBlocking<Unit> {
        var now = SYNCED_AT
        var replaying = false
        var patches = 0
        var getsDuringReplay = 0
        val harness = openHarness(nowMillis = { now }) { request ->
            if (request.method == HttpMethod.Get) {
                if (replaying) getsDuringReplay++
                ok(plantsJson(listOf(overduePlant(), healthyPlant())))
            } else {
                patches++
                throw IllegalStateException("offline")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            now += 10
            assertFailsWith<ApiError.Network> { box.repo.water("healthy") }
            val patchesBefore = patches

            replaying = true
            val result = box.repo.replayPending()

            assertIs<ReplayResult.Held>(result)
            assertIs<ApiError.Network>(result.cause)
            assertEquals(patchesBefore + 1, patches)
            assertEquals(0, getsDuringReplay)
            assertEquals(listOf("overdue", "healthy"), box.repo.pendingJournal().map { it.plantId })
            assertEquals(TODAY, box.repo.currentPlants().byId().getValue("overdue").lastWateredDate)
        }
    }

    @Test
    fun next_successful_water_drains_older_pending_first() = runBlocking<Unit> {
        var now = SYNCED_AT
        var online = false
        val calls = mutableListOf<String>()
        val harness = openHarness(nowMillis = { now }) { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant(), healthyPlant())))
            } else {
                calls += "${request.method.value} ${request.url.encodedPath}"
                if (!online) throw IllegalStateException("offline")
                val id = request.url.encodedPath.substringAfterLast('/')
                ok(plantJson(plant(id, TODAY, name = "saved-$id")))
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            now += 10
            calls.clear()
            online = true
            box.repo.water("healthy")

            assertEquals(
                listOf("PATCH /api/plants/overdue", "PATCH /api/plants/healthy"),
                calls,
            )
            assertTrue(box.repo.pendingJournal().isEmpty())
            val rows = box.repo.currentPlants().byId()
            assertEquals("saved-overdue", rows.getValue("overdue").name)
            assertEquals("saved-healthy", rows.getValue("healthy").name)
        }
    }

    @Test
    fun water_all_replay_posts_then_refresh_adopts_server_date() = runBlocking<Unit> {
        var online = false
        val calls = mutableListOf<String>()
        val harness = openHarness { request ->
            calls += "${request.method.value} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/api/plants/water-all" -> {
                    if (!online) throw IllegalStateException("offline")
                    ok("""{"success":true,"count":1}""")
                }
                "/api/plants" -> {
                    val date = if (online) "2026-09-20" else "2026-09-01"
                    ok(plantsJson(listOf(overduePlant().copy(lastWateredDate = date))))
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertFailsWith<ApiError.Network> { box.repo.waterAll() }
            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)
            assertEquals(MutationType.WATER_ALL, box.repo.pendingJournal().single().type)
            calls.clear()

            online = true
            val result = box.repo.replayPending()

            assertIs<ReplayResult.Converged>(result)
            assertEquals(
                listOf("POST /api/plants/water-all", "GET /api/plants"),
                calls,
            )
            assertEquals("2026-09-20", box.repo.currentPlants().single().lastWateredDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }
}

private fun List<site.xmpp.greenthumb.core.network.PlantDto>.byId() =
    associateBy { it.id }

private fun insert(name: String) = site.xmpp.greenthumb.core.network.InsertPlantDto(
    name = name,
    location = "shelf",
    waterFrequencyDays = 7,
    lastWateredDate = TODAY,
)
