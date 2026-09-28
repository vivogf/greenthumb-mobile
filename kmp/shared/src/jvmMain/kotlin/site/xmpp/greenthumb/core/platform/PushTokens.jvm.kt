package site.xmpp.greenthumb.core.platform

/**
 * Desktop-актуал (харнесс) — постоянная заглушка: push-токена у desktop-процесса
 * не бывает (architecture.md §3: push = expect с jvm-заглушкой «лог + дефолты»).
 * Экран enable-notifications на харнессе ведёт себя как при отказе
 * ([PushOutcome.Denied]) — подсказка + дашборд (VAL-INTRO-002 desktop-ветка).
 *
 * Шов сети ([PushSubscriptions], M9 push-android) на desktop НЕ используется:
 * граф передаёт [FcmPushSubscriptions] всем таргетам одинаково, но заглушка
 * его игнорирует — сетевых вызовов нет (тест kmp-push-fcm фиксирует контракт).
 */
public actual open class PushTokens {
    public actual constructor() : this(null)

    /** Принят, но не используется (Firebase на JVM не существует). */
    public actual constructor(@Suppress("UNUSED_PARAMETER") subscriptions: PushSubscriptions?)

    public actual open suspend fun requestSubscribe(language: String): PushOutcome = PushOutcome.Denied

    /** Заглушка харнесса: пуш-подписки у desktop-процесса не бывает. */
    public actual open suspend fun subscriptionStatus(): Boolean = false

    /** No-op: отписывать нечего (подписки не было). */
    public actual open suspend fun unsubscribe() = Unit

    /** No-op: локальное тестовое уведомление не поддерживается (харнесс). */
    public actual open suspend fun sendLocalTestNotification() = Unit
}
