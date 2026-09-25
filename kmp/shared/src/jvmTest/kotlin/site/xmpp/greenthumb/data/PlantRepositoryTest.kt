package site.xmpp.greenthumb.data

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.storage.plantDatabaseRelatedFileNames
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage 4 п.3 + п.7: refresh не чистит таблицу при ошибке; water/waterAll
 * оптимистичны и откатываются на 500; postponeAll не двигает observePlants
 * до ответа; эффект отделён от записи.
 */
class PlantRepositoryTest {

    @Test
    fun refresh_error_does_not_clear_table_or_sync_meta() = runBlocking<Unit> {
        var fail = false
        val harness = openHarness { request ->
            assertEquals("/api/plants", request.url.encodedPath)
            if (fail) serverError() else ok(plantsJson(listOf(overduePlant(), healthyPlant())))
        }
        harness.use { box ->
            box.repo.refresh()
            val before = box.repo.currentPlants().byId()
            val synced = box.repo.lastSyncedAtMillis()
            fail = true

            val error = assertFailsWith<ApiError.Server> { box.repo.refresh() }

            assertEquals(500, error.status)
            assertEquals(before, box.repo.currentPlants().byId())
            assertEquals(synced, box.repo.lastSyncedAtMillis())
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun refresh_success_replaces_rows_and_writes_sync_meta() = runBlocking<Unit> {
        var second = false
        val harness = openHarness { _ ->
            if (!second) ok(plantsJson(listOf(overduePlant())))
            else ok(plantsJson(listOf(healthyPlant())))
        }
        harness.use { box ->
            assertEquals(null, box.repo.lastSyncedAtMillis())
            box.repo.refresh()
            assertEquals(setOf("overdue"), box.repo.currentPlants().map { it.id }.toSet())
            assertEquals(SYNCED_AT, box.repo.lastSyncedAtMillis())

            second = true
            box.repo.refresh()
            assertEquals(setOf("healthy"), box.repo.currentPlants().map { it.id }.toSet())
            assertEquals(SYNCED_AT, box.repo.lastSyncedAtMillis())
        }
    }

    @Test
    fun refresh_empty_success_clears_rows() = runBlocking<Unit> {
        var empty = false
        val harness = openHarness { _ ->
            if (!empty) ok(plantsJson(listOf(healthyPlant()))) else ok("[]")
        }
        harness.use { box ->
            box.repo.refresh()
            empty = true
            box.repo.refresh()
            assertTrue(box.repo.currentPlants().isEmpty())
            assertEquals(SYNCED_AT, box.repo.lastSyncedAtMillis())
        }
    }

    @Test
    fun water_is_optimistic_then_500_rolls_back_and_journal_is_empty() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness { request ->
            if (request.url.encodedPath == "/api/plants") {
                ok(plantsJson(listOf(overduePlant())))
            } else {
                started.complete(Unit)
                release.await()
                serverError()
            }
        }
        harness.use { box ->
            box.repo.refresh()
            // ApiError — Throwable, не Exception: необработанный вылет из async
            // роняет воркер, а не приходит в await. Ловим внутри корутины.
            val call = async(Dispatchers.IO) {
                runCatching { box.repo.water("overdue") }
            }
            withTimeout(10_000) { started.await() }

            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)
            assertEquals(1, box.repo.pendingJournal().size)
            assertEquals(MutationType.WATER, box.repo.pendingJournal().single().type)

            release.complete(Unit)
            val error = call.await().exceptionOrNull()
            assertIs<ApiError.Server>(error)
            assertEquals(500, error.status)
            assertEquals("2026-09-01", box.repo.currentPlants().single().lastWateredDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun water_200_keeps_server_date_and_clears_journal() = runBlocking<Unit> {
        var body: String? = null
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant())))
            } else {
                body = requestBody(request.body)
                ok(plantJson(overduePlant().copy(lastWateredDate = TODAY, name = "from-server")))
            }
        }
        harness.use { box ->
            box.repo.refresh()
            box.repo.water("overdue")
            assertEquals("""{"last_watered_date":"$TODAY"}""", body)
            val row = box.repo.currentPlants().single()
            assertEquals(TODAY, row.lastWateredDate)
            assertEquals("from-server", row.name)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun water_all_changes_only_non_healthy_and_rolls_back_the_set() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness { request ->
            when (request.url.encodedPath) {
                "/api/plants" -> ok(plantsJson(listOf(overduePlant(), dueTodayPlant(), healthyPlant())))
                "/api/plants/water-all" -> {
                    started.complete(Unit)
                    release.await()
                    serverError()
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val call = async(Dispatchers.IO) { runCatching { box.repo.waterAll() } }
            withTimeout(10_000) { started.await() }

            val mid = box.repo.currentPlants().byId()
            assertEquals(TODAY, mid.getValue("overdue").lastWateredDate)
            assertEquals(TODAY, mid.getValue("due").lastWateredDate)
            assertEquals("2026-09-24", mid.getValue("healthy").lastWateredDate)
            val journal = box.repo.pendingJournal().single()
            assertEquals(MutationType.WATER_ALL, journal.type)
            val payload = ApiClient.json.decodeFromString<WaterAllPayload>(journal.payload)
            assertEquals(setOf("overdue", "due"), payload.plantIds.toSet())

            release.complete(Unit)
            assertIs<ApiError.Server>(call.await().exceptionOrNull())
            val after = box.repo.currentPlants().byId()
            assertEquals("2026-09-01", after.getValue("overdue").lastWateredDate)
            assertEquals("2026-09-18", after.getValue("due").lastWateredDate)
            assertEquals("2026-09-24", after.getValue("healthy").lastWateredDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun water_all_200_keeps_non_healthy_dates() = runBlocking<Unit> {
        val harness = openHarness { request ->
            when (request.url.encodedPath) {
                "/api/plants" -> ok(plantsJson(listOf(overduePlant(), healthyPlant())))
                "/api/plants/water-all" -> ok("""{"success":true,"count":1}""")
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            box.repo.waterAll()
            val rows = box.repo.currentPlants().byId()
            assertEquals(TODAY, rows.getValue("overdue").lastWateredDate)
            assertEquals("2026-09-24", rows.getValue("healthy").lastWateredDate)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun water_all_writes_immediately_and_emits_effect_before_response() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness { request ->
            when (request.url.encodedPath) {
                "/api/plants" -> ok(plantsJson(listOf(overduePlant())))
                "/api/plants/water-all" -> {
                    started.complete(Unit)
                    release.await()
                    ok("""{"success":true,"count":1}""")
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val seen = CompletableDeferred<PlantEffect>()
            val collect = launch(Dispatchers.IO) {
                box.repo.effects.collect { effect ->
                    if (!seen.isCompleted) seen.complete(effect)
                }
            }
            withTimeout(10_000) { box.repo.effectSubscriptions.first { it >= 1 } }
            val call = async(Dispatchers.IO) { box.repo.waterAll() }
            withTimeout(10_000) { started.await() }

            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)
            val effect = withTimeout(10_000) { seen.await() }
            assertIs<PlantEffect.WaterAll>(effect)
            assertEquals(listOf("overdue"), effect.plantIds)
            assertTrue(!release.isCompleted, "эффект пришёл до ответа сервера")

            release.complete(Unit)
            call.await()
            collect.cancel()
        }
    }

    @Test
    fun postpone_all_does_not_change_observe_plants_until_response() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var getsDuringHold = 0
        val original = listOf(overduePlant(), healthyPlant())
        val updated = listOf(overduePlant().copy(lastWateredDate = "2026-09-24"), healthyPlant())
        val harness = openHarness { request ->
            when (request.url.encodedPath) {
                "/api/plants/postpone-all" -> {
                    started.complete(Unit)
                    release.await()
                    ok("""{"success":true,"count":1}""")
                }
                "/api/plants" -> {
                    if (started.isCompleted && !release.isCompleted) getsDuringHold++
                    ok(plantsJson(if (release.isCompleted) updated else original))
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val seen = mutableListOf<Map<String, String>>()
            val collect = launch(Dispatchers.IO) {
                box.repo.observePlants().collect { rows ->
                    seen.add(rows.associate { it.id to it.lastWateredDate })
                }
            }
            withTimeout(10_000) {
                while (seen.isEmpty()) delay(20)
            }
            val call = async(Dispatchers.IO) { box.repo.postponeAll() }
            withTimeout(10_000) { started.await() }
            delay(200)

            assertEquals(0, getsDuringHold, "refresh не зовётся, пока postpone не ответил")
            assertEquals("2026-09-01", box.repo.currentPlants().byId().getValue("overdue").lastWateredDate)
            assertTrue(seen.all { it["overdue"] == "2026-09-01" })

            release.complete(Unit)
            call.await()
            withTimeout(10_000) {
                while (box.repo.currentPlants().byId()["overdue"]?.lastWateredDate != "2026-09-24") {
                    delay(20)
                }
            }
            assertEquals("2026-09-24", box.repo.currentPlants().byId().getValue("overdue").lastWateredDate)
            collect.cancel()
        }
    }

    @Test
    fun postpone_all_error_does_not_refresh() = runBlocking<Unit> {
        var gets = 0
        val harness = openHarness { request ->
            when (request.url.encodedPath) {
                "/api/plants" -> {
                    gets++
                    ok(plantsJson(listOf(overduePlant())))
                }
                "/api/plants/postpone-all" -> serverError()
                else -> error("unexpected ${request.url.encodedPath}")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertEquals(1, gets)
            assertFailsWith<ApiError.Server> { box.repo.postponeAll() }
            assertEquals(1, gets)
            assertEquals("2026-09-01", box.repo.currentPlants().single().lastWateredDate)
        }
    }

    @Test
    fun add_is_optimistic_and_rolls_back() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) {
                ok("[]")
            } else {
                started.complete(Unit)
                release.await()
                serverError()
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val call = async(Dispatchers.IO) { runCatching { box.repo.add(insert()) } }
            withTimeout(10_000) { started.await() }
            assertEquals(listOf("temp-1"), box.repo.currentPlants().map { it.id })
            assertEquals(MutationType.ADD, box.repo.pendingJournal().single().type)

            release.complete(Unit)
            assertIs<ApiError.Server>(call.await().exceptionOrNull())
            assertTrue(box.repo.currentPlants().isEmpty())
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun add_200_replaces_temp_id_with_server_id() = runBlocking<Unit> {
        val harness = openHarness(ids = ArrayDeque(listOf("temp-1"))) { request ->
            if (request.method == HttpMethod.Get) ok("[]")
            else ok(plantJson(healthyPlant().copy(id = "server-1", name = "Ficus")))
        }
        harness.use { box ->
            box.repo.refresh()
            val saved = box.repo.add(insert(name = "Ficus"))
            assertEquals("server-1", saved.id)
            assertEquals(listOf("server-1"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun update_is_optimistic_and_rolls_back() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(healthyPlant())))
            } else {
                started.complete(Unit)
                release.await()
                serverError()
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val call = async(Dispatchers.IO) {
                runCatching { box.repo.update("healthy", PatchPlantDto(name = Patch.Value("Renamed"))) }
            }
            withTimeout(10_000) { started.await() }
            assertEquals("Renamed", box.repo.currentPlants().single().name)

            release.complete(Unit)
            assertIs<ApiError.Server>(call.await().exceptionOrNull())
            assertEquals("healthy", box.repo.currentPlants().single().name)
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun delete_is_optimistic_and_rolls_back() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(healthyPlant())))
            } else {
                started.complete(Unit)
                release.await()
                serverError()
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val call = async(Dispatchers.IO) { runCatching { box.repo.delete("healthy") } }
            withTimeout(10_000) { started.await() }
            assertTrue(box.repo.currentPlants().isEmpty())
            assertEquals(MutationType.DELETE, box.repo.pendingJournal().single().type)

            release.complete(Unit)
            assertIs<ApiError.Server>(call.await().exceptionOrNull())
            assertEquals(listOf("healthy"), box.repo.currentPlants().map { it.id })
            assertTrue(box.repo.pendingJournal().isEmpty())
        }
    }

    @Test
    fun network_error_keeps_optimistic_water_and_journal() = runBlocking<Unit> {
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant())))
            } else {
                throw IllegalStateException("Connection reset")
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val error = assertFailsWith<ApiError.Network> { box.repo.water("overdue") }
            assertEquals(ApiError.Network, error)
            assertEquals(TODAY, box.repo.currentPlants().single().lastWateredDate)
            assertEquals(1, box.repo.pendingJournal().size)
        }
    }

    @Test
    fun delete_database_for_user_removes_own_files_and_leaves_other() = runBlocking<Unit> {
        val harness = openHarness { _ -> ok(plantsJson(listOf(healthyPlant()))) }
        harness.use { box ->
            box.repo.refresh()
            val own = box.databases.absolutePath(USER)
            assertTrue(File(own).isFile)
            File(box.dir, "plants_other.db").writeBytes(byteArrayOf(1))
            File(box.dir, "plants_$USER.db-wal").writeBytes(byteArrayOf(1))

            box.repo.deleteDatabaseForUser(USER)

            plantDatabaseRelatedFileNames(USER).forEach { name ->
                assertTrue(!File(box.dir, name).exists(), name)
            }
            assertTrue(File(box.dir, "plants_other.db").isFile)
        }
    }
}

private fun insert(name: String = "Ficus"): InsertPlantDto = InsertPlantDto(
    name = name,
    location = "shelf",
    waterFrequencyDays = 7,
    lastWateredDate = TODAY,
)

private fun List<PlantDto>.byId(): Map<String, PlantDto> = associateBy { it.id }
