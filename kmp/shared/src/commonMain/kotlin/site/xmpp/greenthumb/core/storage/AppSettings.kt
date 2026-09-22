package site.xmpp.greenthumb.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import site.xmpp.greenthumb.core.network.UserDto

/**
 * Кроссплатформенное несекретное хранилище настроек поверх
 * androidx.datastore-preferences 1.2.1 — architecture.md §6, VAL-STOR-002.
 *
 * Секретного здесь нет: recovery key живёт в [SecureStore]. Содержимое:
 * - язык интерфейса ([AppLanguage]) — рантайм-переключение в Stage 6;
 * - режим сетки дашборда ([LayoutMode]) — персист выбора из RN Phase 3;
 * - тема ([ThemePreference]) — light/dark/auto, Stage 6;
 * - флаг «карусель интро пройдена/пропущена» (RN setHasSeenIntro);
 * - `cached_user` — сериализованный [UserDto] для офлайн-сессии (Stage 3 п.5).
 *
 * Старый RN AsyncStorage напрямую НЕ читается (на Android это SQLite
 * `RKStorage`) — старые значения приезжают только через handoff-файл
 * Stage 0 ([applyLegacyHandoffValues] вызывается фичей kmp-legacy-handoff).
 *
 * Контракт:
 * - геттеры возвращают null при отсутствии ключа (дефолт решает UI-слой,
 *   как в RN: language = системный/en, theme = auto, layout = list);
 * - Flow — null при отсутствии; обновления без перезапуска (реактивность);
 * - [applyLegacyHandoffValues] — единственная запись, отличная от явных
 *   user-действий: перезаписывает только ПЕРЕДАННЫЕ значения (null = «в RN
 *   не задано» → не трогать); intro_seen=false существующий флаг не
 *   даунгрейдит (уже увиденное интро не переигрывается);
 * - [clearCachedUser] стирает только cached_user (signOut-семантика
 *   VAL-DATA-008: язык/тема/сетка/интро НЕ тронуты);
 * - порча файла → пустое хранилище (ReplaceFileCorruptionHandler), не крэш.
 *
 * DataStore: один активный инстанс на файл на процесс (семафора
 * datastore-core 1.2.1) — actual'ы создают один инстанс в объекте.
 *
 * Платформенные actual'ы:
 * - jvmMain: ~/.greenthumb/settings.preferences_pb (desktop-харнесс);
 * - androidMain: filesDir/greenthumb_settings.preferences_pb (domain "file",
 *   НЕ исключается из бэкапов — настройки несекретные и должны переживать
 *   восстановление; из бэкапа исключён только SecureStore-шифротекст).
 */
public expect class AppSettings {
    /** Язык интерфейса или null (не задан). */
    public suspend fun getLanguage(): AppLanguage?

    public suspend fun setLanguage(language: AppLanguage)

    /** Режим отображения дашборда или null (не задан; RN-дефолт list). */
    public suspend fun getLayoutMode(): LayoutMode?

    public suspend fun setLayoutMode(mode: LayoutMode)

    /** Выбор темы или null (не задан; RN-дефолт auto). */
    public suspend fun getTheme(): ThemePreference?

    public suspend fun setTheme(theme: ThemePreference)

    /** True, если карусель интро пройдена или пропущена. */
    public suspend fun isIntroSeen(): Boolean

    /** Идемпотентная фиксация флага интро (RN setHasSeenIntro). */
    public suspend fun setIntroSeen()

    /** Последний известный пользователь (офлайн-сессия) или null. */
    public suspend fun getCachedUser(): UserDto?

    public suspend fun setCachedUser(user: UserDto)

    /** Сброс cached_user (выход из аккаунта); предпочтения не тронуты. */
    public suspend fun clearCachedUser()

    /** Реактивное чтение (null = не задано) — без перезапуска процесса. */
    public val language: Flow<AppLanguage?>
    public val layoutMode: Flow<LayoutMode?>
    public val theme: Flow<ThemePreference?>
    public val introSeen: Flow<Boolean>
    public val cachedUser: Flow<UserDto?>

    /**
     * Применение значений из handoff-файла Stage 0 (kmp-legacy-handoff):
     * перезаписывает только переданные значения; null не трогает ничего.
     */
    public suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    )
}

