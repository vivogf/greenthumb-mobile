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
import kotlin.test.assertTrue

/**
 * Room migrations on real fixtures: a database written by an exported
 * schema (`schemas/.../N.json`) opens with the current code and keeps every
 * plant, pending mutation and sync row. v1 goes through the whole chain
 * 1→2→3; v2 goes through 2→3 only.
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

    private fun createFixture(path: String, version: Int) {
        val schema = Json.parseToJsonElement(
            File("schemas/site.xmpp.greenthumb.core.storage.GreenThumbDb/$version.json").readText(),
        ).jsonObject.getValue("database").jsonObject
        assertEquals(version, schema.getValue("version").jsonPrimitive.content.toInt())
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
            if (version >= 2) {
                connection.execSQL(
                    "INSERT INTO plants (id, user_id, name, location, photo_url, water_frequency_days, " +
                        "last_watered_date, notes, created_at, species_id, species_name) VALUES " +
                        "('p2', 'u1', 'Monstera', 'Desk', '', 7, '2026-10-03', '', '2026-09-02T00:00:00.000Z', " +
                        "'monstera-deliciosa', 'Monstera deliciosa')",
                )
            }
            connection.execSQL(
                "INSERT INTO pending_mutations (id, type, plant_id, payload, snapshot_json, created_at) " +
                    "VALUES ('m1', 'water', 'p1', '{\"last_watered_date\":\"2026-10-02\"}', NULL, 100)",
            )
            connection.execSQL("INSERT INTO sync_meta (key, updated_at_millis) VALUES ('plants', 1700000000000)")
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    private suspend fun assertCommonRowsKept(db: GreenThumbDb) {
        val plant = db.plants().getById("p1")!!
        assertEquals("Old fern", plant.name)
        assertEquals("Hall", plant.location)
        assertEquals(5, plant.waterFrequencyDays)
        assertEquals("2026-10-01", plant.lastWateredDate)
        assertEquals(30, plant.fertilizeFrequencyDays)
        assertEquals("keep me", plant.notes)
        assertEquals(listOf("m1"), db.pendingMutations().listOldestFirst().map { it.id })
        assertEquals(1_700_000_000_000, db.syncMeta().get(SyncMetaEntity.PLANTS_KEY)?.updatedAtMillis)
    }

    private suspend fun assertJournalTablesUsable(db: GreenThumbDb) {
        assertTrue(db.careEvents().observeForPlant("p1").first().isEmpty())
        db.careEvents().upsert(
            CareEventEntity(
                id = "e1",
                plantId = "p1",
                userId = "u1",
                type = "note",
                occurredOn = "2026-10-04",
                note = "after migration",
                photoId = null,
                createdAt = "2026-10-04T10:00:00.000000Z",
            ),
        )
        assertEquals(listOf("after migration"), db.careEvents().observeForPlant("p1").first().map { it.note })
        db.plantPhotos().upsert(
            PlantPhotoEntity(
                id = "ph1",
                plantId = "p1",
                url = "https://example.invalid/p.jpg",
                takenOn = "2026-10-04",
                caption = null,
                createdAt = "2026-10-04T10:00:00.000000Z",
            ),
        )
        assertEquals(listOf("ph1"), db.plantPhotos().listForPlant("p1").map { it.id })
    }

    @Test
    fun current_version_is_3() {
        assertEquals(3, GREEN_THUMB_DB_VERSION)
    }

    @Test
    fun v1_database_migrates_through_chain_without_data_loss() = runBlocking {
        createFixture(databases.absolutePath("u1"), version = 1)

        val db = databases.open("u1")
        try {
            assertEquals(listOf("p1"), db.plants().listAll().map { it.id })
            assertCommonRowsKept(db)
            val plant = db.plants().getById("p1")!!
            assertNull(plant.speciesId)
            assertNull(plant.speciesName)
            db.plants().upsert(plant.copy(speciesId = "a", speciesName = "Sci a"))
            assertEquals("a", db.plants().getById("p1")?.speciesId)
            assertJournalTablesUsable(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun v2_database_migrates_to_v3_without_data_loss() = runBlocking {
        createFixture(databases.absolutePath("u1"), version = 2)

        val db = databases.open("u1")
        try {
            assertEquals(setOf("p1", "p2"), db.plants().listAll().map { it.id }.toSet())
            assertCommonRowsKept(db)
            val monstera = db.plants().getById("p2")!!
            assertEquals("monstera-deliciosa", monstera.speciesId)
            assertEquals("Monstera deliciosa", monstera.speciesName)
            assertEquals("2026-10-03", monstera.lastWateredDate)
            assertJournalTablesUsable(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrated_database_reopens_without_another_migration() = runBlocking {
        createFixture(databases.absolutePath("u1"), version = 1)
        databases.open("u1").close()
        val db = databases.open("u1")
        try {
            assertCommonRowsKept(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun sql_2_3_matches_exported_v3_schema() {
        val entities = Json.parseToJsonElement(
            File("schemas/site.xmpp.greenthumb.core.storage.GreenThumbDb/3.json").readText(),
        ).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
        val expected = entities
            .map { it.jsonObject }
            .filter { it.getValue("tableName").jsonPrimitive.content in setOf("care_events", "plant_photos") }
            .flatMap { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val statements = listOf(entity.getValue("createSql").jsonPrimitive.content) +
                    entity.getValue("indices").jsonArray.map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
                statements.map { it.replace("\${TABLE_NAME}", table) }
            }
        assertEquals(expected, GreenThumbMigrations.SQL_2_3)
    }

    @Test
    fun migrations_are_additive_steps() {
        GreenThumbMigrations.ALL.forEach { migration ->
            assertEquals(migration.startVersion + 1, migration.endVersion)
        }
        assertEquals(listOf(1 to 2, 2 to 3), GreenThumbMigrations.ALL.map { it.startVersion to it.endVersion })
        GreenThumbMigrations.SQL_1_2.forEach { statement ->
            assertTrue(statement.startsWith("ALTER TABLE plants ADD COLUMN"), statement)
        }
        GreenThumbMigrations.SQL_2_3.forEach { statement ->
            assertTrue(
                statement.startsWith("CREATE TABLE IF NOT EXISTS") || statement.startsWith("CREATE INDEX IF NOT EXISTS"),
                statement,
            )
            assertTrue("DROP" !in statement.uppercase() && "DELETE" !in statement.uppercase(), statement)
        }
    }
}
