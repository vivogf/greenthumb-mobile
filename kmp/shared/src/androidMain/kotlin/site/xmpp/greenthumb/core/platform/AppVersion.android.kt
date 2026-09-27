package site.xmpp.greenthumb.core.platform

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
}
