package site.xmpp.greenthumb.data

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Порог баннера «обновлено в HH:mm» — порт `app/(tabs)/index.tsx`
 * (`lastSyncedAgeMs >= 60_000`). Ровно 60 с уже показывает баннер.
 */
public const val BANNER_AFTER_MILLIS: Long = 60_000L

/**
 * Иконка офлайна на баннере — порт того же места (`>= 300_000`).
 * Это не полоса «нет сети»: та питается от Connectivity.
 */
public const val OFFLINE_ICON_AFTER_MILLIS: Long = 300_000L

/**
 * Состояние баннера от `sync_meta.updated_at_millis`.
 *
 * [syncedAtLabel] — время последней успешной синхронизации (`HH:mm`),
 * не «сейчас». null, только если синхронизации ещё не было.
 * Экран (M7) прячет баннер при пустом списке — здесь только возраст.
 */
public data class SyncBanner(
    val visible: Boolean,
    val showOfflineIcon: Boolean,
    val syncedAtLabel: String?,
)

/**
 * Баннер от момента успешного refresh и инжектированных часов.
 * Возраст < 0 (часы уехали вперёд) — ещё свежие, баннера нет.
 */
public fun syncBanner(
    updatedAtMillis: Long?,
    nowMillis: Long,
    zone: TimeZone,
): SyncBanner {
    if (updatedAtMillis == null) {
        return SyncBanner(visible = false, showOfflineIcon = false, syncedAtLabel = null)
    }
    val age = nowMillis - updatedAtMillis
    val label = formatSyncTime(updatedAtMillis, zone)
    if (age < BANNER_AFTER_MILLIS) {
        return SyncBanner(visible = false, showOfflineIcon = false, syncedAtLabel = label)
    }
    return SyncBanner(
        visible = true,
        showOfflineIcon = age >= OFFLINE_ICON_AFTER_MILLIS,
        syncedAtLabel = label,
    )
}

/** `HH:mm` локального времени [zone]. 24 часа, без локали — детерминированные тесты. */
public fun formatSyncTime(updatedAtMillis: Long, zone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(updatedAtMillis).toLocalDateTime(zone)
    val hour = time.hour.toString().padStart(2, '0')
    val minute = time.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}