/**
 * Имена ключей AppSettings (значения — из RN lib/constants.ts, переносятся
 * 1:1; это единая документированная конвенция имён — аналог lib/constants.ts).
 * Уникальность в рамках одного DataStore-файла обеспечена префиксом.
 */
public object AppSettingsKeys {
    /** Язык интерфейса (LANGUAGE_STORE_KEY в RN). */
    public const val LANGUAGE: String = "greenthumb_language"

    /** Режим отображения дашборда (LAYOUT_MODE_STORE_KEY в RN). */
    public const val LAYOUT_MODE: String = "greenthumb_layout_mode"

    /** Выбор темы (THEME_STORE_KEY в RN). */
    public const val THEME: String = "greenthumb_theme"

    /** Флаг «интро пройдено» (INTRO_SEEN_STORE_KEY в RN, значение "1"). */
    public const val INTRO_SEEN: String = "greenthumb_intro_seen"

    /** Кэш последнего известного пользователя (новый ключ, Stage 3 п.5). */
    public const val CACHED_USER: String = "cached_user"
}

/**
 * Язык интерфейса. Нейминг значений — язык провода handoff-схемы Stage 0
 * (lib/storage.ts) и подписки пушей (subscribe language = 'ru'/'en').
 */
public enum class AppLanguage(public val wire: String) {
    En("en"),
    Ru("ru");

    public companion object {
        /** Из строки провода; неизвестное/несуществующее значение — null. */
        public fun fromWire(value: String?): AppLanguage? =
            entries.firstOrNull { it.wire == value }
    }
}

/** Выбор темы; нейминг значений — RN THEME_STORE_KEY (light / dark / auto). */
public enum class ThemePreference(public val wire: String) {
    Auto("auto"),
    Light("light"),
    Dark("dark");

