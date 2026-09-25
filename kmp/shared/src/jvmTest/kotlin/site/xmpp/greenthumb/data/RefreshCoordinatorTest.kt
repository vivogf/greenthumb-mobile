package site.xmpp.greenthumb.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage 4 п.8 (координатор обновления), VAL-OFF-004: «Сеть вернулась, пока
 * приложение открыто → срабатывает refresh (если данные старше 60 с), дедуп
 * с ON_RESUME/первым показом».
 *
 * Проверяются три триггера ([RefreshTrigger]), дедуп между ними и порог
 * свежести 60 с. Часы инжектируются ([RefreshCoordinator] принимает
 * `nowMillis`) — тесты детерминированы, реального ожидания нет.
 *
 * Источник свежести — [SyncMetaSource] (в M4 это `sync_meta.updated_at_millis`
 * из Room); двойник ниже ведёт себя как репозиторий: успешный refresh
 * проставляет время синхронизации, провал — не трогает.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent: прогон уже запланированных корутин без продвижения времени
class RefreshCoordinatorTest {

    // ------------------------------------------------------------------
    // Двойники
    // ------------------------------------------------------------------

    private class FakeSyncMeta(var lastSynced: Long? = null) : SyncMetaSource {
        override suspend fun lastSyncedAtMillis(): Long? = lastSynced
    }

    /** Часы под ручным управлением: тест двигает `now` сам. */
    private class TestClock(var now: Long = START_MILLIS) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private val clock = TestClock()
    private val syncMeta = FakeSyncMeta()

    /** Счётчик вызовов refresh — «сколько раз реально сходили на сервер». */
    private var refreshCalls = 0

    /**
     * Координатор с refresh-действием репозитория: успех проставляет
     * `sync_meta` = сейчас (ровно то, что делает `PlantRepository.refresh()`
     * в M4), провал оставляет прежнее значение.
     */
    private fun coordinator(
        failWith: Throwable? = null,
        beforeReturn: suspend () -> Unit = {},
    ): RefreshCoordinator = RefreshCoordinator(
        syncMeta = syncMeta,
        nowMillis = { clock.now },
    ) {
        refreshCalls++
        beforeReturn()
        if (failWith != null) throw failWith
        syncMeta.lastSynced = clock.now
    }

    // ------------------------------------------------------------------
    // Триггер 1: первый показ экрана
    // ------------------------------------------------------------------

    @Test
    fun `first show refreshes when never synced`() = runTest {
        val outcome = coordinator().onFirstShow()

        assertEquals(RefreshOutcome.Refreshed, outcome, "данных ещё нет — обновляемся")
        assertEquals(1, refreshCalls)
        assertEquals(clock.now, syncMeta.lastSynced, "успешный refresh проставил sync_meta")
    }

