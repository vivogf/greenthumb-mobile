package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * JVM-actual [Connectivity] для desktop-харнесса: всегда «онлайн».
 *
 * Источника состояния сети у харнесса нет (Stage 4 п.8: «jvmMain — всегда
 * онлайн»), а офлайн по умолчанию спрятал бы рабочие экраны за полосой «нет
 * сети». Реальные сетевые сбои приходят обычным путём — через
 * [site.xmpp.greenthumb.core.network.ApiError.Network].
 */
public actual class Connectivity actual constructor(appContext: Any) {

    private val state = MutableStateFlow(true)

    public actual val isOnline: StateFlow<Boolean> = state

    public actual fun close(): Unit = Unit
}
