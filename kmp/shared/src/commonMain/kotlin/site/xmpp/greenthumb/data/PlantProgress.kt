package site.xmpp.greenthumb.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlin.time.Instant
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.ProgressDto
import site.xmpp.greenthumb.core.network.SessionSuperseded
import site.xmpp.greenthumb.core.storage.GreenThumbDb
import site.xmpp.greenthumb.core.storage.ProgressCacheEntity

/** The nine fixed achievements, in display order (server `ACHIEVEMENT_IDS`). */
public enum class AchievementId(public val wire: String) {
    FirstPlant("first_plant"),
    Plants5("plants_5"),
    FirstWater("first_water"),
    Waterings25("waterings_25"),
    Waterings100("waterings_100"),
    Streak7("streak_7"),
    Streak30("streak_30"),
    FirstNote("first_note"),
    FirstPhoto("first_photo"),
}

public data class AchievementCell(
    val id: AchievementId,
    val unlocked: Boolean,
    val unlockedAt: String?,
)

/** Streaks plus exactly one cell per [AchievementId]. */
public data class ProgressView(
    val current: Int,
    val best: Int,
    val cells: List<AchievementCell>,
)

public data class CachedProgress(
    val view: ProgressView,
    val updatedAtMillis: Long,
)

/**
 * Unknown ids are dropped, an id the server omits is locked, and a repeated
 * id counts as unlocked if any of its entries is.
 */
public fun ProgressDto.toView(): ProgressView {
    val byWire = achievements.groupBy { it.id }
    val cells = AchievementId.entries.map { id ->
        val entries = byWire[id.wire].orEmpty()
        val unlocked = entries.firstOrNull { it.unlocked }
        AchievementCell(id = id, unlocked = unlocked != null, unlockedAt = unlocked?.unlockedAt)
    }
    return ProgressView(
        current = streak.current.coerceAtLeast(0),
        best = streak.best.coerceAtLeast(0),
        cells = cells,
    )
}

/** `YYYY-MM-DD HH:mm` local time of a cached load: it may be days old, so the date is shown too. */
public fun formatProgressTime(updatedAtMillis: Long, zone: TimeZone): String {
    val date = Instant.fromEpochMilliseconds(updatedAtMillis).toLocalDateTime(zone).date
    return "$date ${formatSyncTime(updatedAtMillis, zone)}"
}

public fun parseProgress(body: String): ProgressView =
    ApiClient.json.decodeFromString(ProgressDto.serializer(), body).toView()

/**
 * Progress of one account (architecture §5.8). The last server answer lives in
 * the per-user database, so it stays readable offline and leaves with the
 * account's database on sign-out. A failed [refresh] keeps the cache.
 */
public class PlantProgress internal constructor(
    private val db: GreenThumbDb,
    private val api: GreenThumbApi,
    private val session: AccountSession,
    private val nowMillis: () -> Long,
    private val isClosed: () -> Boolean,
) {
    private val lock = Mutex()

    public fun observe(): Flow<CachedProgress?> =
        flow { emitAll(db.progressCache().observe(ProgressCacheEntity.PROGRESS_KEY)) }
            .map { row -> row?.toCached() }
            .catch { if (!isClosed()) throw it }

    public suspend fun refresh(): ProgressView = lock.withLock {
        val stamp = session.current()
        val dto = api.getProgress()
        if (!session.isCurrent(stamp)) throw SessionSuperseded()
        db.progressCache().upsert(
            ProgressCacheEntity(
                key = ProgressCacheEntity.PROGRESS_KEY,
                payload = ApiClient.json.encodeToString(dto),
                updatedAtMillis = nowMillis(),
            ),
        )
        dto.toView()
    }

    private fun ProgressCacheEntity.toCached(): CachedProgress? =
        runCatching { parseProgress(payload) }.getOrNull()?.let { CachedProgress(it, updatedAtMillis) }
}