    @Test
    fun `first show skips when data is fresh`() = runTest {
        syncMeta.lastSynced = clock.now - 59_999L

        val outcome = coordinator().onFirstShow()

        assertEquals(RefreshOutcome.SkippedFresh, outcome, "данные моложе 60 с — сети не трогаем")
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `repeated first show is deduplicated even when data went stale`() = runTest {
        val subject = coordinator()

        assertEquals(RefreshOutcome.Refreshed, subject.onFirstShow())
        clock.advance(120_000L)
        val repeated = subject.onFirstShow()

        assertEquals(
            RefreshOutcome.SkippedAlreadyShown,
            repeated,
            "«первый показ» бывает один раз на инстанс — повтор не обновляет даже по протухшим данным",
        )
        assertEquals(1, refreshCalls, "второй вызов первого показа сети не касается")
    }

    // ------------------------------------------------------------------
    // Триггер 2: ON_RESUME
    // ------------------------------------------------------------------

    @Test
    fun `resume refreshes when data is stale`() = runTest {
        syncMeta.lastSynced = clock.now - 61_000L

        val outcome = coordinator().onResume()

        assertEquals(RefreshOutcome.Refreshed, outcome)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `resume skips when data is fresh`() = runTest {
        syncMeta.lastSynced = clock.now - 10_000L

        val outcome = coordinator().onResume()

        assertEquals(RefreshOutcome.SkippedFresh, outcome)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `resume refreshes repeatedly once each window expires`() = runTest {
        val subject = coordinator()

        assertEquals(RefreshOutcome.Refreshed, subject.onResume(), "sync_meta пуст — первый заход обновляет")
        assertEquals(RefreshOutcome.SkippedFresh, subject.onResume(), "сразу следом данные свежие")
        clock.advance(60_000L)
        assertEquals(RefreshOutcome.SkippedFresh, subject.onResume(), "ровно 60 с — ещё не старше порога")
        clock.advance(1L)
        assertEquals(RefreshOutcome.Refreshed, subject.onResume(), "61-я миллисекунда — окно истекло")
        assertEquals(2, refreshCalls)
    }

    // ------------------------------------------------------------------
    // Триггер 3: восстановление сети (VAL-OFF-004)
    // ------------------------------------------------------------------

    @Test
    fun `network restored refreshes when data is stale`() = runTest {
        syncMeta.lastSynced = clock.now - 300_000L

        val outcome = coordinator().onNetworkRestored()

        assertEquals(RefreshOutcome.Refreshed, outcome, "сеть вернулась на переднем плане — обновляемся")
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `network restored skips when data is fresh`() = runTest {
        syncMeta.lastSynced = clock.now - 1_000L

        val outcome = coordinator().onNetworkRestored()

        assertEquals(
            RefreshOutcome.SkippedFresh,
            outcome,
            "моргнувшая сеть при свежих данных лишнего запроса не делает",
        )
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `network flap from connectivity refreshes once per offline to online transition`() = runTest {
        val online = MutableStateFlow(true)
        val subject = coordinator()
        val job = subject.attachConnectivity(this, online)
        runCurrent()

        assertEquals(0, refreshCalls, "стартовое значение потока — не «восстановление сети»")

        online.value = false
        runCurrent()
        assertEquals(0, refreshCalls, "уход в офлайн обновления не запускает")

        online.value = true
        runCurrent()
        assertEquals(1, refreshCalls, "переход офлайн→онлайн обновляет (данных не было)")

        clock.advance(120_000L)
        online.value = false
        // runCurrent между переходами обязателен: StateFlow конфлятит значения,
        // и false→true без прогона коллектора сложился бы в «значение не менялось».
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(2, refreshCalls, "следующее восстановление сети на протухших данных обновляет снова")

        job.cancel()
    }

    @Test
    fun `connectivity attachment ignores initial offline value`() = runTest {
        val online = MutableStateFlow(false)
        val subject = coordinator()
        val job = subject.attachConnectivity(this, online)
        runCurrent()

        assertEquals(0, refreshCalls, "стартовый офлайн — не переход, обновления нет")

        job.cancel()
    }

    // ------------------------------------------------------------------
    // Дедуп трёх триггеров
    // ------------------------------------------------------------------

    @Test
    fun `three triggers in one window produce a single refresh`() = runTest {
        val subject = coordinator()

        val first = subject.onFirstShow()
        val resume = subject.onResume()
        val restored = subject.onNetworkRestored()

        assertEquals(RefreshOutcome.Refreshed, first)
        assertEquals(RefreshOutcome.SkippedFresh, resume, "ON_RESUME дедуплицирован первым показом")
        assertEquals(RefreshOutcome.SkippedFresh, restored, "восстановление сети дедуплицировано")
        assertEquals(1, refreshCalls, "три триггера подряд — один запрос")
    }

    @Test
    fun `trigger during in-flight refresh is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val subject = coordinator(beforeReturn = { gate.await() })

        val inFlight = launch { subject.onResume() }
        runCurrent()
        assertEquals(1, refreshCalls, "первый триггер ушёл в сеть и висит на ответе")

        val second = subject.onNetworkRestored()

        assertEquals(
            RefreshOutcome.SkippedInFlight,
            second,
            "пока запрос в полёте, второй триггер не плодит параллельный запрос",
        )
        assertEquals(1, refreshCalls)

        gate.complete(Unit)
        inFlight.join()
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `trigger after in-flight refresh completes respects freshness`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val subject = coordinator(beforeReturn = { gate.await() })

        val inFlight = launch { subject.onResume() }
        runCurrent()
        gate.complete(Unit)
        inFlight.join()

        assertEquals(
            RefreshOutcome.SkippedFresh,
            subject.onNetworkRestored(),
            "после завершения запроса данные свежие — следующий триггер молчит",
        )
        assertEquals(1, refreshCalls)
    }

    /**
     * VAL-OFF-004 целиком: сеть вернулась, пока приложение на переднем плане
     * (не ON_RESUME — поток [attachConnectivity]), refresh только если данные
     * старше 60 с, и этот refresh дедуплицируется с первым показом и ON_RESUME.
     */
    @Test
    fun `network return in foreground refreshes only when older than 60s and dedups`() = runTest {
        syncMeta.lastSynced = clock.now - 61_000L
        val online = MutableStateFlow(true)
        val subject = coordinator()
        val job = subject.attachConnectivity(this, online)
        runCurrent()

        assertEquals(RefreshOutcome.Refreshed, subject.onFirstShow(), "первый показ на протухших данных обновляет")
        assertEquals(RefreshOutcome.SkippedFresh, subject.onResume(), "ON_RESUME сразу следом дедуплицирован")

        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(1, refreshCalls, "сеть вернулась, но данные свежие — refresh нет")

        clock.advance(STALE_AFTER_MILLIS + 1L)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(2, refreshCalls, "сеть вернулась на переднем плане, данные старше 60 с — refresh")
        assertEquals(RefreshOutcome.SkippedFresh, subject.onResume(), "ON_RESUME дедуплицирован восстановлением сети")
        assertEquals(2, refreshCalls)

        job.cancel()
    }

    // ------------------------------------------------------------------
    // Порог 60 с (инжекция часов)
    // ------------------------------------------------------------------

    @Test
    fun `one millisecond below threshold is fresh`() = runTest {
        syncMeta.lastSynced = clock.now - (STALE_AFTER_MILLIS - 1L)

        assertEquals(RefreshOutcome.SkippedFresh, coordinator().onResume())
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `exactly at threshold is still fresh`() = runTest {
        syncMeta.lastSynced = clock.now - STALE_AFTER_MILLIS

        assertEquals(
            RefreshOutcome.SkippedFresh,
            coordinator().onResume(),
            "возраст ровно 60 с — ещё не «старше 60 с» (React Query: dataUpdatedAt + staleTime < now)",
        )
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `one millisecond past threshold is stale`() = runTest {
        syncMeta.lastSynced = clock.now - (STALE_AFTER_MILLIS + 1L)

        assertEquals(RefreshOutcome.Refreshed, coordinator().onResume(), "возраст 60 с + 1 мс — данные протухли")
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `threshold is sixty seconds`() {
        assertEquals(60_000L, STALE_AFTER_MILLIS, "порог свежести — staleTime 60 с из lib/queryClient.ts")
    }

    @Test
    fun `sync time in the future counts as fresh`() = runTest {
        // Расхождение часов (sync_meta из будущего) не должно превращаться в
        // бесконечный цикл обновлений.
        syncMeta.lastSynced = clock.now + 5_000L

        assertEquals(RefreshOutcome.SkippedFresh, coordinator().onResume())
        assertEquals(0, refreshCalls)
    }

    @Test
    fun `custom threshold is honoured`() = runTest {
        val subject = RefreshCoordinator(
            syncMeta = syncMeta,
            nowMillis = { clock.now },
            staleAfterMillis = 5_000L,
        ) {
            refreshCalls++
            syncMeta.lastSynced = clock.now
        }
        syncMeta.lastSynced = clock.now - 6_000L

        assertEquals(RefreshOutcome.Refreshed, subject.onResume(), "порог инжектируется (M4/тесты)")
        assertEquals(1, refreshCalls)
    }

    // ------------------------------------------------------------------
    // Провал обновления
    // ------------------------------------------------------------------

    @Test
    fun `failed refresh is reported and does not mark data fresh`() = runTest {
        val failure = IllegalStateException("network down")
        val subject = coordinator(failWith = failure)

        val outcome = subject.onResume()

        val failed = assertIs<RefreshOutcome.Failed>(outcome, "ошибка обновления видна вызывающему")
        assertEquals(failure, failed.cause)
        assertEquals(null, syncMeta.lastSynced, "провал sync_meta не трогает")
    }

    @Test
    fun `next trigger retries after a failed refresh`() = runTest {
        var failNext = true
        val subject = RefreshCoordinator(
            syncMeta = syncMeta,
            nowMillis = { clock.now },
        ) {
            refreshCalls++
            if (failNext) {
                failNext = false
                throw IllegalStateException("network down")
            }
            syncMeta.lastSynced = clock.now
        }

        assertIs<RefreshOutcome.Failed>(subject.onResume())
        val retry = subject.onNetworkRestored()

        assertEquals(RefreshOutcome.Refreshed, retry, "провал не занимает окно свежести — следующий триггер повторяет")
        assertEquals(2, refreshCalls)
    }

    @Test
    fun `failed refresh releases the in-flight guard`() = runTest {
        val subject = coordinator(failWith = IllegalStateException("boom"))

        assertIs<RefreshOutcome.Failed>(subject.onResume())
        val next = subject.onNetworkRestored()

        assertTrue(next !is RefreshOutcome.SkippedInFlight, "после провала координатор не остаётся «занятым»")
        assertEquals(2, refreshCalls)
    }

    private companion object {
        /** Произвольная точка отсчёта инжектированных часов (эпоха-миллисекунды). */
        const val START_MILLIS = 1_774_000_000_000L
    }
}
