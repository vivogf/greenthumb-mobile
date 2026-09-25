package site.xmpp.greenthumb.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Порог свежести данных: [refetchOnWindowFocus] RN работал со `staleTime` 60 с (`lib/queryClient.ts:28`). */
public const val STALE_AFTER_MILLIS: Long = 60_000L

/**
 * Источник времени последней успешной синхронизации. В M4 это строка
 * `sync_meta("plants").updated_at_millis` из Room (architecture.md §7);
 * координатор знает только про «когда последний раз сошлось с сервером».
 */
public fun interface SyncMetaSource {
    /** Время последнего успешного обновления в эпоха-миллисекундах, либо null, если синхронизации ещё не было. */
    public suspend fun lastSyncedAtMillis(): Long?
}

/** Что координатор сделал с триггером. */
public sealed class RefreshOutcome {
    /** Обновление выполнено успешно. */
    public data object Refreshed : RefreshOutcome()

    /** Данные моложе порога — сети не касались. */
    public data object SkippedFresh : RefreshOutcome()

    /** Предыдущее обновление ещё в полёте — второй запрос не заводим. */
    public data object SkippedInFlight : RefreshOutcome()

    /** Повторный «первый показ» на том же инстансе координатора. */
    public data object SkippedAlreadyShown : RefreshOutcome()

    /** Обновление сходило на сервер и не удалось; данные не тронуты. */
    public data class Failed(public val cause: Throwable) : RefreshOutcome()
}

/**
 * Координатор обновления данных (Stage 4 п.8; architecture.md §7,
 * VAL-OFF-004).
 *
 * Слушает три источника — первый показ экрана, `ON_RESUME` и восстановление
 * сети — дедуплицирует их и вызывает [refresh], только если последняя
 * синхронизация старше [staleAfterMillis]. Это порт связки RN
 * `refetchOnWindowFocus` + `refetchOnReconnect` + `staleTime: 60_000`
 * (`lib/queryClient.ts:28-31`), у которой на мобильном была дыра: `ON_RESUME`
 * не срабатывает, когда сеть вернулась, а приложение всё это время оставалось
 * на экране, поэтому третий триггер идёт от
 * [site.xmpp.greenthumb.core.platform.Connectivity].
 *
 * Дедуп двухуровневый:
 * - по свежести: триггер в пределах окна [staleAfterMillis] после успешной
 *   синхронизации не делает запрос (три триггера подряд → один запрос);
 * - по полёту: пока запрос не вернулся, следующий триггер получает
 *   [RefreshOutcome.SkippedInFlight] и параллельный запрос не заводится.
 *
 * Провал обновления окно свежести не занимает: `sync_meta` его не отмечает,
 * поэтому следующий триггер повторит попытку.
 *
 * Часы инжектируются ([nowMillis]) — тесты детерминированы без ожидания.
 *
 * Сам жизненный цикл не слушает. Скелет вызывает [onFirstShow] при входе
 * в онлайн-сессию и передаёт [PlantRepository.refresh]: успех пишет
 * `sync_meta`, ошибка таблицу не трогает. [onResume] и привязка к
 * `ON_RESUME` остаются оболочке Stage 6. [attachConnectivity] — на поток
 * [site.xmpp.greenthumb.core.platform.Connectivity.isOnline]. [refresh] не
 * должен звать координатор повторно — мьютекс не реентерабелен.
 *
 * @param syncMeta источник времени последней успешной синхронизации (M4: Room).
 * @param nowMillis часы (эпоха-миллисекунды); прод передаёт системные.
 * @param staleAfterMillis порог свежести; по умолчанию [STALE_AFTER_MILLIS].
 * @param refresh обновление данных; бросает при неуспехе (в M4 —
 *   `PlantRepository.refresh()`, которая при ошибке не трогает таблицу).
 */
public class RefreshCoordinator(
    private val syncMeta: SyncMetaSource,
    private val nowMillis: () -> Long,
    private val staleAfterMillis: Long = STALE_AFTER_MILLIS,
    private val refresh: suspend () -> Unit,
) {

    private val inFlight = Mutex()
    private var firstShowHandled = false

    /** Триггер «первый показ экрана»; на одном инстансе срабатывает один раз. */
    public suspend fun onFirstShow(): RefreshOutcome {
        if (firstShowHandled) return RefreshOutcome.SkippedAlreadyShown
        firstShowHandled = true
        return refreshIfStale()
    }

    /** Триггер «приложение вернулось на передний план». */
    public suspend fun onResume(): RefreshOutcome = refreshIfStale()

    /** Триггер «сеть восстановилась» (VAL-OFF-004). */
    public suspend fun onNetworkRestored(): RefreshOutcome = refreshIfStale()

    /**
     * Подписка на поток состояния сети ([site.xmpp.greenthumb.core.platform.Connectivity.isOnline]):
     * каждый переход «нет сети → есть сеть» вызывает [onNetworkRestored].
     *
     * Текущее значение потока переходом не считается (`drop(1)`): подписка на
     * «онлайн» при старте — не восстановление сети, за первый показ отвечает
     * [onFirstShow].
     *
     * @return задание подписки; отменяется вместе с переданным scope либо вручную.
     */
    public fun attachConnectivity(scope: CoroutineScope, isOnline: Flow<Boolean>): Job =
        scope.launch {
            isOnline
                .distinctUntilChanged()
                .drop(1)
                .filter { it }
                .collect { onNetworkRestored() }
        }

    private suspend fun refreshIfStale(): RefreshOutcome {
        if (!inFlight.tryLock()) return RefreshOutcome.SkippedInFlight
        try {
            val lastSynced = syncMeta.lastSyncedAtMillis()
            // Свежие, пока возраст ≤ порога. React Query (`staleTime: 60_000`,
            // lib/queryClient.ts:28) считает данные протухшими только когда
            // `dataUpdatedAt + staleTime < now` — строго старше 60 с
            // («stale > 60 с», architecture.md §7). Ровно на пороге запроса нет.
            // Время из будущего (расхождение часов, отрицательный возраст)
            // тоже свежее: иначе каждый триггер уходил бы в сеть, пока часы
            // не сойдутся.
            if (lastSynced != null && nowMillis() - lastSynced <= staleAfterMillis) {
                return RefreshOutcome.SkippedFresh
            }
            return try {
                refresh()
                RefreshOutcome.Refreshed
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (cause: Throwable) {
                // Провал не отмечает sync_meta — окно свежести не занято,
                // следующий триггер повторит попытку.
                RefreshOutcome.Failed(cause)
            }
        } finally {
            inFlight.unlock()
        }
    }
}
