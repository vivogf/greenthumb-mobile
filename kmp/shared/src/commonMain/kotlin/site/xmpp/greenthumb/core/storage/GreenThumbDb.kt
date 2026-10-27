package site.xmpp.greenthumb.core.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Версия схемы. Следующая — только через [GreenThumbMigrations], без удаления данных. */
public const val GREEN_THUMB_DB_VERSION: Int = 3

/**
 * Зарегистрированные миграции. Каждая может только ДОБАВЛЯТЬ колонки или
 * таблицы. `DROP TABLE`, `DROP COLUMN` и `fallbackToDestructiveMigration`
 * запрещены: миграция никогда не удаляет данные пользователя.
 */
public object GreenThumbMigrations {
    /** v1 → v2: species of a plant (M3). */
    public val SQL_1_2: List<String> = listOf(
        "ALTER TABLE plants ADD COLUMN species_id TEXT",
        "ALTER TABLE plants ADD COLUMN species_name TEXT",
    )

    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(connection: SQLiteConnection) {
            SQL_1_2.forEach { connection.execSQL(it) }
        }
    }

    /**
     * v2 → v3: journal cache tables (M4). The statements equal the
     * `createSql` of the exported `3.json`; Room validates the result on open.
     */
    public val SQL_2_3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `care_events` (`id` TEXT NOT NULL, `plant_id` TEXT NOT NULL, " +
            "`user_id` TEXT NOT NULL, `type` TEXT NOT NULL, `occurred_on` TEXT NOT NULL, `note` TEXT, " +
            "`photo_id` TEXT, `created_at` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_care_events_plant_id_occurred_on_created_at` " +
            "ON `care_events` (`plant_id`, `occurred_on`, `created_at`)",
        "CREATE TABLE IF NOT EXISTS `plant_photos` (`id` TEXT NOT NULL, `plant_id` TEXT NOT NULL, " +
            "`url` TEXT NOT NULL, `taken_on` TEXT NOT NULL, `caption` TEXT, `created_at` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_plant_photos_plant_id_taken_on` " +
            "ON `plant_photos` (`plant_id`, `taken_on`)",
    )

    public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            SQL_2_3.forEach { connection.execSQL(it) }
        }
    }

    public val ALL: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
}

/**
 * Room-база одного пользователя (architecture.md §7). Файл —
 * [plantDatabaseFileName]. Драйвер — sqlite-bundled, одинаковый на android и jvm.
 *
 * KSP генерирует `actual` для [GreenThumbDbConstructor]. Свой actual не писать.
 */
@Database(
    entities = [
        PlantEntity::class,
        SyncMetaEntity::class,
        PendingMutationEntity::class,
        CareEventEntity::class,
        PlantPhotoEntity::class,
    ],
    version = GREEN_THUMB_DB_VERSION,
    exportSchema = true,
)
@ConstructedBy(GreenThumbDbConstructor::class)
public abstract class GreenThumbDb : RoomDatabase() {
    public abstract fun plants(): PlantDao

    public abstract fun syncMeta(): SyncMetaDao

    public abstract fun pendingMutations(): PendingMutationDao

    public abstract fun careEvents(): CareEventDao

    public abstract fun plantPhotos(): PlantPhotoDao

    /** Транзакционные записи репозитория (Room KMP не имеет withTransaction). */
    public abstract fun store(): PlantStoreDao
}

@Suppress(
    "NO_ACTUAL_FOR_EXPECT",
    "KotlinNoActualForExpect",
    "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING",
)
public expect object GreenThumbDbConstructor : RoomDatabaseConstructor<GreenThumbDb> {
    override fun initialize(): GreenThumbDb
}

/**
 * Единственное место, где билдер превращается в базу.
 * Destructive fallback намеренно не вызывается.
 */
public fun RoomDatabase.Builder<GreenThumbDb>.openGreenThumb(): GreenThumbDb {
    GreenThumbMigrations.ALL.forEach { migration ->
        addMigrations(migration)
    }
    return setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
