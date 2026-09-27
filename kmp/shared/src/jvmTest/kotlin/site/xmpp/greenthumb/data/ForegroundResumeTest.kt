package site.xmpp.greenthumb.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 * VAL-DASH-010 — возврат на открытый дашборд после >60 с на фоне обязан
 * обновлять данные (серверные удаления уходят из списка без перезапуска),
 * а соседние триггеры (первый показ, восстановление сети, повторный
 * возврат) — дедуплицироваться.
 *
 * Тест гоняет ТОТ ЖЕ путь, что подключает прод
 * (`DashboardScreen` → [RefreshCoordinator.attachForeground]): поток
 * `AppForeground.isResumed` → события `false → true` → [onResume] →
 * исход в `onOutcome`. Часы инжектируются — без реального ожидания 60 с.
 *
 * Двойник репозитория ведёт себя как `PlantRepository.refresh()`:
 * успех заменяет список содержимым «сервера» (replaceAll) и пишет
 * `sync_meta`; провал таблицу не трогает (VAL-DATA-001) и окно свежести
 * не занимает. Сохранение журнала pending-мутаций при refresh покрыто
 * `PlantRepositoryTest` (VAL-DATA-006) — репозиторий здесь не меняется.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent: прогон корутин без продвижения времени
class ForegroundResumeTest {

    // ------------------------------------------------------------------
    // Двойники
    // ------------------------------------------------------------------

    private class FakeSyncMeta(var lastSynced: Long? = null) : SyncMetaSource {
        override suspend fun lastSyncedAtMillis(): Long? = lastSynced
    }

    private class TestClock(var now: Long = START_MILLIS) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private val clock = TestClock()
    private val syncMeta = FakeSyncMeta()

    /** Сколько раз реально сходили на сервер. */
    private var refreshCalls = 0

    /** «Серверная правда»: что отдаст следующий GET /api/plants. */
    private var serverPlants = listOf("ficus", "monstera")

    /** Что лежит в Room (наблюдение списка на дашборде). */
    private var localPlants = listOf("ficus", "monstera")

    /** Следующий refresh падает (сеть); провал sync_meta не трогает. */
    private var failNext = false

    private fun coordinator(): RefreshCoordinator = RefreshCoordinator(
        syncMeta = syncMeta,
        nowMillis = { clock.now },
    ) {
        refreshCalls++
        if (failNext) {
            failNext = false
            throw IllegalStateException("network down")
        }
        localPlants = serverPlants
        syncMeta.lastSynced = clock.now
    }

    /** Прод-подключение: `DashboardScreen` делает ровно это. */
    private fun CoroutineScope.attachLikeDashboard(
        subject: RefreshCoordinator,
        isResumed: MutableStateFlow<Boolean>,
        outcomes: MutableList<RefreshOutcome>,
    ) = subject.attachForeground(this, isResumed) { outcomes += it }

    // ------------------------------------------------------------------
    // Семантика триггера
    // ------------------------------------------------------------------

    @Test
    fun `initially resumed value is a reference not a trigger`() = runTest {
        val outcomes = mutableListOf<RefreshOutcome>()
        val job = attachLikeDashboard(coordinator(), MutableStateFlow(true), outcomes)
        runCurrent()

        assertEquals(0, refreshCalls, "старт «на переднем плане» — не ON_RESUME")
        assertTrue(outcomes.isEmpty(), "первое значение подписки не порождает исхода")

        job.cancel()
    }

    @Test
    fun `initially backgrounded subscription waits for the real return`() = runTest {
        // sync_meta пуст — данные никогда не сходились, любой триггер обязан обновить.
        val outcomes = mutableListOf<RefreshOutcome>()
        val resumed = MutableStateFlow(false)
        val job = attachLikeDashboard(coordinator(), resumed, outcomes)
        runCurrent()

        assertEquals(0, refreshCalls, "подписка на фоне ничего не делает")

        resumed.value = true
        runCurrent()

        assertEquals(1, refreshCalls, "первый реальный возврат обновляет")
        assertEquals(RefreshOutcome.Refreshed, outcomes.single())
        job.cancel()
    }

    @Test
    fun `return after more than 60s in background adopts server truth`() = runTest {
        syncMeta.lastSynced = clock.now
        serverPlants = listOf("monstera") // «сервер»: ficus удалён, пока приложение на фоне
        val outcomes = mutableListOf<RefreshOutcome>()
        val resumed = MutableStateFlow(true)
        val job = attachLikeDashboard(coordinator(), resumed, outcomes)
        runCurrent()
        assertEquals(0, refreshCalls, "пока приложение на переднем плане — без запросов")

        clock.advance(61_000L)
        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(1, refreshCalls, "возврат после >60 с — ровно один refresh")
        assertEquals(listOf("monstera"), localPlants, "серверное удаление принято без перезапуска")
        assertEquals(clock.now, syncMeta.lastSynced, "sync_meta обновился")
        assertEquals(RefreshOutcome.Refreshed, outcomes.single())

        job.cancel()
    }

