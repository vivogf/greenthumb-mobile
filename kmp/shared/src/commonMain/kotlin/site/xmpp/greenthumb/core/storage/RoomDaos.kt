package site.xmpp.greenthumb.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Наблюдение списка — [observeAll]. Пишет репозиторий (следующая фича);
 * здесь только доступ к таблице.
 */
@Dao
public interface PlantDao {
    @Query("SELECT * FROM plants")
    public fun observeAll(): Flow<List<PlantEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: PlantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(entities: List<PlantEntity>)

    @Query("SELECT * FROM plants WHERE id = :id")
    public suspend fun getById(id: String): PlantEntity?

    @Query("SELECT * FROM plants")
    public suspend fun listAll(): List<PlantEntity>

    @Query("DELETE FROM plants WHERE id = :id")
    public suspend fun deleteById(id: String)

    @Query("DELETE FROM plants")
    public suspend fun deleteAll()
}

@Dao
public interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE `key` = :key")
    public suspend fun get(key: String): SyncMetaEntity?

    @Query("SELECT * FROM sync_meta WHERE `key` = :key")
    public fun observe(key: String): Flow<SyncMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: SyncMetaEntity)
}

@Dao
public interface ProgressCacheDao {
    @Query("SELECT * FROM progress_cache WHERE `key` = :key")
    public suspend fun get(key: String): ProgressCacheEntity?

    @Query("SELECT * FROM progress_cache WHERE `key` = :key")
    public fun observe(key: String): Flow<ProgressCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: ProgressCacheEntity)
}

