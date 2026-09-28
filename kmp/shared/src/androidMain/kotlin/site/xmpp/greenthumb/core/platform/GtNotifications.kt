package site.xmpp.greenthumb.core.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * Уведомительная обвязка Android (M9 push-android): канал `default` с
 * параметрами 1:1 из RN `lib/notifications.ts:46-51` + самопоказ
 * форграунд-пуша (Stage 9 п.5). Пользователь сервиса —
 * [GtFirebaseMessagingService] (манифест androidApp; класс в shell-модуле,
 * т.к. shared не ссылается на ресурсы androidApp — иконка передаётся id'ом).
 *
 * Канал живёт здесь (а не в actual'е [PushTokens]) — его создаёт и поток
 * подписки, и поток показа: создание идемпотентно.
 */
internal const val GT_NOTIFICATION_CHANNEL_ID = "default"

/** Цвет LED/акцента уведомлений — RN `lightColor: '#4a9a5a'`. */
internal const val GT_NOTIFICATION_LIGHT_COLOR = 0xFF4A9A5A.toInt()

/**
 * Канал `default` (VAL-PUSH-002): имя — RN-хардкод «Plant Care Reminders»
 * (не i18n — паритет исходника), importance HIGH, вибрация [0,250,250,250],
 * light color #4a9a5a. Повторный вызов ничего не делает: система не меняет
 * параметры канала после создания — пересоздание только плодило бы каналы.
 */
internal fun ensureDefaultNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(GT_NOTIFICATION_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        GT_NOTIFICATION_CHANNEL_ID,
        "Plant Care Reminders",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        vibrationPattern = longArrayOf(0, 250, 250, 250)
        lightColor = GT_NOTIFICATION_LIGHT_COLOR
    }
    manager.createNotificationChannel(channel)
}

/**
 * small-icon ресурс уведомлений. Shared не видит R shell-модуля
 * (прецедент сервиса самопоказа — иконка передаётся id'ом), поэтому androidApp
 * регистрирует id в Application.onCreate; до регистрации — 0,
 * показ локального тестового уведомления best-effort no-op.
 */
@Volatile
public var gtNotificationSmallIconResId: Int = 0

/**
 * Тексты локального тестового уведомления — RN-хардкод (parity-файл: не i18n,
 * порт `lib/notifications.ts:126-128` sendLocalTestNotification).
 */
internal const val GT_LOCAL_TEST_TITLE = "GreenThumb 💚"
internal const val GT_LOCAL_TEST_BODY = "Уведомления работают! 🌿"

/**
 * Локальное тестовое уведомление (Stage 9 п.6, кнопка профиля — VAL-PUSH-006):
 * тот же канал `default` (HIGH) и тот же показ, что у форграунд-пуша. Data НЕ
 * кладётся (RN-паритет: тестовая пуши без plant_id — тап просто открывает
 * приложение; локальный intent не выдаётся за настоящий FCM push —
 * маршрутизация deep-link на нём не запускается).
 *
 * public: зовётся actual'ом [PushTokens] (shared); иконка — [gtNotificationSmallIconResId].
 */
public fun showLocalTestGtNotification(context: Context) {
    val iconResId = gtNotificationSmallIconResId
    if (iconResId == 0) return
    showForegroundGtNotification(
        context = context,
        iconResId = iconResId,
        title = GT_LOCAL_TEST_TITLE,
        body = GT_LOCAL_TEST_BODY,
        data = emptyMap(),
    )
}

/** База id показываемых уведомлений (стек: новое — новый id, как у expo-notifications). */
private val gtNotificationIdCounter = AtomicInteger(2001)

/**
 * Самопоказ форграунд-пуша (Stage 9 п.5, VAL-PUSH-004): FCM при открытом
 * приложении НЕ показывает notification-полезную нагрузку сам — RN-паритет
 * `setNotificationHandler` (баннер + список + звук, `lib/notifications.ts:24-35`):
 * баннер/звук даёт канал HIGH, список — та же запись в шторке.
 *
 * Контракт данных (план Stage 9 п.7): `data.plant_id` → тап открывает
 * `plant/{id}`; копия data кладётся в launch-intent как есть — маршрутизацию
 * deep-link делает фича kmp-push-deeplink-local (здесь только транспорт).
 *
 * [iconResId] — small icon статуса (ресурс androidApp, monochrome-глиф).
 * POST_NOTIFICATIONS на API 33+ проверен потоком подписки; при отзыве система
 * молча не покажет (не краш) — линт-подавление осознанное.
 *
 * public: зовётся сервисом androidApp ([GtFirebaseMessagingService]) — shared
 * не ссылается на ресурсы shell-модуля, иконка передаётся id'ом.
 */
@Suppress("MissingPermission")
public fun showForegroundGtNotification(
    context: Context,
    iconResId: Int,
    title: String?,
    body: String?,
    data: Map<String, String>,
) {
    ensureDefaultNotificationChannel(context)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    data.forEach { (key, value) -> launch.putExtra(key, value) }
    val contentIntent = PendingIntent.getActivity(
        context,
        data.hashCode(),
        launch,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    // Notification.Builder — фреймворк, без androidx.core (новый пин не
    // заводится): канальный конструктор с API 26, ниже — устаревший
    // безканальный (настройки канала система игнорирует, как и RN до O).
    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, GT_NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
    val notification = builder
        .setSmallIcon(iconResId)
        .setContentTitle(title)
        .setContentText(body)
        .setColor(GT_NOTIFICATION_LIGHT_COLOR)
        .setAutoCancel(true)
        .setContentIntent(contentIntent)
        .setCategory(Notification.CATEGORY_REMINDER)
        .build()
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.notify(gtNotificationIdCounter.getAndIncrement(), notification)
}
