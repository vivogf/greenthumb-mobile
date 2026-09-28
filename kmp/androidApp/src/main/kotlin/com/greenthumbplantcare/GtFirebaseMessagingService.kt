package com.greenthumbplantcare

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
 * тап кладёт `data` в launch-intent.
 *
 * Смена токена ([FirebaseMessagingService.onNewToken]) не обрабатывается —
 * RN-паритет: expo-notifications тоже не ретранслировал ротацию; подписка
 * обновляется следующим включением тумблера. Сознательное ограничение Stage 9.
 *
 * Класс живёт в shell-модуле (androidApp), как MainActivity/Application:
 * самопоказ — [showForegroundGtNotification] из shared androidMain.
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
}
