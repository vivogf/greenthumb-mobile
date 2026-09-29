package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.storage.GreenThumbDb
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Наблюдение против жизненного цикла владельца репозитория (фикс M6 FATAL
 * «Database is closed», прод-трасса PlantDao_Impl.observeAll ←
 * DashboardScreen при рекомпозиции): обращение к закрытой базе при
 * наблюдении не должно ронять процесс, а настоящий сбой живой базы не
 * маскируется.
 */
class PlantRepositoryObservationTest {

    @Test
    fun observation_after_close_completes_without_crash() = runBlocking<Unit> {
        val harness = openObservationHarness { request ->
            if (request.url.encodedPath == "/api/plants") ok(plantsJson(listOf(overduePlant())))
            else error("unexpected ${request.url.encodedPath}")
        }
        harness.use { box ->
            box.repo.refresh()
            assertEquals(listOf("overdue"), box.repo.currentPlants().map { it.id })

            // Владелец закрыл репозиторий (выход/смена аккаунта/смены экрана
            // до фикса). Наблюдение обязано тихо завершиться: было —
            // IllegalStateException «Database is closed» из жадной проверки
            // закрытия в Room createFlow, до коллекции.
            box.repo.close()

            assertTrue(box.repo.observePlants().toList().isEmpty())
            assertTrue(box.repo.observeUnsavedPlantIds().toList().isEmpty())
        }
    }

    @Test
    fun observation_fault_from_open_repository_propagates() = runBlocking<Unit> {
        val harness = openObservationHarness { _ -> ok("[]") }
        harness.use { box ->
            // База закрыта МИМО флага репозитория: репозиторий «жив», ошибка —
            // настоящий сбой данных. Она доходит до коллекционера, не глотается.
            box.db.close()

            assertFailsWith<IllegalStateException> { box.repo.observePlants().toList() }
            assertFailsWith<IllegalStateException> { box.repo.observeUnsavedPlantIds().toList() }
        }
    }

    @Test
    fun observation_on_open_repository_emits_rows() = runBlocking<Unit> {
        val harness = openObservationHarness { request ->
            if (request.url.encodedPath == "/api/plants") ok(plantsJson(listOf(overduePlant())))
            else error("unexpected ${request.url.encodedPath}")
        }
        harness.use { box ->
            box.repo.refresh()
            assertEquals(listOf("overdue"), box.repo.observePlants().first().map { it.id })
        }
    }
}

/** Как RepoHarness, но держит открытый [GreenThumbDb] — для поломки мимо флага. */
@OptIn(ExperimentalPathApi::class)
private class ObservationHarness(
    val dir: File,
    private val client: ApiClient,
    val db: GreenThumbDb,
    val repo: PlantRepository,
) : AutoCloseable {
    override fun close() {
        runCatching { repo.close() }
        runCatching { client.close() }
        dir.toPath().deleteRecursively()
    }
}

@OptIn(ExperimentalPathApi::class)
private fun openObservationHarness(handler: MockRequestHandler): ObservationHarness {
    val dir = createTempDirectory(prefix = "gt-observe").toFile()
    val databases = JvmPlantDatabases(dir)
    val client = ApiClient(MockEngine(handler), NoSessionRecoveryProvider)
    val db = databases.open(USER)
    val repo = PlantRepository(
        userId = USER,
        db = db,
        api = GreenThumbApi(client),
        deleteFiles = { id -> databases.delete(id) },
        clocks = PlantClocks(nowMillis = { SYNCED_AT }, zone = TEST_ZONE),
    )
    return ObservationHarness(dir, client, db, repo)
}
