package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto

/**
 * Результат импорта handoff-файла Stage 0 ([SessionManager.importHandoff]).
 */
public sealed class SessionResult {
    /** Ключ записан, перечитан и совпал; настройки применены; файл удалён. */
    public data object Imported : SessionResult()

    /** Handoff отсутствует или нечитаем (VAL-HANDOFF-IMP-003/004): не ошибка. */
    public data object NoHandoff : SessionResult()

    /**
     * Провал записи ключа/настроек (или перечитывание не совпало):
     * handoff-файл ОСТАЛСЯ на диске — единственная копия ключа не потеряна
     * (VAL-HANDOFF-IMP-005); [recoveryKey] — для экрана ошибки с копированием.
     */
    public data class Failed(public val recoveryKey: String) : SessionResult()
}

/**
 * Состояние сессии — результат стартовой последовательности ([SessionManager.startup])
 * и входов; UI-слой Stage 6 мапит на экраны (список/логин/ошибка handoff).
 */
public sealed class SessionState {
    /** Вход выполнен; [user] — последний известный пользователь (me/recovery/вход). */
    public data class SignedIn(public val user: UserDto) : SessionState()

    /**
     * Офлайн-режим (Stage 3 п.5): сетевой провал me при сохранённом ключе и
     * последнем известном пользователе; [user] — сериализованный cached_user.
     * Пользователь продолжает работу: данные из Room и полоса «нет сети» —
     * M4 (kmp-connectivity/репозиторий), здесь только факт режима.
     */
    public data class Offline(public val user: UserDto) : SessionState()

    /** Экран входа (нет ключа/сессии или явный 401 при восстановлении). */
    public data object SignedOut : SessionState()

    /**
     * Провал импорта handoff: файл остался, пользователю показывается экран
     * ошибки с предложением СКОПИРОВАТЬ ключ (VAL-HANDOFF-IMP-005).
     */
    public data class HandoffImportFailed(public val recoveryKey: String) : SessionState()

    /**
     * Экран «ключ не найден» (Stage 3 п.6, VAL-HANDOFF-IMP-002): ключа нет
     * НИГДЕ (SecureStore + handoff), а установка — обновление поверх предыдущей
     * (androidMain: lastUpdateTime ≠ firstInstallTime). Ряды матрицы «Expo-без-
     * handoff → KMP» и «handoff доставлен, но приложение не запускалось → KMP»:
     * аккаунт пользователя жив на сервере, ключ нужно ввести руками (кнопка
     * ввода), восстановить ключ невозможно — предлагается создать новый аккаунт
     * (вторая кнопка). НЕ показывается на чистой установке (обычный логин) и
     * после обычного выхода/сброса ключа пользователем ([SessionState.SignedOut]).
     */
    public data object KeyNotFound : SessionState()
}

