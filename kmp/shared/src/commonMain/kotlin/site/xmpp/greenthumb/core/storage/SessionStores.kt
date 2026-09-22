package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.flow.Flow
import site.xmpp.greenthumb.core.network.UserDto

/**
 * Сеам handoff-источника для [SessionManager] (Stage 3 п.4, фича
 * kmp-startup-session): чтение/удаление файла Stage 0 без платформенного
 * expect-класса.
 *
 * Реализован android-actual'ом [LegacyHandoff] (filesDir/gt-handoff.json —
 * файл, оставленный Expo-приложением) и jvm-actual'ом [LegacyHandoff]
 * (всегда null/no-op — desktop-харнесс Expo-сборкой не был). expect-класс
 * не может выступать супертипом интерфейса (actual с наследованием от
 * общего интерфейса несовместим с Beta expect/actual-классами), поэтому
 * сессия зависит от интерфейса, а подстановка actual'ов — в точке сборки
 * (SessionGraph / jvmTest-двойник).
 */
public interface HandoffSource {
    /** [HandoffPayload] или null = «handoff отсутствует» (нет файла/мусор). */
    public fun readHandoff(): HandoffPayload?

    /** Идempotentное удаление файла; вызывается ТОЛЬКО после проверенной записи ключа. */
    public fun clearHandoff()
}

/**
 * Интерфейс secure-хранилища для сессионного слоя (Stage 3 п.4). Реализован
 * jvm-делегатом [JvmSecureStoreStorage] и android-делегатом
 * [AndroidSecureStoreStorage]; контракт каждого метода — [SecureStore]
 * (get = null при провале чтения, set = Boolean-провал видим, remove бросает
 * IO-ошибку — чистку ключа нельзя проглотить молча).
 *
 * SessionManager зависит от интерфейса, а не от expect-класса: jvmTest
 * подставляет либо реальный jvm-делегат на temp-каталоге, либо фейк
 * (инжекция провала записи / подмены при перечитывании).
 */
public interface SecureKeyValueStore {
    public suspend fun get(key: String): String?

    /** @return true = запись надёжно сохранена; false = провал (виден вызывающему). */
    public suspend fun set(key: String, value: String): Boolean

    public suspend fun remove(key: String)
}

/**
 * Интерфейс настроек для сессионного слоя: применение значений handoff-файла
 * и кэш последнего пользователя (cached_user). Полный [AppSettings] API
 * (включая Flow-реактивность) реализует [PreferencesAppSettingsCore]; UI (Stage 6)
 * читает настройки из того же инстанса графа, чтобы не создавать второй
 * DataStore на тот же файл (один активный DataStore на файл на процесс —
 * kmp-toolchain.md, дополнение m3).
 */
public interface AppPreferencesStore {
    public suspend fun getLanguage(): AppLanguage?

    public suspend fun setLanguage(language: AppLanguage)

    public suspend fun getLayoutMode(): LayoutMode?

    public suspend fun setLayoutMode(mode: LayoutMode)

    public suspend fun getTheme(): ThemePreference?

    public suspend fun setTheme(theme: ThemePreference)

    public suspend fun isIntroSeen(): Boolean

    public suspend fun setIntroSeen()

    public suspend fun getCachedUser(): UserDto?

    public suspend fun setCachedUser(user: UserDto)

    public suspend fun clearCachedUser()

    public val language: Flow<AppLanguage?>
    public val layoutMode: Flow<LayoutMode?>
    public val theme: Flow<ThemePreference?>
    public val introSeen: Flow<Boolean>
    public val cachedUser: Flow<UserDto?>

    /**
     * Применение значений из handoff-файла Stage 0: перезаписывает только
     * переданные значения (null = «в RN не задано» → не трогать).
     */
    public suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    )
}
