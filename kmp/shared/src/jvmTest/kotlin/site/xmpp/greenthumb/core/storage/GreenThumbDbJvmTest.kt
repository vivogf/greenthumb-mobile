package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 4 п.1–2: имя файла per-user, Flow из DAO, три таблицы, схема
 * экспортируется, повторное открытие не стирает строки (нет destructive
 * migration).
 */
@OptIn(ExperimentalPathApi::class)
class GreenThumbDbJvmTest {

    private val storageDir = createTempDirectory(prefix = "gt-room")
    private val databases = JvmPlantDatabases(storageDir.toFile())

    @AfterTest
    fun cleanup() {
        listOf("24", "A", "B").forEach { userId ->
            runCatching { databases.delete(userId) }
        }
        storageDir.deleteRecursively()
    }

    @Test
    fun file_name_is_plants_user_id_db() {
        assertEquals("plants_24.db", plantDatabaseFileName("24"))
        assertEquals("plants_A.db", plantDatabaseFileName("A"))
        assertEquals(
            listOf("plants_24.db", "plants_24.db-wal", "plants_24.db-shm", "plants_24.db-journal"),
            plantDatabaseRelatedFileNames("24"),
        )
        assertTrue(databases.absolutePath("24").endsWith("plants_24.db"))
    }