    public companion object {
        /** Из строки провода handoff/AsyncStorage; неизвестное — null. */
        public fun fromWire(value: String?): ThemePreference? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Режим отображения дашборда; нейминг значений — RN Phase 3
 * (LAYOUT_MODE_STORE_KEY: list / card / grid).
 */
public enum class LayoutMode(public val wire: String) {
    List("list"),
    Card("card"),
    Grid("grid");

    public companion object {
        /** Из строки провода handoff/AsyncStorage; неизвестное — null. */
        public fun fromWire(value: String?): LayoutMode? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * Платформенно-нейтральное ядро [AppSettings]: чтение/запись ключей,
 * конверсия wire-строк, cached_user-сериализация, handoff-применение.
 * Файл хранит только несекретные настройки — крипто-инвариантов нет
 * (в отличие от SecureStore, шифрующего значения).
 *
 * Actual'ы переопределяют только [createDataStore] (путь к файлу —
 * единственная платформенная разница):
 * - jvmMain: [JvmAppSettingsStorage];
 * - androidMain: [AndroidAppSettingsStorage].
 *
 * Public-класс с инжектируемой логикой — как [AndroidSecureStoreStorage]/
 * [JvmSecureStoreStorage]: платформенные тесты и входные точки создают
 * инстансы с нужным каталогом.
 */
public abstract class PreferencesAppSettingsCore {
    /** Scope хранилища (IO-диспетчер подставляет actual); cancel в [close]. */
    protected abstract val scope: CoroutineScope

    private val dataStore: DataStore<Preferences> by lazy { createDataStore() }
    private val writeMutex = Mutex()

    /**
     * DataStore-файл хранилища. Вызывается лениво, один раз на инстанс:
     * один активный DataStore на файл на процесс — фактическая
     * ответственность за уникальность пути на actual'е.
     */
    protected abstract fun createDataStore(): DataStore<Preferences>

    /** Освободить хранилище (тестам; DataStore-синглтон на файл снимается). */
    public fun close() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Геттеры / сеттеры
    // ------------------------------------------------------------------

    public suspend fun getLanguage(): AppLanguage? =
        AppLanguage.fromWire(readString(AppSettingsKeys.LANGUAGE))

    public suspend fun setLanguage(language: AppLanguage) {
        writeString(AppSettingsKeys.LANGUAGE, language.wire)
    }

    public suspend fun getLayoutMode(): LayoutMode? =
        LayoutMode.fromWire(readString(AppSettingsKeys.LAYOUT_MODE))

    public suspend fun setLayoutMode(mode: LayoutMode) {
        writeString(AppSettingsKeys.LAYOUT_MODE, mode.wire)
    }

    public suspend fun getTheme(): ThemePreference? =
        ThemePreference.fromWire(readString(AppSettingsKeys.THEME))

    public suspend fun setTheme(theme: ThemePreference) {
        writeString(AppSettingsKeys.THEME, theme.wire)
    }

    public suspend fun isIntroSeen(): Boolean =
        readString(AppSettingsKeys.INTRO_SEEN) == INTRO_SEEN_VALUE

    public suspend fun setIntroSeen() {
        writeString(AppSettingsKeys.INTRO_SEEN, INTRO_SEEN_VALUE)
    }

    public suspend fun getCachedUser(): UserDto? =
        readString(AppSettingsKeys.CACHED_USER)?.let { payload ->
            runCatching { settingsJson.decodeFromString(UserDto.serializer(), payload) }
                .getOrNull()
        }

    public suspend fun setCachedUser(user: UserDto) {
        writeString(AppSettingsKeys.CACHED_USER, settingsJson.encodeToString(UserDto.serializer(), user))
    }

    public suspend fun clearCachedUser() {
        dataStore.edit { it.remove(stringPreferencesKey(AppSettingsKeys.CACHED_USER)) }
    }

    // ------------------------------------------------------------------
    // Flow (реактивность — обновление значения без перезапуска процесса)
    // by lazy: property-инициализатор базового класса не должен форсировать
    // DataStore при конструировании подкласса (до присвоения его scope).
    // ------------------------------------------------------------------

    public val language: Flow<AppLanguage?> by lazy {
        dataStore.data.map { AppLanguage.fromWire(it[stringPreferencesKey(AppSettingsKeys.LANGUAGE)]) }
            .distinctUntilChanged()
    }

    public val layoutMode: Flow<LayoutMode?> by lazy {
        dataStore.data.map { LayoutMode.fromWire(it[stringPreferencesKey(AppSettingsKeys.LAYOUT_MODE)]) }
            .distinctUntilChanged()
    }

    public val theme: Flow<ThemePreference?> by lazy {
        dataStore.data.map { ThemePreference.fromWire(it[stringPreferencesKey(AppSettingsKeys.THEME)]) }
            .distinctUntilChanged()
    }

    public val introSeen: Flow<Boolean> by lazy {
        dataStore.data.map { it[stringPreferencesKey(AppSettingsKeys.INTRO_SEEN)] == INTRO_SEEN_VALUE }
            .distinctUntilChanged()
    }

    public val cachedUser: Flow<UserDto?> by lazy {
        dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(AppSettingsKeys.CACHED_USER)]?.let { payload ->
                runCatching { settingsJson.decodeFromString(UserDto.serializer(), payload) }
                    .getOrNull()
            }
        }.distinctUntilChanged()
    }

    // ------------------------------------------------------------------
    // Handoff-применение (вызывается kmp-legacy-handoff, VAL-HANDOFF-IMP-001)
    // ------------------------------------------------------------------

    public suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) {
        dataStore.edit { prefs ->
            language?.let { prefs[stringPreferencesKey(AppSettingsKeys.LANGUAGE)] = it.wire }
            theme?.let { prefs[stringPreferencesKey(AppSettingsKeys.THEME)] = it.wire }
            layoutMode?.let { prefs[stringPreferencesKey(AppSettingsKeys.LAYOUT_MODE)] = it.wire }
            // Уже увиденное интро не даунгрейдится (false в handoff не стирает).
            if (introSeen == true) {
                prefs[stringPreferencesKey(AppSettingsKeys.INTRO_SEEN)] = INTRO_SEEN_VALUE
            }
        }
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    private suspend fun readString(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    private suspend fun writeString(key: String, value: String) {
        writeMutex.withLock {
            dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value }
        }
    }

    private companion object {
        /** Значение флага интро — «1», как в RN setHasSeenIntro (handoff intro_seen=true). */
        const val INTRO_SEEN_VALUE = "1"
    }
}

/** JSON настроек: cached_user-сериализация + парсинг значений из handoff. */
internal val settingsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
