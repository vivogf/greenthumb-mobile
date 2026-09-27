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
}
