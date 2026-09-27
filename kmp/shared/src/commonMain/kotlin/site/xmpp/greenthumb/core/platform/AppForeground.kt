package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Источник триггера `ON_RESUME` (VAL-DASH-010): «приложение вернулось на
 * передний план». Порт RN-связки `AppState` → `focusManager.setFocused`
 * (`app/_layout.tsx`), которой в KMP-оболочке не было — координатор
 * обновления слушал только первый показ и сеть
 * ([site.xmpp.greenthumb.data.RefreshCoordinator]).
 *
 * [isResumed] — «горячее» состояние: подписчик получает текущее значение
 * немедленно, дальше — каждый переход. Возврат на передний план = переход
 * `false → true`; первое значение подписки — ориентир, не событие (тот же
 * приём, что `drop(1)` у подписки на сеть), за первый refresh отвечает
 * первый показ экрана. Свежесть данных координатор проверяет сам — даже
 * «лишний» триггер не даёт параллельного запроса.
 *
 * Владелец — экран дашборда: экземпляр создаётся при показе и закрывается
 * вместе с ним ([close] снимает платформенную подписку).
 *
 * Платформенные actual'ы:
 * - androidMain: `Application.ActivityLifecycleCallbacks`
 *   (`onActivityPaused`/`onActivityResumed`); контекст процесса берётся из
 *   [AppContextHolder] — тот же шов, что у clipboard/package-меток;
 * - jvmMain: всегда `true` — у desktop-харнесса фонового режима нет, а
 *   триггер этого фичи проверяется реальным Android (VAL-DASH-010), не
 *   десктопом (параллель с «jvmMain всегда онлайн» у [Connectivity]).
 */
public expect class AppForeground() {

    /** Приложение сейчас на переднем плане. */
    public val isResumed: StateFlow<Boolean>

    /** Снимает платформенную подписку; повторный вызов идемпотентен. */
    public fun close()
}
