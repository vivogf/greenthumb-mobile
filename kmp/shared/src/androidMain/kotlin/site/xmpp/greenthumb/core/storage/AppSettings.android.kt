package site.xmpp.greenthumb.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import site.xmpp.greenthumb.core.network.UserDto

/**
 * Android-actual AppSettings (architecture.md §6): DataStore-файл
 * `greenthumb_settings.preferences_pb` в filesDir (domain "file").
 *
 * Из бэкапов НЕ исключается — настройки несекретные (recovery key — в
 * SecureStore, его файл исключён) и должны переживать восстановление.
 * Старый AsyncStorage (SQLite RKStorage) не читается: legacy-значения
 * приезжают только через handoff-файл (kmp-legacy-handoff).
 *
 * Context — applicationContext androidApp-активности (инжектируется точкой
 * входа Stage 6; до неё actual создаётся лениво из текущего процесса).
 */
public actual class AppSettings(
    appContext: Context,
) {
    private val delegate = AndroidAppSettingsStorage(appContext.applicationContext)

    actual suspend fun getLanguage(): AppLanguage? = delegate.getLanguage()

    actual suspend fun setLanguage(language: AppLanguage) = delegate.setLanguage(language)

    actual suspend fun getLayoutMode(): LayoutMode? = delegate.getLayoutMode()

    actual suspend fun setLayoutMode(mode: LayoutMode) = delegate.setLayoutMode(mode)

    actual suspend fun getTheme(): ThemePreference? = delegate.getTheme()

    actual suspend fun setTheme(theme: ThemePreference) = delegate.setTheme(theme)

    actual suspend fun isIntroSeen(): Boolean = delegate.isIntroSeen()

    actual suspend fun setIntroSeen() = delegate.setIntroSeen()

    actual suspend fun getCachedUser(): UserDto? = delegate.getCachedUser()

    actual suspend fun setCachedUser(user: UserDto) = delegate.setCachedUser(user)

    actual suspend fun clearCachedUser() = delegate.clearCachedUser()

    actual val language: Flow<AppLanguage?> = delegate.language
    actual val layoutMode: Flow<LayoutMode?> = delegate.layoutMode
    actual val theme: Flow<ThemePreference?> = delegate.theme
    actual val introSeen: Flow<Boolean> = delegate.introSeen
    actual val cachedUser: Flow<UserDto?> = delegate.cachedUser

    actual suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) = delegate.applyLegacyHandoffValues(language, theme, layoutMode, introSeen)
}

/**
 * Инжектируемая android-реализация (открытый конструктор для тестов/входной
 * точки). Логика описана в [PreferencesAppSettingsCore] / [AppSettings].
 */
public class AndroidAppSettingsStorage(
    private val appContext: Context,
) : PreferencesAppSettingsCore() {

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            // Полный файл-путь (domain "file" в backup-правилах), не sharedPreferences.
            produceFile = { appContext.getFileStreamPath(DATA_FILE_NAME) },
        )

    private companion object {
        const val DATA_FILE_NAME = "greenthumb_settings.preferences_pb"
    }
}
