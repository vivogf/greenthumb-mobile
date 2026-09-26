package site.xmpp.greenthumb.core.platform

import android.content.Context

/**
 * Контекст процесса (Application) для платформенных сервисов, которым нужен
 * системный менеджер (clipboard, ACTION_VIEW, package-метки). Пишется один
 * раз из [site.xmpp.greenthumb.core.storage.registerAppContext]
 * (androidApp GreenThumbApplication.onCreate); до регистрации — null,
 * сервисы best-effort no-op.
 */
internal object AppContextHolder {
    @Volatile
    internal var context: Context? = null
}

/** Читатель контекста для actual'ов платформенных сервисов этого пакета. */
internal fun appContextOrNull(): Context? = AppContextHolder.context
