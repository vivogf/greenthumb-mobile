package com.greenthumbplantcare

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.platform.rotateFcmTokenAfterColdStart
import site.xmpp.greenthumb.core.platform.showForegroundGtNotification

/**
 * Перехват FCM-сообщений (M9 push-android, Stage 9 п.5): при открытом
 * приложении FCM SDK НЕ показывает notification-полезную нагрузку сам —
 * сервис показывает её через канал `default` (баннер + список + звук,
 * RN-паритет `setNotificationHandler`, lib/notifications.ts:24-35).
 *
 * Фоновые доставки (приложение убито/в фоне) показываются системой
 * автоматически — канал берётся из meta-data
 * `com.google.firebase.messaging.default_notification_channel_id` (манифест),
 * тап кладёт `data` в launch-intent (маршрутизацию deep-link делает
 * MainActivity/App — Stage 9 п.7).
 *
 * Ротация токена ([onNewToken]) — фикс отказа доставки из M9: сервер держит
 * старый токен, и после ротации пуши молча ломаются. Подписка обновляется
 * ТОЛЬКО если она была включена для текущего аккаунта; иначе push самовольно
 * не включается, ошибки не включают его «незаметно» ([FcmTokenRotation]).
 * Холодный процесс (FCM поднял процесс только ради токена) обрабатывает
 * [rotateFcmTokenAfterColdStart] — стартовая последовательность сессии
 * запускается фоном до проверки SignedIn (фикс scrutiny m9-push).
 *
 * Класс живёт в shell-модуле (androidApp), как MainActivity/Application:
 * самопоказ — [showForegroundGtNotification] из shared androidMain, логика
 * ротации — тестируемая функция shared commonMain (сервис — тонкая обёртка).
 */
public class GtFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        showForegroundGtNotification(
            context = applicationContext,
            iconResId = R.drawable.gt_notification_small,
            title = notification?.title,
            body = notification?.body,
            data = message.data,
        )
    }

    override fun onNewToken(token: String) {
        val graph = (application as GreenThumbApplication).sessionGraph
        tokenRotationScope.launch {
            // Холодный процесс (FCM поднял его только ради токена, App() не
            // запускался): rotateFcmTokenAfterColdStart сам запускает
            // идемпотентный startupIfNeeded и решает по его результату —
            // SignedIn доводит ротацию до шва подписки, иные исходы шов
            // не трогают. Никаких 401-восстановлений из фона сверх того,
            // что делает сам startup.
            val outcome = rotateFcmTokenAfterColdStart(
                manager = graph.manager,
                settings = graph.settings,
                rotation = graph.pushRotation,
                token = token,
                platform = PLATFORM_ANDROID,
            )
            Log.i(LOG_TAG, "FCM token rotation: ${outcome ?: "skipped (no SignedIn session)"}")
        }
    }

    private companion object {
        /** Значение провода platform (backend-contract.md №24). */
        const val PLATFORM_ANDROID = "android"

        const val LOG_TAG = "GtFcmService"
    }
}

/**
 * Scope фоновой ротации: доставка FCM приходит в живой процесс (сервис может
 * пересоздаваться на каждую доставку — scope на процесс, корутины короткие).
 */
private val tokenRotationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
