package site.xmpp.greenthumb.core.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная копия растения. Колонки — все поля `Plant` (RN `shared/schema.ts`,
 * KMP `PlantDto`). Файл базы и так per-user; `user_id` хранится, чтобы строка
 * совпадала с ответом сервера.
 *
 * KSP 2.3.12 встраивает Analysis API 2.3.20: этот файл (и остальные, которые
 * обрабатывает KSP) держать в синтаксисе Kotlin не новее 2.3.
 */
@Entity(tableName = "plants")
public data class PlantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "location")
    val location: String,
    @ColumnInfo(name = "photo_url")
    val photoUrl: String,
    @ColumnInfo(name = "water_frequency_days")
    val waterFrequencyDays: Int,
    @ColumnInfo(name = "last_watered_date")
    val lastWateredDate: String,
    @ColumnInfo(name = "fertilize_frequency_days")
    val fertilizeFrequencyDays: Int?,
    @ColumnInfo(name = "last_fertilized_date")
    val lastFertilizedDate: String?,
    @ColumnInfo(name = "repot_frequency_months")
    val repotFrequencyMonths: Int?,
    @ColumnInfo(name = "last_repotted_date")
    val lastRepottedDate: String?,
    @ColumnInfo(name = "prune_frequency_months")
    val pruneFrequencyMonths: Int?,
    @ColumnInfo(name = "last_pruned_date")
    val lastPrunedDate: String?,
    @ColumnInfo(name = "notes")
    val notes: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)

/**
 * Одна строка на ключ. Для списка растений ключ — [PLANTS_KEY],
 * `updated_at_millis` — момент успешного refresh (баннер «обновлено»).
 */
@Entity(tableName = "sync_meta")
public data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
) {
    public companion object {
        public const val PLANTS_KEY: String = "plants"
    }
}

/**
 * Журнал неподтверждённых мутаций (Stage 4 п.4). Репозиторий пишет сюда
 * в одной транзакции с оптимистичной правкой `plants`. Эта фича только
 * схема: `id`, `type`, `plant_id`, `payload`, `snapshot_json`, `created_at`.
 *
 * `created_at` — epoch millis, чтобы порядок досылки был числовым.
 * `plant_id` и `snapshot_json` nullable: массовая мутация и insert
 * не всегда имеют одну строку-снимок.
 */
@Entity(
    tableName = "pending_mutations",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["plant_id"]),
    ],
)
public data class PendingMutationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "plant_id")
    val plantId: String?,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "snapshot_json")
    val snapshotJson: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
