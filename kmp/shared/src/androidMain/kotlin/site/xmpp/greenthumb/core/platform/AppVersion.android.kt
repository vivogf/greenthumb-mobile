package site.xmpp.greenthumb.core.platform

import android.os.Build

/**
 * Android-актуал: versionName установленного пакета (источник —
 * packageInfo.versionName из build.gradle.kts androidApp; registerAppContext
 * в Application.onCreate гарантирует контекст до первого экрана).
 * null → «0.0.0» (не бывает на проде; RN-фолбэк '1.0.0' не переносится —
 * у KMP один источник правды).
 */
public actual object AppVersion {
    public actual val name: String by lazy {
        val context = appContextOrNull()
        context
            ?.packageManager
            ?.getPackageInfo(context.packageName, 0)
            ?.versionName
            ?: "0.0.0"
    }

    /**
     * versionCode (kill-switch Stage 12 п.1): longVersionCode с API 28,
     * ниже — устаревшее 32-битное поле (minSdk 24). Нет контекста → 0
     * (решение kill-switch — fail-open: 0 < любого min_supported_build
     * был бы ЗАБЛОКИРОВАН, но без контекста конфиг и не читается —
     * фактическое решение ниже, в RemoteKillSwitch.android).
     */
    public actual val code: Long by lazy {
        val context = appContextOrNull()
        val info = context?.packageManager?.getPackageInfo(context.packageName, 0)
        when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
    }
}
