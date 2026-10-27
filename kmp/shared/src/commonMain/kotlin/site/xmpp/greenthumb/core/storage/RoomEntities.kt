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
    /** Added in schema v2 ([GreenThumbMigrations.MIGRATION_1_2]). */
    @ColumnInfo(name = "species_id")
    val speciesId: String? = null,
    @ColumnInfo(name = "species_name")
    val speciesName: String? = null,
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

/**
 * Cached server care event (architecture §5.5, `care_events` on the server).
 * Room holds the last confirmed server copy per plant; nothing here is a
 * pending write. Dates are `YYYY-MM-DD`; `created_at` is the server ISO
 * string with microseconds, so text order equals time order.
 *
 * Added in schema v3 ([GreenThumbMigrations.MIGRATION_2_3]).
 */
@Entity(
    tableName = "care_events",
    indices = [
        Index(value = ["plant_id", "occurred_on", "created_at"]),
    ],
)
public data class CareEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "plant_id")
    val plantId: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "occurred_on")
    val occurredOn: String,
    @ColumnInfo(name = "note")
    val note: String?,
    @ColumnInfo(name = "photo_id")
    val photoId: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)

/**
 * Cached timeline photo (server `plant_photos`). Added in schema v3 together
 * with [CareEventEntity] so the timeline needs no further schema bump.
 */
@Entity(
    tableName = "plant_photos",
    indices = [
        Index(value = ["plant_id", "taken_on"]),
    ],
)
public data class PlantPhotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "plant_id")
    val plantId: String,
    @ColumnInfo(name = "url")
    val url: String,
    @ColumnInfo(name = "taken_on")
    val takenOn: String,
    @ColumnInfo(name = "caption")
    val caption: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)
