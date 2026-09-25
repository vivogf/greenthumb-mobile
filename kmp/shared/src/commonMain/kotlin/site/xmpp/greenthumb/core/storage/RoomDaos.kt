package site.xmpp.greenthumb.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
public interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY created_at ASC")
    public suspend fun listOldestFirst(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations ORDER BY created_at ASC")
    public fun observeAll(): Flow<List<PendingMutationEntity>>

    @Insert
    public suspend fun insert(entity: PendingMutationEntity)

    @Query("DELETE FROM pending_mutations WHERE id = :id")
    public suspend fun deleteById(id: String)
}
