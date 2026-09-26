package site.xmpp.greenthumb.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionSuperseded
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
 * подтверждён. Отмена корутины тоже оставляет журнал (процесс «умер» между
 * записью и ответом).
 *
 * Досылка — [replayPending]: строки по `created_at`, затем [refresh].
 * Её зовут на старте и когда сеть вернулась. Пока журнал открыт, [refresh]
 * не перезаписывает эти строки и не воскрешает оптимистично удалённые.
 * Новая мутация сначала досылает более старые строки: порядок не перескакивает.
 * Подтверждённый HTTP-отказ одной строки откатывает только её и не стопорит
 * очередь. Сеть/таймаут стопорят очередь, остаток ждёт следующей попытки.
 *
 * `waterAll` меняет только статус ≠ healthy. `postponeAll` локально ничего не
 * пишет: сначала досылка уже лежащего журнала, затем вызов, затем [refresh].
 * Алерт на ошибку — дело экрана, не репозитория (у RN postponeAll алерта нет).
 *
 * Мутации одного plant_id идут очередью ([PlantMutationQueue]): вторая не
 * начинает сеть, пока первая не записала ответ или не откатилась. Общий
 * [gate] сериализует журнал и refresh, чтобы откат не пересёкся с заменой
 * таблицы и полив B не обогнал неподтверждённый полив A.
 *
 * Ответ применяется, только если [AccountSession] не сменилась с момента
 * запроса. Иначе [SessionSuperseded]: в базу не пишем, журнал не снимаем.
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
    private val session: AccountSession = AccountSession(),
) {
    private val gate = Mutex()
    private val plantQueues = PlantMutationQueue()
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
        observeWhileOpen { db.plants().observeAll().map { rows -> rows.map { it.toDto() } } }

    public fun observePending(): Flow<List<PendingMutationEntity>> =
        observeWhileOpen { db.pendingMutations().observeAll() }

    /**
     * Id растений с открытым журналом. Экраны рисуют [UnsavedMutation.LABEL]
     * на этих карточках и не выдумывают свою строку.
     */
    public fun observeUnsavedPlantIds(): Flow<Set<String>> =
        observePending().map { unsavedPlantIds(it) }

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
     * Наблюдение с учётом жизненного цикла владельца (фикс M6 FATAL
     * «Database is closed», прод-трасса user-testing раунда 1): обращение к
     * Room — при КОЛЛЕКЦИИ, а не при создании Flow. Room проверяет закрытие
     * жадно (`InvalidationTracker.createFlow` → `throwIfClosed`), поэтому
     * прежнее создание наблюдения в теле комозабла падало при поздней
     * рекомпозиции по уже закрытому репозиторию; `catch` на Flow от ошибки
     * создания не спасает.
     *
     * Ошибка при закрытом репозитории ([closed] — владелец закрыл его при
     * выходе/смене аккаунта) тихо завершает Flow: это не сбой данных, а конец
     * наблюдения. Ошибка живой базы доходит до коллекционера — пустым
     * списком не маскируется (architecture §7).
     */
    private fun <T> observeWhileOpen(create: () -> Flow<T>): Flow<T> =
        flow { emitAll(create()) }.catch { if (!closed) throw it }

    /**
     * GET → транзакция → sync_meta. Бросает [ApiError] (и транспорт), не
     * очищая таблицу. Пустой 200 — успешная очистка, это не ошибка.
     *
     * Строки с открытым журналом не перезаписываются и не удаляются:
     * сервер ещё не подтвердил локальную правку (VAL-DATA-006).
     */
    public suspend fun refresh() {
        gate.withLock { refreshUnlocked() }
    }

    /**
     * Досылка журнала по `created_at`, затем [refresh], если очередь дошла
     * до конца. Пустой журнал — [ReplayResult.Idle], без сети.
     * Сеть/таймаут — [ReplayResult.Held], refresh не зовётся.
     * HTTP-отказ строки откатывает её снимок и очередь идёт дальше.
     */
    public suspend fun replayPending(): ReplayResult = gate.withLock {
        if (db.pendingMutations().listOldestFirst().isEmpty()) {
            return@withLock ReplayResult.Idle
        }
        when (val drain = drainAll()) {
            is DrainStep.Held -> ReplayResult.Held(drain.cause)
            else -> {
                refreshUnlocked()
                ReplayResult.Converged
            }
        }
    }

    public suspend fun water(plantId: String) {
        plantQueues.withPlant(plantId) {
            waterQueued(plantId)
        }
    }

    private suspend fun waterQueued(plantId: String) {
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
                when (val older = drainAll()) {
                    is DrainStep.Held -> throw older.cause
                    else -> api.waterAll()
                }
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
            when (val older = drainBefore(entry.id)) {
                is DrainStep.Held -> throw older.cause
                else -> {}
            }
            when (val step = sendOne(entry)) {
                is DrainStep.Held -> throw step.cause
                is DrainStep.Rejected -> throw step.cause
                is DrainStep.Done -> {}
            }
        }
    }

    /**
     * Без оптимистичной записи. Сначала досылка уже лежащего журнала
     * (первый успешный ответ не должен обогнать очередь), затем вызов,
     * затем [refresh]. Ошибка вызова refresh не зовёт. Сеть на досылке
     * не доходит до postpone.
     */
    public suspend fun postponeAll() {
        val stamp = session.current()
        gate.withLock {
            when (val older = drainAll()) {
                is DrainStep.Held -> throw older.cause
                else -> {
                    api.postponeAll()
                    ensureCurrent(stamp)
                    refreshUnlocked()
                }
            }
        }
    }

    public suspend fun add(plant: InsertPlantDto): PlantDto {
        val localId = newId()
        return plantQueues.withPlant(localId) {
            addQueued(plant, localId)
        }
    }

    private suspend fun addQueued(plant: InsertPlantDto, localId: String): PlantDto {
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
        return plantQueues.withPlant(plantId) {
            updateQueued(plantId, patch)
        }
    }

    private suspend fun updateQueued(plantId: String, patch: PatchPlantDto): PlantDto {
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
        plantQueues.withPlant(plantId) {
            deleteQueued(plantId)
        }
    }

    private suspend fun deleteQueued(plantId: String) {
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

    /** Соединение уже закрыто (повторный [close] ничего не делает). */
    public fun isClosed(): Boolean = closed

    public fun close() {
        if (closed) return
        closed = true
        runCatching { db.close() }
    }

    private suspend fun refreshUnlocked() {
        val stamp = session.current()
        val remote = api.getPlants()
        ensureCurrent(stamp)
        val protectedIds = unsavedPlantIds(db.pendingMutations().listOldestFirst())
        val incoming = remote.map { it.toEntity() }.filter { it.id !in protectedIds }
        val remoteIds = remote.map { it.id }.toSet()
        val removeIds = db.plants().listAll().map { it.id }.filter { id ->
            id !in remoteIds && id !in protectedIds
        }
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
        when (val older = drainBefore(entry.id)) {
            is DrainStep.Held -> throw older.cause
            else -> {}
        }
        val stamp = session.current()
        val result = try {
            request()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            fail(entry, error)
        }
        ensureCurrent(stamp)
        onSuccess(entry, result)
        result
    }

    /** Ответ после смены сессии в базу не пишем. Журнал остаётся. */
    private suspend fun ensureCurrent(stamp: Long) {
        if (!session.isCurrent(stamp)) throw SessionSuperseded()
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

    private suspend fun pending(
        type: String,
        plantId: String?,
        payload: String,
        snapshotJson: String?,
    ): PendingMutationEntity {
        val now = clocks.nowMillis()
        val latest = db.pendingMutations().listOldestFirst().lastOrNull()?.createdAt
        val createdAt = if (latest == null || now > latest) now else latest + 1
        return PendingMutationEntity(
            id = newId(),
            type = type,
            plantId = plantId,
            payload = payload,
            snapshotJson = snapshotJson,
            createdAt = createdAt,
        )
    }

    /**
     * Досылает весь журнал. HTTP-отказ строки откатывает её и идёт дальше.
     * Сеть/таймаут останавливают очередь, строка остаётся.
     */
    private suspend fun drainAll(): DrainStep {
        while (true) {
            val next = db.pendingMutations().listOldestFirst().firstOrNull() ?: return DrainStep.Done
            when (val step = sendOne(next)) {
                is DrainStep.Held -> return step
                else -> {}
            }
        }
    }

    /** Досылает строки старше [entryId]. Саму строку не трогает — её шлёт вызывающий. */
    private suspend fun drainBefore(entryId: String): DrainStep {
        while (true) {
            val next = db.pendingMutations().listOldestFirst().firstOrNull() ?: return DrainStep.Done
            if (next.id == entryId) return DrainStep.Done
            when (val step = sendOne(next)) {
                is DrainStep.Held -> return step
                else -> {}
            }
        }
    }

    /**
     * Одна строка журнала. Успех применяет ответ и снимает строку.
     * Сеть/таймаут строку не снимает. Подтверждённый отказ откатывает снимок.
     * Отмена корутины строку не снимает.
     *
     * Дата `water` берётся из payload, не из часов на момент досылки.
     * `water_all` — только POST, без повторного локального проставления даты.
     */
    private suspend fun sendOne(entry: PendingMutationEntity): DrainStep {
        val stamp = session.current()
        try {
            when (entry.type) {
                MutationType.WATER -> {
                    val payload = json.decodeFromString<WaterPayload>(entry.payload)
                    val plantId = requireNotNull(entry.plantId)
                    val server = api.updatePlant(
                        plantId,
                        PatchPlantDto(lastWateredDate = Patch.Value(payload.lastWateredDate)),
                    )
                    ensureCurrent(stamp)
                    db.store().replacePlantDropPending(plantId, server.toEntity(), entry.id)
                }
                MutationType.WATER_ALL -> {
                    api.waterAll()
                    ensureCurrent(stamp)
                    db.store().dropPending(entry.id)
                }
                MutationType.ADD -> {
                    val body = json.decodeFromString<InsertPlantDto>(entry.payload)
                    val localId = requireNotNull(entry.plantId)
                    val server = api.addPlant(body)
                    ensureCurrent(stamp)
                    db.store().replacePlantDropPending(localId, server.toEntity(), entry.id)
                }
                MutationType.UPDATE -> {
                    val patch = json.decodeFromString<PatchPlantDto>(entry.payload)
                    val plantId = requireNotNull(entry.plantId)
                    val server = api.updatePlant(plantId, patch)
                    ensureCurrent(stamp)
                    db.store().replacePlantDropPending(plantId, server.toEntity(), entry.id)
                }
                MutationType.DELETE -> {
                    val plantId = requireNotNull(entry.plantId)
                    api.deletePlant(plantId)
                    ensureCurrent(stamp)
                    db.store().dropPending(entry.id)
                }
                else -> rollback(entry)
            }
            return DrainStep.Done
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (keepForReplay(error)) return DrainStep.Held(error)
            return try {
                rollback(entry)
                DrainStep.Rejected(error)
            } catch (rollbackError: Throwable) {
                if (rollbackError is CancellationException) throw rollbackError
                error.addSuppressed(rollbackError)
                DrainStep.Held(rollbackError)
            }
        }
    }

    /** Сеть/таймаут — ответ не подтверждён. HTTP-отказ — подтверждённый провал. */
    private fun keepForReplay(error: Throwable): Boolean =
        error is ApiError.Network || error is ApiError.Timeout
}

/** Шаг разбора одной журнальной строки. Снаружи репозитория не виден. */
private sealed class DrainStep {
    data object Done : DrainStep()

    data class Held(val cause: Throwable) : DrainStep()

    data class Rejected(val cause: Throwable) : DrainStep()
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

public fun PlantDatabases.openerFor(
    api: GreenThumbApi,
    session: AccountSession,
): PlantRepositoryOpener =
    PlantRepositoryOpener { userId ->
        PlantRepository(
            userId = userId,
            db = open(userId),
            api = api,
            deleteFiles = { id -> delete(id) },
            session = session,
        )
    }
