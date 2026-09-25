package site.xmpp.greenthumb.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.storage.GreenThumbDb
import site.xmpp.greenthumb.core.storage.PendingMutationEntity
import site.xmpp.greenthumb.core.storage.PlantDatabases
import site.xmpp.greenthumb.core.storage.SyncMetaEntity
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Источник истины списка растений — Room (architecture.md §7, Stage 4 п.3 и п.7).
 *
 * [observePlants] читает таблицу. [refresh] заменяет её ответом `GET /api/plants`
 * и пишет `sync_meta`; ошибка запроса возвращается вызывающему и таблицу не
 * трогает (не полноэкранная ошибка поверх данных).
 *
 * `water` / `waterAll` / `add` / `update` / `delete` пишут оптимистично в одной
 * транзакции с журнальной строкой и шлют [effects] сразу, не дожидаясь сети и
 * не дожидаясь анимации. HTTP-отказ (4xx/5xx) откатывает снимок и снимает
 * журнал. [ApiError.Network] и [ApiError.Timeout] журнал не снимают: ответ не
 * подтверждён, досылка — следующая фича. Отмена корутины тоже оставляет
 * журнал (процесс «умер» между записью и ответом).
 *
 * `waterAll` меняет только статус ≠ healthy. `postponeAll` локально ничего не
 * пишет: вызов, затем [refresh]. Алерт на ошибку — дело экрана, не репозитория
 * (у RN postponeAll алерта нет).
 *
 * Гонки по plant_id, игнор ответа после смены сессии и «refresh не затирает
 * открытую мутацию» — следующие фичи. Здесь мутации и refresh сериализованы
 * одним мьютексом, чтобы откат не пересёкся с заменой таблицы.
 *
 * @param deleteFiles стирает `plants_${userId}.db` вместе с wal/shm/journal.
 *   Вызывающий отменяет свою работу до [deleteDatabaseForUser].
 */
