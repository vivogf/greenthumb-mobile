package site.xmpp.greenthumb.core.platform

/**
 * Desktop-актуал (харнесс) — постоянная заглушка: push-токена у desktop-процесса
 * не бывает (architecture.md §3: push = expect с jvm-заглушкой «лог + дефолты»).
 * Экран enable-notifications на харнессе ведёт себя как при отказе
 * ([PushOutcome.Denied]) — подсказка + дашборд (VAL-INTRO-002 desktop-ветка).
 */
public actual open class PushTokens actual constructor() {
    public actual open suspend fun requestSubscribe(language: String): PushOutcome = PushOutcome.Denied

    /** Заглушка харнесса: пуш-подписки у desktop-процесса не бывает. */
    public actual open suspend fun subscriptionStatus(): Boolean = false

    /** No-op: отписывать нечего (подписки не было). */
    public actual open suspend fun unsubscribe() = Unit

    /** No-op до M9: локальное тестовое уведомление не поддерживается. */
    public actual open suspend fun sendLocalTestNotification() = Unit
}
