@file:UseSerializers(PatchSerializer::class)

package site.xmpp.greenthumb.core.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Растение в ответе сервера — порт `Plant` из RN `shared/schema.ts:25-41`.
 * Даты — `YYYY-MM-DD` (никогда ISO со временем), `created_at` — ISO-строка.
 *
 * `id`/`user_id` — [StringOrNumberAsStringSerializer] (формат провода не
 * подтверждён; число не должно ронять разбор). `user_id` сервер пишет строкой
 * (`server/routes.ts:179`: `user_id: String(userId)`), но исторические данные
 * могли быть числом.
 */
@Serializable
data class PlantDto(
    @SerialName("id")
    @kotlinx.serialization.Serializable(with = StringOrNumberAsStringSerializer::class)
    val id: String,
    @SerialName("user_id")
    @kotlinx.serialization.Serializable(with = StringOrNumberAsStringSerializer::class)
    val userId: String,
    val name: String,
    val location: String,
    @SerialName("photo_url")
    val photoUrl: String,
    @SerialName("water_frequency_days")
    val waterFrequencyDays: Int,
    @SerialName("last_watered_date")
    val lastWateredDate: String,
    @SerialName("fertilize_frequency_days")
    val fertilizeFrequencyDays: Int? = null,
    @SerialName("last_fertilized_date")
    val lastFertilizedDate: String? = null,
    @SerialName("repot_frequency_months")
    val repotFrequencyMonths: Int? = null,
    @SerialName("last_repotted_date")
    val lastRepottedDate: String? = null,
    @SerialName("prune_frequency_months")
    val pruneFrequencyMonths: Int? = null,
    @SerialName("last_pruned_date")
    val lastPrunedDate: String? = null,
    val notes: String = "",
    @SerialName("created_at")
    val createdAt: String,
)

/** Пользователь в ответах auth-эндпоинтов — порт `User` из RN `shared/schema.ts:14-21`. */
@Serializable
data class UserDto(
    @SerialName("id")
    @kotlinx.serialization.Serializable(with = StringOrNumberAsStringSerializer::class)
    val id: String,
    val name: String? = null,
    @SerialName("notification_time")
    val notificationTime: String? = null,
    val timezone: String? = null,
    @SerialName("last_notified_date")
    val lastNotifiedDate: String? = null,
    @SerialName("recovery_key")
    val recoveryKey: String,
    @SerialName("created_at")
    val createdAt: String,
)

/** Платформа в FCM-подписке; значение провода — `android`/`ios` (план Stage 9). */
@Serializable
enum class PlatformDto {
    @SerialName("android") android,
    @SerialName("ios") ios,
}

/** Тело `POST /api/push/subscribe-fcm` (план Stage 9; backend-contract.md). */
@Serializable
data class FcmSubscriptionRequest(
    @SerialName("fcm_token")
    val fcmToken: String,
    val platform: PlatformDto,
    val language: String,
)

/** Тело создания растения — порт `insertPlantSchema` (RN `shared/schema.ts:44-57`). */
@Serializable
data class InsertPlantDto(
    val name: String,
    val location: String,
    /**
     * RN всегда шлёт photo_url ("" без фото, `app/add-plant.tsx:96-101`), серверный
     * zod требует поле (400 `photo_url: Required` проверено live 2026-09-27) —
     * поэтому ALWAYS: ключ пишется и для дефолта "" (encodeDefaults=false
     * выбрасывал бы его как равный дефолту).
     */
    @SerialName("photo_url")
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val photoUrl: String = "",
    @SerialName("water_frequency_days")
    val waterFrequencyDays: Int,
    @SerialName("last_watered_date")
    val lastWateredDate: String,
    val notes: String = "",
    @SerialName("fertilize_frequency_days")
    val fertilizeFrequencyDays: Int? = null,
    @SerialName("last_fertilized_date")
    val lastFertilizedDate: String? = null,
    @SerialName("repot_frequency_months")
    val repotFrequencyMonths: Int? = null,
    @SerialName("last_repotted_date")
    val lastRepottedDate: String? = null,
    @SerialName("prune_frequency_months")
    val pruneFrequencyMonths: Int? = null,
    @SerialName("last_pruned_date")
    val lastPrunedDate: String? = null,
)

/**
 * Частичное обновление растения для PATCH /api/plants/:id (план Stage 2 п.5).
 * Каждое поле — [Patch]: Absent (не трогать) / Value (задать) / Null (сбросить
 * колонку сервером). Дефолты [Patch.Absent] не пишутся (encodeDefaults=false).
 */
@Serializable
data class PatchPlantDto(
    val name: Patch<String> = Patch.Absent,
    val location: Patch<String> = Patch.Absent,
    @SerialName("photo_url")
    val photoUrl: Patch<String> = Patch.Absent,
    @SerialName("water_frequency_days")
    val waterFrequencyDays: Patch<Int> = Patch.Absent,
    @SerialName("last_watered_date")
    val lastWateredDate: Patch<String> = Patch.Absent,
    @SerialName("fertilize_frequency_days")
    val fertilizeFrequencyDays: Patch<Int> = Patch.Absent,
    @SerialName("last_fertilized_date")
    val lastFertilizedDate: Patch<String> = Patch.Absent,
    @SerialName("repot_frequency_months")
    val repotFrequencyMonths: Patch<Int> = Patch.Absent,
    @SerialName("last_repotted_date")
    val lastRepottedDate: Patch<String> = Patch.Absent,
    @SerialName("prune_frequency_months")
    val pruneFrequencyMonths: Patch<Int> = Patch.Absent,
    @SerialName("last_pruned_date")
    val lastPrunedDate: Patch<String> = Patch.Absent,
    val notes: Patch<String> = Patch.Absent,
) {
    companion object
}

// ---------------------------------------------------------------------
// Конверты ответов сервера (server/routes.ts; backend-contract.md)
// ---------------------------------------------------------------------

/** `{ user: ... }` — ответ auth-эндпоинтов. */
@Serializable
data class UserEnvelope(val user: UserDto)

/** `{ success: true, count: N }` — ответ вод-эндпоинтов. */
@Serializable
data class MassActionResult(val success: Boolean = false, val count: Int = 0)

/** `{ success: true }` — ответ logout/delete-эндпоинтов. */
@Serializable
data class SuccessResult(val success: Boolean = false)

/** `{ ok: true }` — ответ expo/fcm subscribe. */
@Serializable
data class OkResult(val ok: Boolean = false)

/** `{ subscribed: bool }` — ответ subscription-status эндпоинтов. */
@Serializable
data class SubscriptionStatus(val subscribed: Boolean = false)
