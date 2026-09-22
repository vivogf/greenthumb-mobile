package site.xmpp.greenthumb.core.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android-actual маркера обновления (план Stage 3 п.6): «обновление» =
 * `lastUpdateTime ≠ firstInstallTime` для пакета приложения.
 *
 * Обе метки читаются одним `getPackageInfo`; с API 33+ правильный путь —
 * `PackageManager.PackageInfoFlags`, ниже — классическая int-маска (0).
 * Худший исход любого сбоя (нет пакета/меток) — false: обычный экран входа
 * вместо объяснения, не блокировка входа. Нулевые метки трактуются как
 * «неизвестно» (свежие установки иногда дают 0) → false.
 */
public actual fun isUpdateInstall(): Boolean {
    val context = appContextOrNull() ?: return false
    return runCatching {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(context.packageName, 0)
        }
        val firstInstall = info.firstInstallTime
        val lastUpdate = info.lastUpdateTime
        firstInstall != 0L && lastUpdate != 0L && firstInstall != lastUpdate
    }.getOrDefault(false)
}

/** Контекст процесса (Application): androidApp-точка входа пишет его на onCreate. */
private var cachedAppContext: Context? = null

/** Регистрация контекста приложения (androidApp MainActivity/Application, onCreate). */
public fun registerAppContext(context: Context) {
    cachedAppContext = context.applicationContext
}

private fun appContextOrNull(): Context? = cachedAppContext
