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
 *
 * Сеть — через [PushSubscriptions] (второй конструктор): Android-actual зовёт
 * её после получения токена; no-arg конструктор (тесты, dev-ветки графа) и
 * desktop-харнесс сеть не получают — jvm-заглушка игнорирует её
 * ([PushOutcome.Denied] всегда, kmp-push-fcm).
 */
public expect open class PushTokens {
    /** Без сети: заглушки/тесты (поведение исходов — см. actual'ы). */
    public constructor()

    /** С сетевым швом подписки (M9 push-android: [FcmPushSubscriptions]). */
    public constructor(subscriptions: PushSubscriptions?)

    /**
     * Запросить разрешение на уведомления и подписать устройство.
     * Мутаций данных не делает; сетевая подписка — деталь реализации (M9);
     * исход — [PushOutcome] (отказ молча не глотается: экран показывает
     * подсказку, parity RN showAlert(pushPermissionDenied)).
     */
    public open suspend fun requestSubscribe(language: String): PushOutcome

    /**
     * Текущее состояние подписки пушей (экран профиля, Stage 7 п.4;
     * RN `checkExpoSubscription` + mount-эффект `app/(tabs)/profile.tsx:60-73`).
     * Сетевой вызов статуса — деталь реализации (M9: GET fcm-subscription;
     * 404/недоступно → false); тумблер отражает результат. До M9 — false.
     */
    public open suspend fun subscriptionStatus(): Boolean

    /**
     * Отписка устройства (выключение тумблера профиля; RN
     * `unsubscribeFromExpoNotifications`). До M9 — no-op (подписки не было).
     */
    public open suspend fun unsubscribe()

    /**
     * Локальное тестовое уведомление (кнопка профиля; RN
     * `sendLocalTestNotification` — `lib/notifications.ts:122-137`, канал
     * HIGH). Активная реализация — M9; до неё не поддерживается (no-op).
     */
    public open suspend fun sendLocalTestNotification()
}

/**
 * Сетевые операции FCM-подписки (backend-contract.md №24–26) — единственный
 * шов сети в push-подсистему. Ошибки НЕ глотаются: не-OK/транспорт наверх как
 * [site.xmpp.greenthumb.core.network.ApiError] (тумблер профиля показывает
 * сообщение, RN-паритет catch(error: any)); исход [PushOutcome.Error]
 * решает actual.
 *
 * [platform] — значение провода `"android"`/`"ios"` (план Stage 9 п.4):
 * Android-actual всегда `"android"`. Реализация —
 * [site.xmpp.greenthumb.core.network.FcmPushSubscriptions].
 */
public interface PushSubscriptions {
    /** POST /api/push/subscribe-fcm {fcm_token, platform, language}. */
    public suspend fun subscribe(token: String, platform: String, language: String)

    /** DELETE /api/push/subscribe-fcm (выключение тумблера профиля). */
    public suspend fun unsubscribe()

    /** GET /api/push/fcm-subscription → subscribed (тумблер на mount). */
    public suspend fun status(): Boolean
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
