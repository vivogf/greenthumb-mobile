package site.xmpp.greenthumb.core.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android-actual [AppForeground]: `Application.ActivityLifecycleCallbacks`.
 *
 * `ON_RESUME` в чистом виде — `onActivityPaused` ставит `false`,
 * `onActivityResumed` ставит `true` (Activity одна — MainActivity; пауза
 * любой другой поверхности приложения тоже считается уходом на фон, это
 * согласуется с RN `AppState`, который привязан к resume/pause).
 *
 * Стартовое значение — `true`: экземпляр создаётся в композиции дашборда,
 * то есть пока приложение на переднем плане (новая композиция на фоне не
 * появляется: кадры Android приостанавливает). Благодаря этому переход
 * «ушёл в фон → вернулся» всегда виден как `false → true`, даже если
 * регистрация произошла уже после первого `onResume` Activity.
 *
 * Контекст процесса — [appContextOrNull]
 * ([site.xmpp.greenthumb.core.storage.registerAppContext] в
 * `GreenThumbApplication.onCreate`, до любой композиции). Если контекста нет
 * (не должно случиться в проде), регистрация пропускается: состояние
 * остаётся «на переднем плане» и триггеров ON_RESUME не будет — деградация
 * «без обновления по возврату», а не ложные обновления.
 *
 * Колбэки приходят на главном потоке (lifecycle Activity); [StateFlow] это
 * переживает. [close] идемпотентен.
 */
public actual class AppForeground actual constructor() {

    private val state = MutableStateFlow(true)

    public actual val isResumed: StateFlow<Boolean> = state

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) {
            state.value = false
        }

        override fun onActivityResumed(activity: Activity) {
            state.value = true
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Application, на который подписались; null — не подписывались ([close] уже сработал). */
    private var application: Application? =
        (appContextOrNull() as? Application)?.also { app ->
            runCatching { app.registerActivityLifecycleCallbacks(callbacks) }
        }

    public actual fun close() {
        val app = application ?: return
        application = null
        runCatching { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