@Dao
public interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY created_at ASC, id ASC")
    public suspend fun listOldestFirst(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations ORDER BY created_at ASC, id ASC")
    public fun observeAll(): Flow<List<PendingMutationEntity>>

    @Insert
    public suspend fun insert(entity: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    public suspend fun deleteById(id: String)
}

/**
 * Транзакционные записи [site.xmpp.greenthumb.data.PlantRepository].
 *
 * Room KMP не даёт `RoomDatabase.withTransaction`. Граница транзакции —
 * `@Transaction` на конкретном методе этого DAO: оптимистичная правка
 * `plants` и строка `pending_mutations` коммитятся вместе, refresh не
 * показывает пустую таблицу между delete и insert.
 *
 * Синтаксис Kotlin ≤2.3: файл обрабатывает KSP 2.3.12.
 */
@Dao
public interface PlantStoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertPlant(entity: PlantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertPlants(entities: List<PlantEntity>)

    @Query("DELETE FROM plants WHERE id = :id")
    public suspend fun deletePlant(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSync(entity: SyncMetaEntity)

    @Insert
    public suspend fun insertPending(entity: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    public suspend fun deletePending(id: String)

    /**
     * Успешный refresh: убрать отсутствующие на сервере id, заменить
     * остальные, отметить sync_meta. Пустой [plants] — успешный пустой
     * список, не ошибка сети.
     */
    @Transaction
    public suspend fun replaceAll(
        plants: List<PlantEntity>,
        removeIds: List<String>,
        syncedAtMillis: Long,
    ) {
        var index = 0
        while (index < removeIds.size) {
            deletePlant(removeIds[index])
            index = index + 1
        }
        if (plants.isNotEmpty()) {
            upsertPlants(plants)
        }
        upsertSync(SyncMetaEntity(SyncMetaEntity.PLANTS_KEY, syncedAtMillis))
    }

    /** Оптимистичная правка одной строки и журнальная строка — один коммит. */
    @Transaction
    public suspend fun savePlantWithPending(plant: PlantEntity, pending: PendingMutationEntity) {
        upsertPlant(plant)
        insertPending(pending)
    }

    /** Оптимистичная правка набора (waterAll) и одна журнальная строка. */
    @Transaction
    public suspend fun savePlantsWithPending(plants: List<PlantEntity>, pending: PendingMutationEntity) {
        upsertPlants(plants)
        insertPending(pending)
    }

    /** Оптимистичное удаление и журнальная строка — один коммит. */
    @Transaction
    public suspend fun deletePlantWithPending(plantId: String, pending: PendingMutationEntity) {
        deletePlant(plantId)
        insertPending(pending)
    }

    /** Откат одной строки снимком и снятие журнальной строки. */
    @Transaction
    public suspend fun restorePlantDropPending(plant: PlantEntity, pendingId: String) {
        upsertPlant(plant)
        deletePending(pendingId)
    }

    /** Откат набора (waterAll) снимком и снятие журнальной строки. */
    @Transaction
    public suspend fun restorePlantsDropPending(plants: List<PlantEntity>, pendingId: String) {
        upsertPlants(plants)
        deletePending(pendingId)
    }

    /** Откат insert: временной строки не было — удалить её и журнал. */
    @Transaction
    public suspend fun deletePlantDropPending(plantId: String, pendingId: String) {
        deletePlant(plantId)
        deletePending(pendingId)
    }

    /**
     * Подтверждение add/update/water: убрать временный id (если он другой),
     * записать ответ сервера, снять журнал.
     */
    @Transaction
    public suspend fun replacePlantDropPending(
        removeId: String,
        plant: PlantEntity,
        pendingId: String,
    ) {
        if (removeId != plant.id) {
            deletePlant(removeId)
        }
        upsertPlant(plant)
        deletePending(pendingId)
    }

    /** Подтверждение: сервер принял, локальные строки уже верные. */
    @Transaction
    public suspend fun dropPending(pendingId: String) {
        deletePending(pendingId)
    }
}

/**
 * Journal cache of one plant. Order matches the server list: newest day
 * first, then newest write, then id.
 *
 * Kotlin syntax ≤2.3: KSP 2.3.12 processes this file.
 */
@Dao
public interface CareEventDao {
    @Query(
        "SELECT * FROM care_events WHERE plant_id = :plantId " +
            "ORDER BY occurred_on DESC, created_at DESC, id DESC",
    )
    public fun observeForPlant(plantId: String): Flow<List<CareEventEntity>>

    @Query("SELECT * FROM care_events WHERE id = :id")
    public suspend fun getById(id: String): CareEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: CareEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(entities: List<CareEventEntity>)

    @Query("DELETE FROM care_events WHERE id = :id")
    public suspend fun deleteById(id: String)

    @Query("DELETE FROM care_events WHERE plant_id = :plantId")
    public suspend fun deleteForPlant(plantId: String)

    @Query("DELETE FROM care_events WHERE photo_id = :photoId")
    public suspend fun deleteByPhotoId(photoId: String)

    /** Successful refresh: the server list replaces this plant's rows in one commit. */
    @Transaction
    public suspend fun replaceForPlant(plantId: String, events: List<CareEventEntity>) {
        deleteForPlant(plantId)
        if (events.isNotEmpty()) {
            upsertAll(events)
        }
    }
}

/** Timeline photo cache of one plant, newest first. */
@Dao
public interface PlantPhotoDao {
    @Query(
        "SELECT * FROM plant_photos WHERE plant_id = :plantId " +
            "ORDER BY taken_on DESC, created_at DESC, id DESC",
    )
    public suspend fun listForPlant(plantId: String): List<PlantPhotoEntity>

    @Query(
        "SELECT * FROM plant_photos WHERE plant_id = :plantId " +
            "ORDER BY taken_on DESC, created_at DESC, id DESC",
    )
    public fun observeForPlant(plantId: String): Flow<List<PlantPhotoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: PlantPhotoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(entities: List<PlantPhotoEntity>)

    @Query("DELETE FROM plant_photos WHERE id = :id")
    public suspend fun deleteById(id: String)

    @Query("DELETE FROM plant_photos WHERE plant_id = :plantId")
    public suspend fun deleteForPlant(plantId: String)

    /** Successful refresh: the server list replaces this plant's rows in one commit. */
    @Transaction
    public suspend fun replaceForPlant(plantId: String, photos: List<PlantPhotoEntity>) {
        deleteForPlant(plantId)
        if (photos.isNotEmpty()) {
            upsertAll(photos)
        }
    }
}
