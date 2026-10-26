package site.xmpp.greenthumb.core.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Room v1 → v2 (species columns). A database written by the v1 schema
 * (exported `schemas/.../1.json`) opens with the current code and keeps
 * every plant, pending mutation and sync row.
 */
@OptIn(ExperimentalPathApi::class)
class GreenThumbDbMigrationTest {

    private val dir = createTempDirectory(prefix = "gt-room-mig")
    private val databases = JvmPlantDatabases(dir.toFile())

    @AfterTest
    fun cleanup() {
        runCatching { databases.delete("u1") }
        dir.deleteRecursively()
    }

    private fun createV1Database(path: String) {
        val schema = Json.parseToJsonElement(
            File("schemas/site.xmpp.greenthumb.core.storage.GreenThumbDb/1.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        val connection = BundledSQLiteDriver().open(path)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                connection.execSQL(
                    entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table),
                )
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table),
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL(
                "INSERT INTO plants (id, user_id, name, location, photo_url, water_frequency_days, " +
                    "last_watered_date, fertilize_frequency_days, notes, created_at) VALUES " +
                    "('p1', 'u1', 'Old fern', 'Hall', '', 5, '2026-10-01', 30, 'keep me', '2026-09-01T00:00:00.000Z')",
            )
            connection.execSQL(
                "INSERT INTO pending_mutations (id, type, plant_id, payload, snapshot_json, created_at) " +
                    "VALUES ('m1', 'water', 'p1', '{\"last_watered_date\":\"2026-10-02\"}', NULL, 100)",
            )
            connection.execSQL("INSERT INTO sync_meta (key, updated_at_millis) VALUES ('plants', 1700000000000)")
            connection.execSQL("PRAGMA user_version = 1")
        } finally {
            connection.close()
        }
    }

    @Test
    fun v1_database_migrates_to_current_version_without_data_loss() = runBlocking {
        assertEquals(2, GREEN_THUMB_DB_VERSION)
        createV1Database(databases.absolutePath("u1"))

        val db = databases.open("u1")
        try {
            val plant = db.plants().observeAll().first().single()
            assertEquals("p1", plant.id)
            assertEquals("Old fern", plant.name)
            assertEquals(30, plant.fertilizeFrequencyDays)
            assertEquals("keep me", plant.notes)
            assertNull(plant.speciesId)
            assertNull(plant.speciesName)
            assertEquals(listOf("m1"), db.pendingMutations().listOldestFirst().map { it.id })
            assertEquals(1_700_000_000_000, db.syncMeta().get(SyncMetaEntity.PLANTS_KEY)?.updatedAtMillis)

            db.plants().upsert(plant.copy(speciesId = "a", speciesName = "Sci a"))
            assertEquals("a", db.plants().observeAll().first().single().speciesId)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_only_adds_columns() {
        GreenThumbMigrations.ALL.forEach { migration ->
            assertEquals(migration.startVersion + 1, migration.endVersion)
        }
        assertEquals(listOf(1 to 2), GreenThumbMigrations.ALL.map { it.startVersion to it.endVersion })
        GreenThumbMigrations.SQL_1_2.forEach { statement ->
            kotlin.test.assertTrue(statement.startsWith("ALTER TABLE plants ADD COLUMN"), statement)
        }
    }
}
