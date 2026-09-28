package site.xmpp.greenthumb.core.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android-актуал push-подсистемы — РЕАЛЬНЫЙ FCM-конвейер (M9 push-android,
 * Stage 9 п.1–3 + п.5): канал `default` (параметры 1:1 с RN
 * `lib/notifications.ts:46-51`), запрос разрешения POST_NOTIFICATIONS,
 * токен FirebaseMessaging, затем сетевая подписка через шов
 * [PushSubscriptions] ([FcmPushSubscriptions] из графа сессии).
 *
 * Порядок шага — RN `registerForPushNotificationsAsync` +
 * `subscribeToExpoNotifications`, слитые в [requestSubscribe]:
 * 1) канал создаётся ДО запроса разрешения (RN создаёт канал первым);
 * 2) разрешение (API 33+: POST_NOTIFICATIONS; ниже — выдано неявно);
 * 3) токен FCM (FirebaseMessaging.token — подвеска Task'ы в suspend);
 * 4) POST /api/push/subscribe-fcm {fcm_token, platform:"android", language}.
 *
 * Ошибки НЕ глотаются: сетевые приходят [ApiError] наверх из шва, платформенные
 * сбои (Firebase не инициализирован, токен недоступен) — [PushOutcome.Error];
 * отказ в разрешении — [PushOutcome.Denied]. Экраны показывают состояние и
 * идут на дашборд, краша нет (RN-паритет catch(error: any)).
 *
 * Отмена вызывающим ([CancellationException]) наверх не мапится — как во всей
 * сети Stage 2.
 */
public actual open class PushTokens {
    private val subscriptions: PushSubscriptions?

    /** Без сети (тесты/dev-ветки графа): шаг дойдёт до токена и упадёт в Error. */
    public actual constructor() : this(null)

    /** С сетевым швом подписки (прод: [FcmPushSubscriptions] из SessionGraph). */
    public actual constructor(subscriptions: PushSubscriptions?) {
        this.subscriptions = subscriptions
    }

    public actual open suspend fun requestSubscribe(language: String): PushOutcome {
        return try {
            val activity = AppActivityHolder.activity
                ?: // Запросить разрешение негде (Activity не жива) — тот же
                // пользовательский исход, что у отказа (RN вернул null → Denied).
                return PushOutcome.Denied
            ensureDefaultNotificationChannel(activity)
            if (!hasPostNotificationsPermission(activity) && !requestPostNotificationsPermission(activity)) {
                return PushOutcome.Denied
            }
            val token = fcmToken()
            val subs = subscriptions
                ?: // Шов не подключён — ошибка сборки графа, не «отказ»
                // (пользовательский показ — текст сбоя, как у сетевой ошибки).
                return PushOutcome.Error("Push subscription channel is not wired")
            subs.subscribe(token, PLATFORM_ANDROID, language)
            PushOutcome.Subscribed(token)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            PushOutcome.Error(error.message ?: error.toString())
        }
    }

    /**
     * Состояние подписки (тумблер профиля): GET fcm-subscription через шов.
     * Ошибки наверх — экран решает «недоступен → off» (catch → false, RN-паритет).
     * Шва нет (no-arg конструктор) — подписки нет.
     */
    public actual open suspend fun subscriptionStatus(): Boolean =
        subscriptions?.status() ?: false

    /** Отписка (выключение тумблера): DELETE через шов; шва нет — отписывать нечего. */
    public actual open suspend fun unsubscribe() {
        subscriptions?.unsubscribe()
    }

    /**
     * Локальное тестовое уведомление (Stage 9 п.6, VAL-PUSH-006): тот же канал
     * `default` (HIGH), тексты — RN-хардкод (`lib/notifications.ts:126-128`,
     * не i18n). Без сети и без data-полезной нагрузки: тап по ней просто
     * открывает приложение (RN-паритет), локальный intent не выдаётся за
     * настоящий FCM push. Отказ показа (нет контекста/иконки, отзыв
     * разрешения на API 33+) — тихий no-op: кнопка уже показала модалку
     * успеха; система молча не покажет уведомление (не краш).
     */
    public actual open suspend fun sendLocalTestNotification() {
        val context = appContextOrNull() ?: return
        showLocalTestGtNotification(context)
    }

    /** Разрешение POST_NOTIFICATIONS: API 33+ runtime, ниже — выдано неявно. */
    private fun hasPostNotificationsPermission(activity: ComponentActivity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Запрос разрешения через registry Activity (тот же паттерн, что у пикера фото). */
    private suspend fun requestPostNotificationsPermission(activity: ComponentActivity): Boolean =
        suspendCancellableCoroutine { continuation ->
            val key = "gt-push-permission-${gtPushRequestCounter++}"
            lateinit var launcher: ActivityResultLauncher<String>
            launcher = activity.activityResultRegistry.register(
                key,
                ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                launcher.unregister()
                if (continuation.isActive) continuation.resume(isGranted)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            if (continuation.isActive) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

    /**
     * Токен FCM: `FirebaseMessaging.getInstance()` бросает IllegalStateException,
     * если FirebaseApp не инициализирован (нет google-services.json/плагина) —
     * ловится наверху как [PushOutcome.Error]; suspend-обёртка Task'ы.
     */
    private suspend fun fcmToken(): String {
        val messaging = FirebaseMessaging.getInstance()
        return suspendCancellableCoroutine { continuation ->
            messaging.token
                .addOnSuccessListener { token ->
                    if (continuation.isActive) continuation.resume(token)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
    }

    private companion object {
        /** Значение провода platform (backend-contract.md №24). */
        const val PLATFORM_ANDROID = "android"
    }
}

/** Счётчик ключей registry — уникальный на запрос разрешения (см. пикер фото). */
private var gtPushRequestCounter = 0
