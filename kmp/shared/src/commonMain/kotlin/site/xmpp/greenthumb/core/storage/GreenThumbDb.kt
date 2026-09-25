package site.xmpp.greenthumb.core.storage

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Версия схемы. Следующая — только через [GreenThumbMigrations], без удаления данных. */
public const val GREEN_THUMB_DB_VERSION: Int = 1

/**
 * Зарегистрированные миграции. Каждая может только ДОБАВЛЯТЬ колонки или
 * таблицы. `DROP TABLE`, `DROP COLUMN` и `fallbackToDestructiveMigration`
 * запрещены: миграция никогда не удаляет данные пользователя.
 */
public object GreenThumbMigrations {
    public val ALL: List<Migration> = emptyList()
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
    ],
    version = GREEN_THUMB_DB_VERSION,
    exportSchema = true,
)
@ConstructedBy(GreenThumbDbConstructor::class)
public abstract class GreenThumbDb : RoomDatabase() {
    public abstract fun plants(): PlantDao

    public abstract fun syncMeta(): SyncMetaDao

    public abstract fun pendingMutations(): PendingMutationDao

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
