package site.xmpp.greenthumb.core.platform

/**
 * Версия приложения для футера профиля (Stage 7 п.4, RN `Constants.expoConfig
 * ?.version` в `app/(tabs)/profile.tsx`; VAL-PROFILE-007).
 *
 * androidMain — versionName из build.gradle.kts androidApp (один источник
 * правды с сборкой); jvmMain — константа харнесса (desktop не публикуется).
 * RN-фолбэк `?? '1.0.0'` не переносится: KMP-источник всегда определён.
 */
public expect object AppVersion {
    /** versionName (RN app.json version). */
    public val name: String

    /**
     * versionCode — build number установки (Stage 12 п.1, VAL-REL-001):
     * kill-switch сверяет его с Remote Config `min_supported_build`.
     * androidMain — packageInfo.longVersionCode; jvmMain — константа харнесса
     * (kill-switch на desktop не активен, значение ни с чем не сравнивается).
     */
    public val code: Long
}
