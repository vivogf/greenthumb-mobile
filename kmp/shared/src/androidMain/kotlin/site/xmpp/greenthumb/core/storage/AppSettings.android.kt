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
public actual class AppSettings actual constructor(
    appContext: Any,
) : AppPreferencesStore {
    private val delegate = AndroidAppSettingsStorage((appContext as Context).applicationContext)

    actual override suspend fun getLanguage(): AppLanguage? = delegate.getLanguage()

    actual override suspend fun setLanguage(language: AppLanguage) = delegate.setLanguage(language)

    actual override suspend fun getLayoutMode(): LayoutMode? = delegate.getLayoutMode()

    actual override suspend fun setLayoutMode(mode: LayoutMode) = delegate.setLayoutMode(mode)

    actual override suspend fun getTheme(): ThemePreference? = delegate.getTheme()

    actual override suspend fun setTheme(theme: ThemePreference) = delegate.setTheme(theme)

    actual override suspend fun isIntroSeen(): Boolean = delegate.isIntroSeen()

    actual override suspend fun setIntroSeen() = delegate.setIntroSeen()

    actual override suspend fun getCachedUser(): UserDto? = delegate.getCachedUser()

    actual override suspend fun setCachedUser(user: UserDto) = delegate.setCachedUser(user)

    actual override suspend fun clearCachedUser() = delegate.clearCachedUser()

    actual override val language: Flow<AppLanguage?> = delegate.language
    actual override val layoutMode: Flow<LayoutMode?> = delegate.layoutMode
    actual override val theme: Flow<ThemePreference?> = delegate.theme
    actual override val introSeen: Flow<Boolean> = delegate.introSeen
    actual override val cachedUser: Flow<UserDto?> = delegate.cachedUser

    actual override suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) = delegate.applyLegacyHandoffValues(language, theme, layoutMode, introSeen)
}

/**
 * Инжектируемая android-реализация (открытый конструктор для тестов/входной
 * точки). Логика описана в [PreferencesAppSettingsCore] / [AppSettings];
 * сессионный слой потребляет её через [AppPreferencesStore] (SessionManager).
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
