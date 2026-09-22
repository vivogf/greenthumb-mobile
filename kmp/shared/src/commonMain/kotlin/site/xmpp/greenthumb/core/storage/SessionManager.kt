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
     * + офлайн-ветка Stage 3 п.5 (VAL-OFF-003): me → 401? recovery →
     * сетевой провал? офлайн → иной не-OK ничего. Итог — состояние для UI.
     */
    public suspend fun initSession(): SessionState {
        val me = try {
            api.me()
        } catch (e: ApiError) {
            when (e) {
                is ApiError.Unauthorized ->
                    // 401 наверх означает: клиентская попытка восстановления (шов
                    // M2) не состоялась или 401 сам от login-recovery — оба исхода
                    // решены в [recover]. Офлайн-режим явный 401 не включает.
                    return recover()
                is ApiError.Network, is ApiError.Timeout ->
                    // Офлайн-ветка Stage 3 п.5 (VAL-OFF-003): ключ + cached_user
                    // → офлайн; любой компонент отсутствует → экран входа.
                    return offlineOrNull() ?: SessionState.SignedOut
                // Транзиентный сбой 5xx/429 (parity: не «офлайн-причина» в RN):
                // ключ и cached_user хранятся, пользователя нет.
                else -> return SessionState.SignedOut
            }
        }
        return applyUser(me)
    }

    /**
     * Офлайн-сессия (Stage 3 п.5): сохранённый ключ + cached_user →
     * [SessionState.Offline] с последним известным пользователем; любой
     * компонент отсутствует → null (экран входа).
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
                // Явный 401 на вход: ключ невалиден (или стёрт) — чистим.
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
     * (VAL-LOGIN-004); транзиентные — ключ хранится.
     */
    public suspend fun onAuthError(error: ApiError) {
        if (error is ApiError.Unauthorized) {
            clearKeyAndCachedUser()
        }
    }

    /**
     * Выход (Stage 3 п.5, полная чистка c БД в Stage 4 п.5): серверный logout
     * best-effort + локально ключ, cached_user и handoff-файл; предпочтения
     * (язык/тема/сетка/интро) не тронуты. Обновляет [state].
     */
    public suspend fun signOut(): SessionState {
        try {
            api.logout()
        } catch (e: ApiError) {
            // logout best-effort (parity RN): локальная чистка не зависит от сети.
        }
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
     */
    private suspend fun recover(): SessionState {
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
            return SessionState.SignedOut
        }
        return applyUser(user)
    }

    /** Успешный вход: cached_user + ключ сервера (если отличается) → SignedIn. */
    private suspend fun applyUser(user: UserDto): SessionState {
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
