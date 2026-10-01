package com.greenthumbplantcare

import android.app.Application
import android.content.pm.ApplicationInfo
import com.google.firebase.crashlytics.FirebaseCrashlytics
import site.xmpp.greenthumb.SessionGraph
import site.xmpp.greenthumb.core.platform.gtNotificationSmallIconResId
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.PlantDatabases
import site.xmpp.greenthumb.core.storage.SecureStore
import site.xmpp.greenthumb.core.storage.registerAppContext
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.accountGate

/**
 * Владелец объектов времени жизни процесса (architecture.md §9, фикс M6
 * VAL-SHELL-003): [SessionGraph] с его DataStore-хранилищами и opener
 * per-user баз создаются здесь ОДИН раз на процесс. Пересоздание
 * [MainActivity] конфигурацией (поворот, масштаб шрифта) переиспользует их:
 * второй инстанс DataStore на тот же файл в живом процессе воспроизводимо
 * аварийно завершает приложение (`There are multiple DataStores active for
 * the same file`) — `configChanges="uiMode"` в манифесте маскирует только
 * смену темы, не остальные пересоздания.
 *
 * [Connectivity] сюда НЕ входит — это ресурс Activity (колбэк сети снимается
 * при её уничтожении, см. MainActivity).
 *
 * Взаимная ссылка [sessionGraph]↔[plantGate] разрешается лениво и отложенно:
 * `deleteUserDatabase` зовётся только при выходе/смене аккаунта, когда оба
 * ленивых поля уже инициализированы (см. MainActivity — прежний шов).
 */
class GreenThumbApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Контекст для isUpdateInstall() (Stage 3 п.6) — до первого доступа
        // к графу (факт «установка — обновление» читается в SessionGraph.create).
        registerAppContext(this)
        // small-icon уведомлений (M9): shared не видит R shell-модуля — id
        // регистрируется для самопоказа форграунда (GtFirebaseMessagingService
        // передаёт его явно) и локального тестового уведомления
        // (PushTokens.sendLocalTestNotification читает регистрацию).
        gtNotificationSmallIconResId = R.drawable.gt_notification_small
        // Crashlytics (M12 release-rollout-prep, VAL-REL-006): debug-сборки
        // (эмулятор, Maestro-валидация, FLAG_DEBUGGABLE — тот же критерий, что
        // у dev-оверрайда kill-switch) не отчитывают краши — пробные краши
        // валидаторов не попадают в crash-базлайн релиза. Release-сборка не
        // тронута (сбор по умолчанию).
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = false
        }
    }

    /** Один граф сессии на процесс: хранилища DataStore уникальны на процесс. */
    val sessionGraph: SessionGraph by lazy {
        SessionGraph.create(
            secure = SecureStore(this),
            settings = AppSettings(this),
            handoff = LegacyHandoff(this),
            deleteUserDatabase = { userId -> plantGate.closeAndDelete(userId) },
        )
    }

    /** Открытые per-user базы — тоже на процесс (один opener на файлы БД). */
    val plantGate: AccountPlantGate by lazy {
        PlantDatabases(this).accountGate(sessionGraph.api, sessionGraph.accountSession)
    }
}
