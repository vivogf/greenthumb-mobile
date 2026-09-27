package site.xmpp.greenthumb.core.platform

/**
 * Android-актуал push-подсистемы — ЗАГЛУШКА до M9 (Stage 9, фича
 * push-android): токен FirebaseMessaging, канал `default`, запрос разрешения
 * и `POST /api/push/subscribe-fcm`. Контракт интерфейса — [PushTokens].
 *
 * Заглушка НЕ запрашивает разрешение (запрос в M9 вместе с каналом — RN
 * создаёт канал до запроса, разбивать нечего) и возвращает [PushOutcome.Denied]:
 * экран enable-notifications показывает подсказку и идёт на дашборд —
 * тот же пользовательский исход, что у отказа в разрешении (VAL-INTRO-002).
 */
public actual open class PushTokens actual constructor() {
    public actual open suspend fun requestSubscribe(language: String): PushOutcome = PushOutcome.Denied

    /** Заглушка до M9: подписки нет (fcm-subscription читает M9). */
    public actual open suspend fun subscriptionStatus(): Boolean = false

    /** No-op: отписывать нечего (подписки не было). */
    public actual open suspend fun unsubscribe() = Unit

    /** No-op до M9: локальное тестовое уведомление не поддерживается. */
    public actual open suspend fun sendLocalTestNotification() = Unit
}
