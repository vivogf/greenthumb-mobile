package site.xmpp.greenthumb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import site.xmpp.greenthumb.core.platform.DEFAULT_MIN_SUPPORTED_BUILD
import site.xmpp.greenthumb.core.platform.KillSwitchVerdict
import site.xmpp.greenthumb.core.platform.RemoteKillSwitch
import site.xmpp.greenthumb.core.platform.evaluateKillSwitch

/**
 * Держатель вердикта kill-switch (Stage 12 п.1, VAL-REL-001) — один на
 * процесс (живёт в [SessionGraph], как push): пересоздание Activity
 * конфигурацией НЕ перечитывает конфиг и не мигает контентом — вердикт
 * переживает пересоздание корня.
 *
 * Политика запуска: [checkIfNeeded] зовётся из App() параллельно со стартом
 * сессии ([SessionManager.startupIfNeeded]) и НЕ гейтит старт — Remote Config
 * fetch с коротким таймаутом (android-actual); пока вердикт не решён
 * ([verdict] == null), UI работает как обычно. Отсюда fail-open-UX: разблокировка
 * возможна только по факту fetch'а; экран обновления появляется поверх
 * содержимого сразу после решения (максимум через таймаут fetch'а).
 *
 * Повторная оценка — при следующем старте ПРОЦЕССА (не Activity): kill-switch
 * Stage 12 — механизм «остановить распространение сломанной версии», а не
 * фича реального времени; сниженное значение снимает экран при следующем
 * запуске приложения, БЕЗ переустановки (acceptance VAL-REL-001).
 */
public class KillSwitchGate(
    private val switch: RemoteKillSwitch,
    private val currentBuild: Long,
) {
    private val state = MutableStateFlow<KillSwitchVerdict?>(null)

    /**
     * Вердикт текущего процесса: null — проверка ещё не решена (UI работает
     * как обычно, fail-open), далее [KillSwitchVerdict.Allowed]/[KillSwitchVerdict
     * .Blocked].
     */
    public val verdict: StateFlow<KillSwitchVerdict?> = state.asStateFlow()

    /**
     * Проверка (идемпотентна: после первого решения конфиг на процесс не
     * перечитывается). Сам fetch не бросает (fail-open в actual), но страховка
     * от любого неучтённого сбоя — та же семантика: kill-switch не ломает старт.
     */
    public suspend fun checkIfNeeded() {
        if (state.value != null) return
        val minimum = try {
            switch.fetchMinimumSupportedBuild()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Fail-open: сбой чтения конфига = «обновление не требуется»
            // (ограничение kill-switch, missionDir/library/kill-switch.md).
            DEFAULT_MIN_SUPPORTED_BUILD
        }
        state.value = evaluateKillSwitch(currentBuild, minimum)
    }
}