/**
 * Единая стартовая последовательность сессии (Stage 3 п.4; architecture.md §6,
 * порт RN `contexts/AuthContext.tsx:53-91` + `lib/storage.ts`):
 *
 * ```
 * startup():
 *   1. key = SecureStore.get(RECOVERY_KEY)
 *   2. key == null → handoff = readHandoff()
 *      - handoff == null → NoHandoff (обычный initSession)
 *      - мусор в файле   → NoHandoff, файл НЕ тронут (VAL-HANDOFF-IMP-004)
 *      - иначе импорт:
 *          a) SecureStore.set(key) — провал → Failed, файл остаётся
 *          b) settings.applyLegacyHandoffValues — провал → Failed, файл остаётся
 *          c) SecureStore.get ПЕРЕЧИТАТЬ и сверить — не совпало → Failed
 *          d) только теперь clearHandoff() (VAL-HANDOFF-IMP-001/005)
 *      - импорт стёр stale cached_user (офлайн-кэш прошлого аккаунта не всплывает)
 *   3. импорт провалился → HandoffImportFailed(ключ) — экран ошибки с копированием
 *   4. иначе initSession (3 шага ниже)
 *
 * initSession():
 *   1. GET /api/auth/me → 200: вход (cached_user обновлён)
 *   2. 401 → recovery: POST /api/auth/login-recovery ключом
 *      - 200 → вход (cached_user обновлён; ключ сервера сохранён, если пришёл)
 *      - 401 → ЯВНЫЙ 401: ключ + cached_user чистятся (VAL-LOGIN-004)
 *      - 429/5xx/сеть/таймаут → ключ ХРАНИТСЯ, пользователя нет
 *   3. иной не-OK / транспортный провал → ничего: ключ хранится, экран входа
 *      (401 от me на raw-уровне уже пытался восстановиться сам — шов M2; здесь
 *      только классификация результата, повторной попытки нет)
 *   4. сетевой провал me ([ApiError.Network]/[ApiError.Timeout]) + ключ в
 *      SecureStore + cached_user → [SessionState.Offline] (Stage 3 п.5,
 *      VAL-OFF-003): пользователь в офлайн-режиме с последним известным
 *      аккаунтом. Полный офлайн-флоу (данные из Room, очередь мутаций,
 *      полоса «нет сети») доезжает в M4 — здесь только факт режима.
 *   5. пустой исход (нет ключа НИГДЕ после шагов 1-2) при установке-обновлении
 *      (androidMain lastUpdateTime ≠ firstInstallTime) → [SessionState.KeyNotFound]
 *      — экран «ключ не найден» (Stage 3 п.6, VAL-HANDOFF-IMP-002): ряды матрицы
 *      «Expo-без-handoff → KMP» и «handoff доставлен, но не запускался → KMP».
 *      Только холодный старт; обычный вход/выход/транзиентные сбои дают
 *      [SessionState.SignedOut] (сравнение с EmptyScreen-логикой RN: у RN
 *      отсутствует — осознанное улучшение architecture.md §6).
 *
 * Дисциплина чистки ключа (parity + Stage 0): ключ стирается ТОЛЬКО при явном
 * 401 ([onAuthError], signOut, 401 от login-recovery) и всегда вместе с
 * cached_user + handoff-файлом; транзиентные сбои ключ хранят.
 *
 * signOut(): server logout best-effort + ключ + cached_user + handoff-файл;
 * предпочтения (язык/тема/сетка/интро) не тронуты.
 *
 * cached_user — сериализованный UserDto после каждого успешного me/логина
 * (офлайн-сессия Stage 3 п.5 читает его в M4).
 *
 * Все вызовы — из корутины (DataStore/сеть suspend); конкурентные мутации
 * ключа/настроек сериализованы mutex'ом.
 */
