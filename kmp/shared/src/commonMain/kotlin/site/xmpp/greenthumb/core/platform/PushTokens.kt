package site.xmpp.greenthumb.core.platform

/**
 * Push-подсистема (architecture.md §3 «PushTokens», §11; Stage 9 — FCM).
 * Первое объявление expect-интерфейса (фича screen-enable-notifications, M7).
 *
 * Рантайм-смысл: ЕДИНАЯ операция экрана «включить уведомления» —
 * «запросить разрешение и подписать устройство» (порт RN
 * `lib/notifications.ts:registerForPushNotificationsAsync` +
 * `subscribeToExpoNotifications`, слитые в один шаг; RN делил их потому, что
 * токен нужен и тумблеру профиля — KMP-тумблер Stage 9 читает статус
 * `GET /api/push/fcm-subscription` и зовёт [requestSubscribe] при включении).
 *
 * Активная реализация — M9 (Stage 9): FirebaseMessaging токен, канал
 * `default` (параметры 1:1: HIGH, вибрация [0,250,250,250], цвет #4a9a5a),
 * запрос разрешения, затем `POST /api/push/subscribe-fcm {fcm_token,
 * platform:"android", language}`. Язык подписки — wire-значение ('ru'/'en'),
 * решает вызывающий экран (тот же контракт, что у Expo-подписки RN).
 *
 * До M9 актуалы — ЗАГЛУШКИ: разрешение не запрашивается, токена нет, экран
 * enable-notifications ведёт себя как при отказе ([PushOutcome.Denied]).
 * На desktop-харнессе заглушка постоянна (пуш-токена у харнесса не бывает).
 *
 * open — тестовая инжекция: jvmTest подменяет исход вызова двойником
 * ([PushOutcome] — все три исхода экранного флоу).
 */
public expect open class PushTokens() {
    /**
     * Запросить разрешение на уведомления и подписать устройство.
     * Мутаций данных не делает; сетевая подписка — деталь реализации (M9);
     * исход — [PushOutcome] (отказ молча не глотается: экран показывает
     * подсказку, parity RN showAlert(pushPermissionDenied)).
     */
    public open suspend fun requestSubscribe(language: String): PushOutcome
}

/** Исход «включить уведомления» (порт веток RN enable-notifications.tsx). */
public sealed class PushOutcome {
    /**
     * Разрешение выдано, токен получен, подписка отправлена (RN: token != null
     * после subscribeToExpoNotifications). [token] — FCM/Expo-токен провайдера.
     */
    public data class Subscribed(public val token: String) : PushOutcome()

    /**
     * Разрешение не выдано (или токен недоступен — эмулятор/заглушка до M9):
     * RN возвращает null → экран показывает подсказку и идёт на дашборд.
     */
    public data object Denied : PushOutcome()

    /**
     * Сбой шага (транспорт/сеть/сервис): RN ловил исключение и показывал
     * error.message; навигация на дашборд всё равно выполняется.
     */
    public data class Error(public val message: String) : PushOutcome()
}
