package site.xmpp.greenthumb.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import java.io.File
import site.xmpp.greenthumb.core.network.UserDto

/**
 * JVM-actual AppSettings для desktop-харнесса (architecture.md §6).
 * Файл — `~/.greenthumb/settings.preferences_pb` (Preferences 1.2.1,
 * расширение `.preferences_pb` обязательно — валидация
 * PreferenceDataStoreFactory; см. kmp-toolchain.md, дополнение m3).
 *
 * Тестам — JvmAppSettingsStorage с инжектируемым каталогом (public
 * constructor), runtime — синглтон-файл в каталоге харнесса.
 */
public actual class AppSettings {
    private val delegate = JvmAppSettingsStorage(defaultStorageDir())

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
 * Инжектируемая jvm-реализация (открытый конструктор для jvmTest).
 * Логика описана в [PreferencesAppSettingsCore] / [AppSettings].
 */
public class JvmAppSettingsStorage(
    storageDir: File,
) : PreferencesAppSettingsCore() {

    private val dir: File = storageDir.absoluteFile

    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(dir, DATA_FILE_NAME) },
        )

    private companion object {
        const val DATA_FILE_NAME = "settings.preferences_pb"
    }
}