public class PlantRepository(
    public val userId: String,
    private val db: GreenThumbDb,
    private val api: GreenThumbApi,
    private val deleteFiles: (String) -> Unit,
    private val clocks: PlantClocks = PlantClocks(),
    private val newId: () -> String = { newMutationId() },
) {
    private val gate = Mutex()
    private val json = ApiClient.json

    private val effectSink = MutableSharedFlow<PlantEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Одноразовые эффекты. replay нет. */
    public val effects: SharedFlow<PlantEffect> = effectSink.asSharedFlow()

    /** Для теста: подписчик успел встать до emit. */
    internal val effectSubscriptions: kotlinx.coroutines.flow.StateFlow<Int> =
        effectSink.subscriptionCount

    @Volatile
    private var closed = false

    public fun observePlants(): Flow<List<PlantDto>> =
        db.plants().observeAll().map { rows -> rows.map { it.toDto() } }

    public fun observePending(): Flow<List<PendingMutationEntity>> =
        db.pendingMutations().observeAll()

    public suspend fun currentPlants(): List<PlantDto> =
        db.plants().listAll().map { it.toDto() }

    public suspend fun pendingJournal(): List<PendingMutationEntity> =
        db.pendingMutations().listOldestFirst()

    public suspend fun lastSyncedAtMillis(): Long? =
        db.syncMeta().get(SyncMetaEntity.PLANTS_KEY)?.updatedAtMillis

    /** Баннер от строки `sync_meta`. [nowMillis] — инжектированные часы. */
    public suspend fun syncBanner(nowMillis: Long = clocks.nowMillis()): SyncBanner =
        syncBanner(lastSyncedAtMillis(), nowMillis, clocks.zone)

    /**
     * GET → транзакция → sync_meta. Бросает [ApiError] (и транспорт), не
     * очищая таблицу. Пустой 200 — успешная очистка, это не ошибка.
     */
    public suspend fun refresh() {
        gate.withLock { refreshUnlocked() }
    }

    public suspend fun water(plantId: String) {
        val today = clocks.todayString()
        runOptimistic(
            write = {
                val current = db.plants().getById(plantId)
                    ?: error("plant $plantId is not in the local cache")
                val entry = pending(
                    type = MutationType.WATER,
                    plantId = plantId,
                    payload = json.encodeToString(WaterPayload(today)),
                    snapshotJson = json.encodeToString(current.toDto()),
                )
                db.store().savePlantWithPending(
                    current.copy(lastWateredDate = today),
                    entry,
                )
                entry
            },
            effect = PlantEffect.Water(plantId),
            request = {
                api.updatePlant(
                    plantId,
                    PatchPlantDto(lastWateredDate = Patch.Value(today)),
                )
            },
            onSuccess = { entry, server ->
                db.store().replacePlantDropPending(plantId, server.toEntity(), entry.id)
            },
        )
    }

    /**
     * Оптимистично только не-healthy. Сервер всё равно вызывается: его набор
     * может разойтись с локальным фильтром, сверка — следующим refresh.
     */
    public suspend fun waterAll() {
        val today = clocks.todayString()
        val todayDate = clocks.today()
        gate.withLock {
            val due = db.plants().listAll().filter { plant ->
                wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, todayDate) !=
                    WateringStatus.Healthy
            }
            if (due.isEmpty()) {
                api.waterAll()
                return@withLock
            }
            val ids = due.map { it.id }
            val entry = pending(
                type = MutationType.WATER_ALL,
                plantId = null,
                payload = json.encodeToString(WaterAllPayload(ids, today)),
                snapshotJson = json.encodeToString(due.map { it.toDto() }),
            )
            db.store().savePlantsWithPending(
                due.map { it.copy(lastWateredDate = today) },
                entry,
            )
            effectSink.tryEmit(PlantEffect.WaterAll(ids))
            try {
                api.waterAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                fail(entry, error)
            }
            db.store().dropPending(entry.id)
        }
    }

    /** Без оптимистичной записи: вызов, затем [refresh]. Ошибка вызова refresh не зовёт. */
    public suspend fun postponeAll() {
        gate.withLock {
            api.postponeAll()
            refreshUnlocked()
        }
    }

    public suspend fun add(plant: InsertPlantDto): PlantDto {
        val localId = newId()
        return runOptimistic(
            write = {
                val entity = plant.toOptimisticEntity(
                    id = localId,
                    userId = userId,
                    createdAt = Instant.fromEpochMilliseconds(clocks.nowMillis()).toString(),
                )
                val entry = pending(
                    type = MutationType.ADD,
                    plantId = localId,
                    payload = json.encodeToString(plant),
                    snapshotJson = null,
                )
                db.store().savePlantWithPending(entity, entry)
                entry
            },
            effect = PlantEffect.Added(localId),
            request = { api.addPlant(plant) },
            onSuccess = { entry, server ->
                db.store().replacePlantDropPending(localId, server.toEntity(), entry.id)
            },
        )
    }

    public suspend fun update(plantId: String, patch: PatchPlantDto): PlantDto {
        return runOptimistic(
            write = {
                val current = db.plants().getById(plantId)
                    ?: error("plant $plantId is not in the local cache")
                val entry = pending(
                    type = MutationType.UPDATE,
                    plantId = plantId,
                    payload = json.encodeToString(patch),
                    snapshotJson = json.encodeToString(current.toDto()),
                )
                db.store().savePlantWithPending(current.applying(patch), entry)
                entry
            },
            effect = PlantEffect.Updated(plantId),
            request = { api.updatePlant(plantId, patch) },
            onSuccess = { entry, server ->
                db.store().replacePlantDropPending(plantId, server.toEntity(), entry.id)
            },
        )
    }

    public suspend fun delete(plantId: String) {
        runOptimistic(
            write = {
                val current = db.plants().getById(plantId)
                    ?: error("plant $plantId is not in the local cache")
                val entry = pending(
                    type = MutationType.DELETE,
                    plantId = plantId,
                    payload = "{}",
                    snapshotJson = json.encodeToString(current.toDto()),
                )
                db.store().deletePlantWithPending(plantId, entry)
                entry
            },
            effect = PlantEffect.Deleted(plantId),
            request = { api.deletePlant(plantId) },
            onSuccess = { entry, _ ->
                db.store().dropPending(entry.id)
            },
        )
    }

    /**
     * Стирает файл базы пользователя. Для своего [userId] сначала дожидается
     * текущей мутации и закрывает инстанс. Чужой id только удаляет файлы.
     */
    public suspend fun deleteDatabaseForUser(targetUserId: String) {
        if (targetUserId == userId) {
            gate.withLock { close() }
        }
        deleteFiles(targetUserId)
    }

    public fun close() {
        if (closed) return
        closed = true
        runCatching { db.close() }
    }

    private suspend fun refreshUnlocked() {
        val remote = api.getPlants()
        val incoming = remote.map { it.toEntity() }
        val incomingIds = incoming.map { it.id }.toSet()
        val removeIds = db.plants().listAll().map { it.id }.filter { it !in incomingIds }
        db.store().replaceAll(incoming, removeIds, clocks.nowMillis())
    }

    private suspend fun <T> runOptimistic(
        write: suspend () -> PendingMutationEntity,
        effect: PlantEffect,
        request: suspend () -> T,
        onSuccess: suspend (PendingMutationEntity, T) -> Unit,
    ): T = gate.withLock {
        val entry = write()
        effectSink.tryEmit(effect)
        val result = try {
            request()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            fail(entry, error)
        }
        onSuccess(entry, result)
        result
    }

    private suspend fun fail(entry: PendingMutationEntity, error: Throwable): Nothing {
        if (!keepForReplay(error)) {
            try {
                rollback(entry)
            } catch (rollbackError: Throwable) {
                if (rollbackError is CancellationException) throw rollbackError
                error.addSuppressed(rollbackError)
            }
        }
        throw error
    }

    private suspend fun rollback(entry: PendingMutationEntity) {
        when (entry.type) {
            MutationType.ADD ->
                db.store().deletePlantDropPending(requireNotNull(entry.plantId), entry.id)
            MutationType.WATER_ALL ->
                db.store().restorePlantsDropPending(
                    decodePlants(requireNotNull(entry.snapshotJson)).map { it.toEntity() },
                    entry.id,
                )
            MutationType.WATER, MutationType.UPDATE, MutationType.DELETE ->
                db.store().restorePlantDropPending(
                    decodePlant(requireNotNull(entry.snapshotJson)).toEntity(),
                    entry.id,
                )
            else -> db.store().dropPending(entry.id)
        }
    }

    private fun decodePlant(text: String): PlantDto = json.decodeFromString(text)

    private fun decodePlants(text: String): List<PlantDto> = json.decodeFromString(text)

    private fun pending(
        type: String,
        plantId: String?,
        payload: String,
        snapshotJson: String?,
    ): PendingMutationEntity = PendingMutationEntity(
        id = newId(),
        type = type,
        plantId = plantId,
        payload = payload,
        snapshotJson = snapshotJson,
        createdAt = clocks.nowMillis(),
    )

    /** Сеть/таймаут — ответ не подтверждён. HTTP-отказ — подтверждённый провал. */
    private fun keepForReplay(error: Throwable): Boolean =
        error is ApiError.Network || error is ApiError.Timeout
}

/** Часы репозитория. Прод — системные; тесты подставляют свои. */
public class PlantClocks(
    public val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    public val zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault(),
) {
    public fun today(): kotlinx.datetime.LocalDate =
        Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(zone).date

    public fun todayString(): String = today().toString()
}

@OptIn(ExperimentalUuidApi::class)
public fun newMutationId(): String = Uuid.random().toString()

/** Открыть репозиторий на per-user файле [PlantDatabases]. */
public fun interface PlantRepositoryOpener {
    public fun open(userId: String): PlantRepository
}

public fun PlantDatabases.openerFor(api: GreenThumbApi): PlantRepositoryOpener =
    PlantRepositoryOpener { userId ->
        PlantRepository(
            userId = userId,
            db = open(userId),
            api = api,
            deleteFiles = { id -> delete(id) },
        )
    }