public class SessionManager(
    secure: SecureKeyValueStore,
    settings: AppPreferencesStore,
    /**
     * Handoff-источник (android-actual LegacyHandoff, jvm no-op). Public
     * (read-only) для проверки clearHandoff-счётчика в jvmTest; поведение
     * всегда через методы менеджера.
     */
    public val handoff: HandoffSource,
    api: GreenThumbApi,
    /**
     * Факт «установка — обновление поверх предыдущей» (Stage 3 п.6,
     * androidMain: lastUpdateTime ≠ firstInstallTime). Пустой исход
     * initSession (нет ключа ни в SecureStore, ни в handoff) при этом
     * факте → [SessionState.KeyNotFound] (VAL-HANDOFF-IMP-002), иначе
     * обычный [SessionState.SignedOut]. На desktop-харнессе фактический
     * jvm-actual всегда false; jvmTest инжектирует факт явно.
     */
    private val isUpdateInstall: Boolean = false,
) {
    private val secure = secure
    private val settings = settings
    private val api = api

    /** Сериализация мутаций ключа/настроек (импорт, сбросы, выходы). */
    private val sessionMutex = Mutex()

    /**
     * Реактивное состояние сессии для UI (Stage 6 подменяет экраны):
     * null = startup ещё крутится. Обновляется из suspend-методов менеджера.
     */
    private val mutableState = MutableStateFlow<SessionState?>(null)

    public val state: StateFlow<SessionState?> = mutableState

    /**
     * Повторный прогон стартовой последовательности (кнопка «повторить» в
     * dev-поверхности; Stage 6 заменит своими триггерами): крутит [startup]
     * заново в общем scope (кнопка вне suspend-контекста).
     */
    public fun retryStartup() {
        startupScope.launch { startup() }
    }

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------
    // Стартовая последовательность
    // ------------------------------------------------------------------

    /**
     * Полный старт приложения (импорт handoff, если ключа нет, + initSession).
     * Вызывать один раз при запуске (Stage 6: корутина на IO/Default);
     * обновляет [state].
     */
    public suspend fun startup(): SessionState {
        mutableState.value = null
        val state = runStartup()
        mutableState.value = state
        return state
    }

    private suspend fun runStartup(): SessionState {
        when (val result = importHandoff()) {
            is SessionResult.Failed -> return SessionState.HandoffImportFailed(result.recoveryKey)
            else -> Unit
        }
        return initSession()
    }

    /**
     * Шаги 1-2 последовательности: ключ из SecureStore; пусто → readHandoff →
     * запись ключа + настроек → ПЕРЕЧИТАТЬ и сверить ключ → только тогда
     * clearHandoff. Провал любого шага записи → файл остаётся (Failed).
     * Явно вызывается только тестами; приложение идёт через [startup].
     */
    public suspend fun importHandoff(): SessionResult {
        val storedKey = secure.get(SecureStoreKeys.RECOVERY_KEY)
        if (storedKey != null) return SessionResult.NoHandoff

        val payload = handoff.readHandoff() ?: return SessionResult.NoHandoff
        // Мусорный документ читается как «отсутствует» (parse → null) и остаётся
        // на диске: единственная копия ключа не удаляется (VAL-HANDOFF-IMP-004).

        return sessionMutex.withLock {
            // 1) ключ (провал set виден — не молчание)
            if (!secure.set(SecureStoreKeys.RECOVERY_KEY, payload.recoveryKey)) {
                return@withLock SessionResult.Failed(payload.recoveryKey)
            }
            // 2) настройки
            try {
                settings.applyLegacyHandoffValues(
                    payload.language,
                    payload.theme,
                    payload.layoutMode,
                    payload.introSeen.takeIf { it },
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return@withLock SessionResult.Failed(payload.recoveryKey)
            }
            // 3) перечитать и сверить ключ — до clearHandoff
            val reRead = secure.get(SecureStoreKeys.RECOVERY_KEY)
            if (reRead != payload.recoveryKey) {
                return@withLock SessionResult.Failed(payload.recoveryKey)
            }
            // 4) проверенная запись → файл можно удалять
            handoff.clearHandoff()
            // stale cached_user прошлого аккаунта не переживает импорт
            settings.clearCachedUser()
            SessionResult.Imported
        }
    }

    /**
     * Три шага RN initSession (`contexts/AuthContext.tsx:53-91`)
     * + офлайн-ветка Stage 3 п.5 (VAL-OFF-003) + экран «ключ не найден»
     * Stage 3 п.6 (VAL-HANDOFF-IMP-002): me → 401? recovery → сетевой
     * провал? офлайн → иной не-OK / пусто: экран входа, при пустом исходе
     * на установке-обновлении — [SessionState.KeyNotFound]. Итог — состояние
     * для UI.
     */
    public suspend fun initSession(): SessionState {
        val me = try {
            api.me()
        } catch (e: ApiError) {
            when (e) {
                is ApiError.Unauthorized -> {
                    // 401 от me: recover() сохранённым ключом. Результат
                    // recover с ПУСТЫМИ хранилищами (ключа нет нигде — шов
                    // клиента уже сбросил сессию или ключа не было) — пустой
                    // исход п.6 на установке-обновлении → «ключ не найден»:
                    // аккаунт жив на сервере (мне сервер ответил 401, т.е.
                    // запрос дошёл), ключа нет нигде — эквивалент ряда
                    // «handoff доставлен, но не запускался». Пустота после
                    // ДЕЙСТВИЯ пользователя (401 на входе, выход) отсекается
                    // [hadSession].
                    val recovered = recover()
                    return if (recovered is SessionState.SignedOut) signedOutOrKeyNotFound() else recovered
                }
                is ApiError.Network, is ApiError.Timeout ->
                    // Офлайн-ветка Stage 3 п.5 (VAL-OFF-003): ключ + cached_user
                    // → офлайн; любой компонент отсутствует → экран входа
                    // (или «ключ не найден» на установке-обновлении — Stage 3 п.6).
                    return offlineOrNull() ?: signedOutOrKeyNotFound()
                // Транзиентный сбой 5xx/429 (parity: не «офлайн-причина» в RN):
                // ключ и cached_user хранятся, пользователя нет.
                // И «не-OK без 401» при пустых хранилищах — стартовая пустота
                // п.6: сервер ответил, но ни ключа, ни handoff нет → «ключ
                // не найден» на обновлении (эквивалент ряда «handoff доставлен,
                // но не запускался»: сервер жив, ключа нигде нет).
                else -> return signedOutOrKeyNotFound()
            }
        }
        return applyUser(me)
    }

    /**
     * Пустой исход старта (Stage 3 п.6): нет ключа НИГДЕ (SecureStore пуст,
     * handoff отсутствует/мусор) + установка — обновление поверх предыдущей →
     * [SessionState.KeyNotFound] — экран «ключ не найден» с объяснением
     * (аккаунт на сервере; ключ ввести руками; восстановить нельзя → создать
     * новый) вместо пустого логина (VAL-HANDOFF-IMP-002). Чистая установка
     * или любой исход после действия пользователя (выход, 401 на вход) →
     * обычный [SessionState.SignedOut].
     *
     * «Только холодный старт» — [SessionState.SignedOut] из обычного выхода
     * ([signOut]) и явного 401 на входе ([signInWithRecoveryKey]) в этот
     * хелпер не попадает: они возвращают SignedOut напрямую.
     *
     * Пустота проверяется заново перед KeyNotFound (не только флаг конструктора):
     * между конструированием и стартом импорт handoff мог записать ключ; если
     * ключ появился (в [SecureStoreKeys.RECOVERY_KEY] или handoff-файле) —
     * обычный экран входа, «не найден» ложью быть не может.
     *
     * Плюс признак «сессия уже жила в этом процессе» ([hadSession]): исходы
     * после успешного входа, выхода, 401 на входе и runtime-401 при живом
     * ключе — не холодный старт, экран «ключ не найден» больше не показывается
     * (пустота в этом случае создана действием пользователя, а не обновлением).
     */
    private suspend fun signedOutOrKeyNotFound(): SessionState {
        if (!isUpdateInstall) return SessionState.SignedOut
        if (hadSession) return SessionState.SignedOut
        val stored = secure.get(SecureStoreKeys.RECOVERY_KEY)
        if (stored != null) return SessionState.SignedOut
        if (handoff.readHandoff() != null) return SessionState.SignedOut
        return SessionState.KeyNotFound
    }

    /**
     * В этом процессе уже был успешный вход ([applyUser]) или явная
     * неавторизация по действию пользователя (401 на входе / выход /
     * runtime-401 при живом ключе) — исход после такого шага уже не «холодный
     * старт», и экран «ключ не найден» больше не показывается.
     */
    private var hadSession: Boolean = false

    /**
     * Офлайн-сессия (Stage 3 п.5): сохранённый ключ + cached_user →
     * [SessionState.Offline] с последним известным пользователем; любой
     * компонент отсутствует → null (пустой исход решает вызывающий:
     * [signedOutOrKeyNotFound] — экран входа или «ключ не найден»).
     */
    private suspend fun offlineOrNull(): SessionState.Offline? {
        val key = secure.get(SecureStoreKeys.RECOVERY_KEY) ?: return null
        val user = settings.getCachedUser() ?: return null
        return SessionState.Offline(user)
    }

    // ------------------------------------------------------------------
    // Входы (пишут ключ сервера + cached_user)
    // ------------------------------------------------------------------

    /** Вход recovery key (логин-экран, VAL-LOGIN-004). Бросает [ApiError]. */
    public suspend fun signInWithRecoveryKey(recoveryKey: String): SessionState {
        try {
            return applyUser(api.loginRecovery(recoveryKey))
        } catch (e: ApiError) {
            if (e is ApiError.Unauthorized) {
                // Явный 401 на вход: ключ невалиден (или стёрт) — чистим
                // (VAL-LOGIN-004). Экран входа после этого — обычный
                // [SessionState.SignedOut]: ключ пользователь ввёл сам, «ключ
                // не найден» здесь не показывается (Stage 3 п.6).
                // Признак «в этом процессе уже был успешный вход» (см.
                // [signedOutOrKeyNotFound]): пустота после такого шага —
                // не стартовая.
                hadSession = true
                clearKeyAndCachedUser()
            }
            throw e
        }
    }

    /** Создание анонимного аккаунта (логин-экран, режим create). Бросает [ApiError]. */
    public suspend fun createAnonymousAccount(): SessionState = applyUser(api.createAnonymous())

    // ------------------------------------------------------------------
    // Рантайм
    // ------------------------------------------------------------------

    /**
     * Восстановление сессии сохранённым ключом (шов M2; вызывается
     * ApiClient-провайдером и тестами). Пишет cached_user; Бросает [ApiError].
     */
    public suspend fun loginRecovery(recoveryKey: String): UserDto {
        val state = applyUser(api.loginRecovery(recoveryKey))
        if (state !is SessionState.SignedIn) {
            throw AssertionError("applyUser обязан вернуть SignedIn")
        }
        return state.user
    }

    /**
     * Рантайм-шов для ApiClient (Stage 2 п.7): исход сетевого вызова наверх.
     * Явный 401 (ключ не принял сервер) → ключ + cached_user чистятся
     * (VAL-LOGIN-004); транзиентные — ключ хранятся. Явный 401 при живом
     * ключе — тоже признак «сессия жила» (см. [signedOutOrKeyNotFound]).
     */
    public suspend fun onAuthError(error: ApiError) {
        if (error is ApiError.Unauthorized) {
            val hadKey = secure.get(SecureStoreKeys.RECOVERY_KEY) != null
            if (hadKey) hadSession = true
            clearKeyAndCachedUser()
        }
    }

    /**
     * Выход (Stage 3 п.5, полная чистка c БД в Stage 4 п.5): серверный logout
     * best-effort + локально ключ, cached_user и handoff-файл; предпочтения
     * (язык/тема/сетка/интро) не тронуты. Обновляет [state]. Итог — обычный
     * [SessionState.SignedOut] (экран входа): пользователь сам вышел, экран
     * «ключ не найден» [SessionState.KeyNotFound] тут не показывается
     * (Stage 3 п.6 — только холодный старт без ключа).
     */
    public suspend fun signOut(): SessionState {
        try {
            api.logout()
        } catch (e: ApiError) {
            // logout best-effort (parity RN): локальная чистка не зависит от сети.
        }
        // Выход — действие пользователя: исход после него уже не «холодный
        // старт», «ключ не найден» больше не показывается (см. hadSession).
        hadSession = true
        sessionMutex.withLock {
            secure.remove(SecureStoreKeys.RECOVERY_KEY)
            settings.clearCachedUser()
        }
        handoff.clearHandoff()
        mutableState.value = SessionState.SignedOut
        return SessionState.SignedOut
    }

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /**
     * 401 от me → одна попытка recovery (шаг 2 initSession). Сетевой провал
     * самой recovery (сеть/таймаут) при ключе и cached_user тоже уходит в
     * офлайн-режим (Stage 3 п.5: сессия с сервера не ожила, но пользователь
     * есть локально); 5xx/429 recovery — обычный SignedOut.
     *
     * [SessionState.KeyNotFound] здесь НЕ показывается (см. [hadSession]:
     * пустота после 401-шага — не стартовая). Исход пустоты [initSession] при
     * отсутствии 401-шага (сервер недостижим/транзиент/не-OK) мапится через
     * [signedOutOrKeyNotFound].
     */
    private suspend fun recover(): SessionState {
        // НЕ KeyNotFound: пустота здесь — не «ключ не найден» п.6. 401-шаг
        // всегда означает, что сессия уже жила (или ключ стёрт самим
        // пользователем на входе/выходе); экран «ключ не найден» — только
        // исход стартовой пустоты (см. [initSession]).
        val key = secure.get(SecureStoreKeys.RECOVERY_KEY) ?: return SessionState.SignedOut
        val user = try {
            api.loginRecovery(key)
        } catch (e: ApiError) {
            when (e) {
                is ApiError.Unauthorized ->
                    // Явный 401 от recovery: ключ невалиден — чистим (VAL-LOGIN-004).
                    clearKeyAndCachedUser()
                is ApiError.Network, is ApiError.Timeout ->
                    // Провал сети при recovery: офлайн-ветка Stage 3 п.5
                    // (сессия не ожила, но пользователь есть локально).
                    return offlineOrNull() ?: SessionState.SignedOut
                // Транзиентные 429/5xx recovery ключ ХРАНЯТ (VAL-LOGIN-004).
                else -> Unit
            }
            // Ключ был и жив после транзиентного сбоя — это не «ключ не найден»:
            // обычный экран входа (Stage 3 п.6 — только пустота «нигде»).
            return SessionState.SignedOut
        }
        return applyUser(user)
    }

    /** Успешный вход: cached_user + ключ сервера (если отличается) → SignedIn. */
    private suspend fun applyUser(user: UserDto): SessionState {
        // Признак «в этом процессе уже был успешный вход» (см.
        // [signedOutOrKeyNotFound]): пустота после такого шага — не стартовая.
        hadSession = true
        settings.setCachedUser(user)
        if (user.recoveryKey.isNotEmpty() && user.recoveryKey != secure.get(SecureStoreKeys.RECOVERY_KEY)) {
            // Провал записи здесь не фатален (не handoff-импорт): ключ останется
            // в сессии (cookie в памяти клиента) и придёт с следующим me.
            secure.set(SecureStoreKeys.RECOVERY_KEY, user.recoveryKey)
        }
        return SessionState.SignedIn(user)
    }

    /** Единая чистка: только явный 401 (VAL-LOGIN-004). */
    private suspend fun clearKeyAndCachedUser() {
        sessionMutex.withLock {
            secure.remove(SecureStoreKeys.RECOVERY_KEY)
            settings.clearCachedUser()
        }
    }
}