    // ------------------------------------------------------------------
    // Дедуп соседних триггеров
    // ------------------------------------------------------------------

    @Test
    fun `return within freshness window does not hit the network`() = runTest {
        syncMeta.lastSynced = clock.now
        val outcomes = mutableListOf<RefreshOutcome>()
        val resumed = MutableStateFlow(true)
        val job = attachLikeDashboard(coordinator(), resumed, outcomes)
        runCurrent()
        clock.advance(59_000L)
        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(0, refreshCalls, "данные моложе 60 с — сети не касаемся")
        assertEquals(RefreshOutcome.SkippedFresh, outcomes.single())
        job.cancel()
    }

    @Test
    fun `return right after first show does not duplicate the request`() = runTest {
        val subject = coordinator()
        val outcomes = mutableListOf<RefreshOutcome>()
        val resumed = MutableStateFlow(true)

        assertEquals(RefreshOutcome.Refreshed, subject.onFirstShow(), "первый показ обновил")
        val job = attachLikeDashboard(subject, resumed, outcomes)
        runCurrent()
        clock.advance(10_000L)
        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(1, refreshCalls, "ON_RESUME дедуплицирован первым показом")
        assertEquals(RefreshOutcome.SkippedFresh, outcomes.single())
        job.cancel()
    }

    @Test
    fun `reconnect and return in one window produce a single request`() = runTest {
        syncMeta.lastSynced = clock.now - 120_000L // протухло, пока приложение было на фоне
        val online = MutableStateFlow(true)
        val resumed = MutableStateFlow(true)
        val outcomes = mutableListOf<RefreshOutcome>()
        val subject = coordinator()
        val job = subject.attachConnectivity(this, online)
        val foregroundJob = attachLikeDashboard(subject, resumed, outcomes)
        runCurrent()

        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(1, refreshCalls, "восстановление сети обновило на протухших данных")

        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(1, refreshCalls, "ON_RESUME дедуплицирован восстановлением сети")
        assertEquals(RefreshOutcome.SkippedFresh, outcomes.single())

        job.cancel()
        foregroundJob.cancel()
    }

    @Test
    fun `return while another trigger's refresh is in flight is dropped`() = runTest {
        syncMeta.lastSynced = null
        val gate = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<RefreshOutcome>()
        val subject = RefreshCoordinator(
            syncMeta = syncMeta,
            nowMillis = { clock.now },
        ) {
            refreshCalls++
            gate.await()
            localPlants = serverPlants
            syncMeta.lastSynced = clock.now
        }
        val resumed = MutableStateFlow(true)
        val job = attachLikeDashboard(subject, resumed, outcomes)
        runCurrent()

        // Первый показ висит на ответе — второй триггер не должен плодить запрос.
        val firstShow = launch { subject.onFirstShow() }
        runCurrent()
        assertEquals(1, refreshCalls, "первый показ ушёл в сеть и висит на ответе")

        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(1, refreshCalls, "ON_RESUME во время полёта не плодит запрос")
        assertIs<RefreshOutcome.SkippedInFlight>(outcomes.single())

        gate.complete(Unit)
        firstShow.join()
        assertEquals(1, refreshCalls, "после ответа запрос не повторился")
        job.cancel()
    }

    // ------------------------------------------------------------------
    // Провал обновления по возврату
    // ------------------------------------------------------------------

    @Test
    fun `failed return is reported and retried on the next background cycle`() = runTest {
        syncMeta.lastSynced = clock.now - 120_000L
        serverPlants = listOf("monstera") // «сервер»: ficus удалён, пока приложение на фоне
        val outcomes = mutableListOf<RefreshOutcome>()
        val resumed = MutableStateFlow(true)
        val job = attachLikeDashboard(coordinator(), resumed, outcomes)
        runCurrent()

        failNext = true
        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertIs<RefreshOutcome.Failed>(outcomes.last(), "провал виден экрану (полоска ошибки)")
        assertEquals(clock.now - 120_000L, syncMeta.lastSynced, "провал sync_meta не трогает")
        assertEquals(listOf("ficus", "monstera"), localPlants, "таблица не тронута")

        resumed.value = false
        runCurrent()
        resumed.value = true
        runCurrent()

        assertEquals(RefreshOutcome.Refreshed, outcomes.last(), "следующий возврат повторяет попытку")
        assertEquals(2, refreshCalls)
        assertEquals(listOf("monstera"), localPlants, "после успеха серверная правда принята")
        job.cancel()
    }

    private companion object {
        /** Произвольная точка отсчёта инжектированных часов (эпоха-миллисекунды). */
        const val START_MILLIS = 1_774_000_000_000L
    }
}