    @Test
    fun file_name_rejects_blank_and_path_separators() {
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName("") }
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName(" ") }
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName("a/b") }
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName("a\\b") }
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName("..") }
        assertFailsWith<IllegalArgumentException> { plantDatabaseFileName("../x") }
    }

    @Test
    fun observe_all_emits_empty_then_insert() = runBlocking {
        val db = databases.open("24")
        val first = CompletableDeferred<List<PlantEntity>>()
        val second = CompletableDeferred<List<PlantEntity>>()
        val job = launch(Dispatchers.IO) {
            db.plants().observeAll().collect { rows ->
                if (!first.isCompleted) {
                    first.complete(rows)
                } else if (!second.isCompleted) {
                    second.complete(rows)
                }
            }
        }
        try {
            withTimeout(10_000) {
                assertEquals(emptyList(), first.await())
                db.plants().upsert(sample("p1"))
                assertEquals(listOf("p1"), second.await().map { it.id })
            }
        } finally {
            job.cancel()
            db.close()
        }
    }

    @Test
    fun primary_key_id_replaces_row() = runBlocking {
        val db = databases.open("24")
        try {
            db.plants().upsert(sample("p1").copy(name = "A"))
            db.plants().upsert(sample("p1").copy(name = "B"))
            assertEquals("B", db.plants().observeAll().first().single().name)
            assertEquals(1, db.plants().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun two_users_do_not_share_rows_or_files() = runBlocking {
        val a = databases.open("A")
        val b = databases.open("B")
        try {
            a.plants().upsert(sample("p-a", userId = "A"))
            b.plants().upsert(sample("p-b", userId = "B"))
            assertEquals(listOf("p-a"), a.plants().observeAll().first().map { it.id })
            assertEquals(listOf("p-b"), b.plants().observeAll().first().map { it.id })
        } finally {
            a.close()
            b.close()
        }
        val dir = storageDir.toFile()
        assertTrue(File(dir, "plants_A.db").isFile)
        assertTrue(File(dir, "plants_B.db").isFile)
    }

    @Test
    fun reopen_does_not_delete_rows() = runBlocking {
        databases.open("24").let { db ->
            db.plants().upsert(sample("p1"))
            db.close()
        }
        val again = databases.open("24")
        try {
            assertEquals(listOf("p1"), again.plants().observeAll().first().map { it.id })
        } finally {
            again.close()
        }
    }

    @Test
    fun delete_removes_db_wal_shm_and_leaves_other_user() = runBlocking {
        val db = databases.open("A")
        db.plants().upsert(sample("p-a", userId = "A"))
        db.close()
        val dir = storageDir.toFile()
        File(dir, "plants_A.db-wal").writeBytes(byteArrayOf(1))
        File(dir, "plants_A.db-shm").writeBytes(byteArrayOf(1))
        File(dir, "plants_B.db").writeBytes(byteArrayOf(1))

        databases.delete("A")

        assertFalse(File(dir, "plants_A.db").exists())
        assertFalse(File(dir, "plants_A.db-wal").exists())
        assertFalse(File(dir, "plants_A.db-shm").exists())
        assertTrue(File(dir, "plants_B.db").isFile)
    }

    @Test
    fun sync_meta_stores_plants_row() = runBlocking {
        val db = databases.open("24")
        try {
            assertNull(db.syncMeta().get(SyncMetaEntity.PLANTS_KEY))
            db.syncMeta().upsert(SyncMetaEntity(SyncMetaEntity.PLANTS_KEY, 1_700_000_000_000))
            val row = db.syncMeta().get(SyncMetaEntity.PLANTS_KEY)
            assertEquals(SyncMetaEntity.PLANTS_KEY, row?.key)
            assertEquals(1_700_000_000_000, row?.updatedAtMillis)
            assertEquals(1_700_000_000_000, db.syncMeta().observe(SyncMetaEntity.PLANTS_KEY).first()?.updatedAtMillis)
        } finally {
            db.close()
        }
    }

    @Test
    fun pending_mutations_list_oldest_first() = runBlocking {
        val db = databases.open("24")
        try {
            db.pendingMutations().insert(
                PendingMutationEntity(
                    id = "later",
                    type = "water",
                    plantId = "p1",
                    payload = "{}",
                    snapshotJson = null,
                    createdAt = 200,
                ),
            )
            db.pendingMutations().insert(
                PendingMutationEntity(
                    id = "earlier",
                    type = "add",
                    plantId = "p1",
                    payload = "{}",
                    snapshotJson = "{\"id\":\"p1\"}",
                    createdAt = 100,
                ),
            )
            assertEquals(listOf("earlier", "later"), db.pendingMutations().listOldestFirst().map { it.id })
            assertEquals(listOf("earlier", "later"), db.pendingMutations().observeAll().first().map { it.id })
        } finally {
            db.close()
        }
    }

    @Test
    fun exported_schema_has_three_tables_and_no_drop() {
        assertTrue(GreenThumbMigrations.ALL.isEmpty(), "v1 has no migrations; additions only later")
        val schemas = File("schemas")
        val jsonFiles = if (schemas.isDirectory) {
            schemas.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        } else {
            emptyList()
        }
        assertTrue(
            jsonFiles.isNotEmpty(),
            "schemaDirectory did not export JSON. dirExists=${schemas.isDirectory}",
        )
        jsonFiles.forEach { file ->
            val text = file.readText()
            assertTrue(text.contains("\"plants\""), "${file.path} missing plants")
            assertTrue(text.contains("sync_meta"), "${file.path} missing sync_meta")
            assertTrue(text.contains("pending_mutations"), "${file.path} missing pending_mutations")
            assertTrue(text.contains("updated_at_millis"), "${file.path} missing updated_at_millis")
            assertTrue(text.contains("snapshot_json"), "${file.path} missing snapshot_json")
            assertTrue(Regex("\"version\"\\s*:\\s*1").containsMatchIn(text), "${file.path} version")
            assertFalse(text.contains("DROP TABLE", ignoreCase = true), "${file.path} drops a table")
            assertFalse(text.contains("DROP COLUMN", ignoreCase = true), "${file.path} drops a column")
        }
    }

    private fun sample(id: String, userId: String = "24"): PlantEntity = PlantEntity(
        id = id,
        userId = userId,
        name = "Ficus",
        location = "Kitchen",
        photoUrl = "",
        waterFrequencyDays = 7,
        lastWateredDate = "2026-09-20",
        fertilizeFrequencyDays = null,
        lastFertilizedDate = null,
        repotFrequencyMonths = null,
        lastRepottedDate = null,
        pruneFrequencyMonths = null,
        lastPrunedDate = null,
        notes = null,
        createdAt = "2026-09-01T00:00:00.000Z",
    )
}
