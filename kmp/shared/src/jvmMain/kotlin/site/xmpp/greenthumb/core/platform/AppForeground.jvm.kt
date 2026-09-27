package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * JVM-actual [AppForeground] для desktop-харнесса: всегда «на переднем
 * плане», переходов `false → true` не бывает.
 *
 * У харнесса нет фонового режима (тот же приём, что «jvmMain всегда
 * онлайн» у [Connectivity]): десктопное окно может уйти за другое окно, но
 * это не `ON_RESUME` мобильного приложения, а триггер VAL-DASH-010
 * проверяется реальным Android. Единственное следствие для десктопа —
 * подписка на передний план молчит; первый показ и восстановление сети
 * работают как раньше.
 */
public actual class AppForeground actual constructor() {

    private val state = MutableStateFlow(true)

    public actual val isResumed: StateFlow<Boolean> = state

    public actual fun close(): Unit = Unit
}
