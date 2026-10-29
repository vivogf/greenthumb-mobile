package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.ui.screens.login.canonicalRecoveryKey

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

    /**
     * Sign-in did not finish (network, timeout, 429/5xx) while a recovery key is on this phone.
     * The start screen offers to try again with that key instead of a bare login screen.
     */
    public data object RetryNeeded : SessionState()
}

/**
 * The recovery key of the current account as this process knows it ([SessionManager.recoveryKey]).
 * Screens read the key here, so a rotated key shows at once and a key the phone failed to store
 * is still shown.
 */
public sealed interface CurrentRecoveryKey {
    /** Nothing has read secure storage yet ([SessionManager.loadRecoveryKey] fills it). */
    public data object Unknown : CurrentRecoveryKey

    /** No key: secure storage is empty and the server sent none. */
    public data object Missing : CurrentRecoveryKey

    /**
     * [savedOnDevice] false: secure storage refused the write, so this process holds the only
     * copy. The user must save the key by hand before the app is closed.
     */
    public class Present(
        public val key: String,
        public val savedOnDevice: Boolean,
        /** False: a new key the server has not confirmed (its pending write failed); not offered to a password manager. */
        public val confirmed: Boolean = true,
    ) : CurrentRecoveryKey {
        override fun toString(): String = "Present(key=<redacted>, savedOnDevice=$savedOnDevice, confirmed=$confirmed)"
    }
}

/**
 * Outcome of a two-phase key change ([SessionManager.regenerateRecoveryKey],
 * [SessionManager.finishKeyChange]).
 */
public sealed interface KeyRotation {
    /** The server confirmed the new key; it is the current key now. */
    public class Done(public val user: UserDto) : KeyRotation

    /**
     * The phone could not store the new key, so nothing was confirmed and the old key still works.
     * [SessionManager.recoveryKey] shows the new key with savedOnDevice false.
     */
    public data object NotStored : KeyRotation

    /**
     * The confirm did not finish (no answer, 404, 5xx). Both keys stay on the phone; the app
     * tries again after the next sign-in ([SessionManager.keyChangePending] stays true).
     */
    public data object Unfinished : KeyRotation

    /** The server no longer holds this pending key (409): it was dropped, the old key stays. */
    public data object Superseded : KeyRotation

    /** [SessionManager.finishKeyChange] found no pending key. */
    public data object NothingPending : KeyRotation

    /** The session ended (sign-out, account deletion, 401) while the change ran: nothing written. */
    public data object Abandoned : KeyRotation
}

/** What Profile must do before a sign-out ([SessionManager.prepareSignOut]). */
public sealed interface SignOutCheck {
    /** No key change is pending: sign out. */
    public data object Clear : SignOutCheck

    /** The pending change finished now: show the new key and do not sign out. */
    public class Finished(public val user: UserDto) : SignOutCheck

    /**
     * The change is still not finished: the user must save [key] (the confirmed key) and, when
     * set, [newKey] (the new key, not yet proven by the server) before signing out.
     */
    public class SaveKeyFirst(public val key: String, public val newKey: String? = null) : SignOutCheck {
        override fun toString(): String = "SaveKeyFirst(key=<redacted>, newKey=${if (newKey == null) "null" else "<redacted>"})"
    }

    /**
     * A key change in flight lost its answer (network, timeout): on a HEAD server the key may
     * already have changed. Profile warns and signs out only on an explicit "Sign out anyway".
     */
    public data object Uncertain : SignOutCheck
}

/**
 * create-anonymous answered without a recovery key. Nobody could ever sign in to that account
 * again, so the app does not open it.
 */
public class MissingRecoveryKeyException(endpoint: String = "create-anonymous") :
    IllegalStateException("$endpoint returned no recovery key")

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
 *      - 200 → вход (cached_user обновлён; хранится ключ, которым вошли)
 *      - 401 → ЯВНЫЙ 401: ключ + cached_user чистятся (VAL-LOGIN-004)
 *      - 429/5xx/сеть/таймаут → ключ ХРАНИТСЯ, [SessionState.RetryNeeded]
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
 * signOut(): server logout best-effort + ключ + cached_user + handoff-файл
 * + база пользователя (WAL/SHM) через [deleteUserDatabase]; cookies сбрасывает
 * [onSessionEnded]. Предпочтения (язык/тема/сетка/интро) не тронуты.
 *
 * deleteAccount(): та же матрица чистки БЕЗ logout — после подтверждённого
 * сервером DELETE /api/auth/account (Stage 12 п.4, удаление аккаунта
 * в приложении; сессию уничтожает сервер). Провал запроса локальное
 * состояние не трогает.
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
    /**
     * Смена сессии (Stage 4 п.5): отмена запросов, новый session-id, сброс
     * cookies. Колбэк не должен бросать [kotlinx.coroutines.CancellationException]
     * кроме настоящей отмены вызывающего.
     */
    private val onSessionEnded: suspend () -> Unit = {},
    /**
     * Стирает `plants_${userId}.db` вместе с WAL/SHM (VAL-DATA-008).
     * Вызывающий закрывает открытое соединение до удаления файла.
     * Пустой колбэк — тесты сессии без Room.
     */
    private val deleteUserDatabase: suspend (userId: String) -> Unit = {},
) {
    private val secure = secure
    private val settings = settings
    private val api = api

    /** Сериализация мутаций ключа/настроек (импорт, сбросы, выходы). */
    private val sessionMutex = Mutex()

    /**
     * Пользователь, для которого уже открыта сессия в этом процессе.
     * Не [state]: вход с экрана логина пишет пользователя в хранилище раньше,
     * чем UI успевает прочитать [state]. Нужен, чтобы смена аккаунта сбросила
     * cookies до запроса нового входа.
     */
    private var activeUserId: String? = null

    /**
     * Реактивное состояние сессии для UI (Stage 6 подменяет экраны):
     * null = startup ещё крутится. Обновляется из suspend-методов менеджера.
     */
    private val mutableState = MutableStateFlow<SessionState?>(null)

    public val state: StateFlow<SessionState?> = mutableState

    private val mutableRecoveryKey = MutableStateFlow<CurrentRecoveryKey>(CurrentRecoveryKey.Unknown)

    /**
     * The current account's recovery key. Set by every sign-in and key change before [state]
     * publishes the user, so screens never pair a new user with an old key.
     */
    public val recoveryKey: StateFlow<CurrentRecoveryKey> = mutableRecoveryKey

    private val mutableKeyNotStoredNotice = MutableStateFlow(false)

    /**
     * True after a sign-in whose key the phone could not store: the app warns the user at once.
     * [dismissKeyNotStoredNotice] clears it.
     */
    public val keyNotStoredNotice: StateFlow<Boolean> = mutableKeyNotStoredNotice

    public fun dismissKeyNotStoredNotice() {
        mutableKeyNotStoredNotice.value = false
    }

    private val mutableKeyChangeFinishedNotice = MutableStateFlow(false)

    /** True after a background sign-in finished a key change the user did not just tap. */
    public val keyChangeFinishedNotice: StateFlow<Boolean> = mutableKeyChangeFinishedNotice

    public fun dismissKeyChangeFinishedNotice() {
        mutableKeyChangeFinishedNotice.value = false
    }

    private val mutableKeyChangeFailedNotice = MutableStateFlow(false)

    /** True after the server proved a new key dead (409, or 401 on the probe sign-in) without a tap. */
    public val keyChangeFailedNotice: StateFlow<Boolean> = mutableKeyChangeFailedNotice

    public fun dismissKeyChangeFailedNotice() {
        mutableKeyChangeFailedNotice.value = false
    }

    private val mutableUnconfirmedRecoveryKey = MutableStateFlow<String?>(null)

    /**
     * The new key of an unfinished change whose fate the server has not proven. Screens show it
     * next to [recoveryKey]: it becomes the key when the change finishes; until then the old key
     * also works, so the user saves both.
     */
    public val unconfirmedRecoveryKey: StateFlow<String?> = mutableUnconfirmedRecoveryKey

    /**
     * Before a sign-out from Profile. Waits for a key change in flight, then tries to finish a
     * pending one: [SignOutCheck.Finished] means the new key is in place (show it, do not sign
     * out); [SignOutCheck.SaveKeyFirst] means sign-out would erase a key the user may need, so
     * Profile asks to save it and signs out only on an explicit "Sign out anyway". It does not
     * hold any lock across the network.
     */
    public suspend fun prepareSignOut(): SignOutCheck {
        val inFlight = keyChangeInFlight?.takeIf { it.isActive }
        if (inFlight != null) {
            val flown = try {
                inFlight.await()
            } catch (e: ApiError) {
                // A lost regenerate answer: on HEAD the key may already have changed (R2-5).
                if (e is ApiError.Network || e is ApiError.Timeout) return SignOutCheck.Uncertain
                null
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
            if (flown is KeyRotation.Done) return SignOutCheck.Finished(flown.user)
        }
        if (regenerateAnswerLost) return SignOutCheck.Uncertain
        val pending = secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING)
        if (pending == null && unstoredPendingKey == null) return SignOutCheck.Clear
        if (pending != null) {
            val finished = runCatching { runKeyChange(regenerate = false, byUser = true) }.getOrNull()
            if (finished is KeyRotation.Done) return SignOutCheck.Finished(finished.user)
        }
        val current = secure.get(SecureStoreKeys.RECOVERY_KEY)
            ?: (mutableRecoveryKey.value as? CurrentRecoveryKey.Present)?.key
        val newKey = (secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING) ?: unstoredPendingKey)?.takeIf { it != current }
        if (current == null) return newKey?.let { SignOutCheck.SaveKeyFirst(it) } ?: SignOutCheck.Clear
        return SignOutCheck.SaveKeyFirst(current, newKey)
    }

    private val mutableKeyChangePending = MutableStateFlow(false)

    /**
     * True while a new key waits in [SecureStoreKeys.RECOVERY_KEY_PENDING] for the server's
     * confirm. Profile then shows "key change not finished" with a retry ([finishKeyChange]).
     */
    public val keyChangePending: StateFlow<Boolean> = mutableKeyChangePending

    /**
     * A new key the phone could not write to the pending slot. Held only in memory: it is shown
     * to the user ([KeyRotation.NotStored]) and tried last at sign-in, but never written over
     * [SecureStoreKeys.RECOVERY_KEY] by a later user update.
     */
    private var unstoredPendingKey: String? = null

    /**
     * A proven key (promoted) whose RECOVERY_KEY write failed: it lives only in the pending slot,
     * and the screens keep showing it instead of the dead old key in RECOVERY_KEY.
     */
    private var shownPendingKey: String? = null

    /**
     * A regenerate request lost its answer (network, timeout). On HEAD the key may already have
     * changed to a key nobody saw, so a plain sign-out is blocked ([SignOutCheck.Uncertain]) until
     * a successful regenerate, sign-in or confirm proves the key state again. In memory only: a
     * cold start runs [recover], which settles it.
     */
    private var regenerateAnswerLost = false

    /**
     * Bumped by [signOut], [deleteAccount] and [onAuthError]. A key change that started in an
     * earlier generation drops its work and writes nothing.
     */
    private val sessionGeneration = MutableStateFlow(0L)

    private fun endSessionGeneration() {
        sessionGeneration.update { it + 1 }
    }

    /** Fills [recoveryKey] from secure storage when nothing has set it yet (e.g. an offline start). */
    public suspend fun loadRecoveryKey() {
        if (mutableRecoveryKey.value != CurrentRecoveryKey.Unknown) return
        val stored = secure.get(SecureStoreKeys.RECOVERY_KEY)
        val loaded = stored?.let { CurrentRecoveryKey.Present(it, savedOnDevice = true) } ?: CurrentRecoveryKey.Missing
        // A sign-in that finished meanwhile wins over this read.
        mutableRecoveryKey.compareAndSet(CurrentRecoveryKey.Unknown, loaded)
    }

    /**
     * The key for a silent re-login, memory first: when the phone could not store a new key,
     * storage still holds the old one, which the server no longer accepts.
     */
    public suspend fun sessionRecoveryKey(): String? = sessionRecoveryKeys().firstOrNull()

    /**
     * Every key this phone holds for the account, in sign-in order: a key this process could not
     * store (create or sign-in), the confirmed key, the stored pending key, then an unstored
     * pending key. Sign-in moves to the next key only on a 401.
     */
    public suspend fun sessionRecoveryKeys(): List<String> {
        val unstoredPending = unstoredPendingKey
        val memory = (mutableRecoveryKey.value as? CurrentRecoveryKey.Present)
            ?.takeIf { !it.savedOnDevice && it.key != unstoredPending }
            ?.key
        return listOfNotNull(
            memory,
            secure.get(SecureStoreKeys.RECOVERY_KEY),
            secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING),
            unstoredPending,
        ).distinct()
    }

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
     * Единственный прогон стартовой последовательности на процесс
     * (architecture.md §9): Android пересоздаёт Activity при повороте и
     * смене масштаба шрифта, и новый состав UI запускает свой
     * `LaunchedEffect` поверх того же [SessionManager]. Повторный [startup]
     * сбрасывал бы [state] в null и крутил последовательность заново
     * (сплеш-вспышка, повторный me-запрос, риск выкинуть сессию на
     * транзиентном сбое) — здесь инициализированная сессия возвращается
     * как есть. Ещё не выполненный старт ([state] == null) — полный прогон
     * [startup]; параллельный вызов дожидается текущего, а не стартует
     * второй (mutex). Отменённый посреди прогона вызов (уничтожение первой
     * Activity во время сети) флаг не ставит — следующий вызов начинает
     * заново. Принудительный перезапуск — [retryStartup].
     */
    public suspend fun startupIfNeeded(): SessionState {
        val current = mutableState.value
        if (startupCompleted && current != null) return current
        return startupOnceMutex.withLock {
            val settled = mutableState.value
            if (startupCompleted && settled != null) return@withLock settled
            startup()
        }
    }

    /** Флаг «стартовая последовательность процесса уже выполнена» ([startupIfNeeded]). */
    private var startupCompleted = false

    /** Сериализация единственного прогона (гонка двух составов UI). */
    private val startupOnceMutex = Mutex()

    /**
     * Полный старт приложения (импорт handoff, если ключа нет, + initSession).
     * Вызывать один раз при запуске (Stage 6: корутина на IO/Default);
     * обновляет [state]. Успешное завершение помечает старт процесса
     * выполненным ([startupIfNeeded] больше не повторяет его); отменённый
     * посреди прогона вызов флаг не ставит. Форс-перезапуск поверх живого
     * процесса — [retryStartup].
     */
    public suspend fun startup(): SessionState {
        mutableState.value = null
        stripKeyFromCachedUser()
        normalizePendingKey()
        val state = runStartup()
        mutableState.value = state
        startupCompleted = true
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
            mutableRecoveryKey.value = CurrentRecoveryKey.Present(payload.recoveryKey, savedOnDevice = true)
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
                    return offlineOrNull() ?: retryOrSignedOut()
                // Транзиентный сбой 5xx/429 (parity: не «офлайн-причина» в RN):
                // ключ и cached_user хранятся, пользователя нет.
                // И «не-OK без 401» при пустых хранилищах — стартовая пустота
                // п.6: сервер ответил, но ни ключа, ни handoff нет → «ключ
                // не найден» на обновлении (эквивалент ряда «handoff доставлен,
                // но не запускался»: сервер жив, ключа нигде нет).
                else -> return retryOrSignedOut()
            }
        }
        val state = applyUser(me)
        // Item 8: a live session still finishes a pending change.
        if (secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING) != null) finishKeyChangeInBackground()
        return state
    }

    /**
     * A sign-in that did not finish for a transient reason. With a key on this phone the start
     * screen offers to try again with it ([SessionState.RetryNeeded]); a bare login screen would
     * invite the user to type a key, and the stored one still works.
     */
    private suspend fun retryOrSignedOut(): SessionState =
        if (sessionRecoveryKey() != null) SessionState.RetryNeeded else signedOutOrKeyNotFound()

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
     * [hadSession] умирает с процессом, а установка остаётся «обновлением»
     * навсегда, поэтому то же событие пишется в DataStore
     * ([AppPreferencesStore.isSessionEnded]) и читается после рестарта.
     */
    private suspend fun signedOutOrKeyNotFound(): SessionState {
        if (!isUpdateInstall) return SessionState.SignedOut
        if (hadSession) return SessionState.SignedOut
        if (settings.isSessionEnded()) return SessionState.SignedOut
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
        // Иначе выход из офлайн-сессии не знает, чью базу стирать.
        activeUserId = user.id
        return SessionState.Offline(user)
    }

    // ------------------------------------------------------------------
    // Входы (пишут ключ сервера + cached_user)
    // ------------------------------------------------------------------

    /** Вход recovery key (логин-экран, VAL-LOGIN-004). Бросает [ApiError]. */
    public suspend fun signInWithRecoveryKey(recoveryKey: String): SessionState {
        val previous = closingUserId()
        endActiveAccount()
        try {
            val typedIsStored = recoveryKey == secure.get(SecureStoreKeys.RECOVERY_KEY)
            val user = api.loginRecovery(recoveryKey)
            return signedInAfterTyped(previous, user, recoveryKey, typed = !typedIsStored)
        } catch (e: ApiError) {
            val held = if (e is ApiError.Unauthorized) sessionRecoveryKeys() else emptyList()
            if (e is ApiError.Unauthorized && recoveryKey in held && held.size > 1) {
                // The typed key is one this phone holds; another held key (a pending key the
                // server may already have committed) gets its chance before anything is erased.
                for (other in held - recoveryKey) {
                    val user = try {
                        api.loginRecovery(other)
                    } catch (next: ApiError) {
                        if (next is ApiError.Unauthorized) continue
                        throw next
                    }
                    return signedInAfterTyped(previous, user, other, typed = false)
                }
            }
            if (e is ApiError.Unauthorized && held.isNotEmpty() && recoveryKey !in held) {
                // The server rejected a typed or saved key. That says nothing about a different key
                // on this phone (e.g. a 5xx at startup left the user here with a working key), so
                // the stored key, cached_user and that account's database stay.
                hadSession = true
                if (mutableState.value != SessionState.RetryNeeded) mutableState.value = SessionState.SignedOut
            } else if (e is ApiError.Unauthorized) {
                // Явный 401 на вход: ключ невалиден (или стёрт) — чистим
                // (VAL-LOGIN-004). Экран входа после этого — обычный
                // [SessionState.SignedOut]: ключ пользователь ввёл сам, «ключ
                // не найден» здесь не показывается (Stage 3 п.6).
                // Признак «в этом процессе уже был успешный вход» (см.
                // [signedOutOrKeyNotFound]): пустота после такого шага —
                // не стартовая.
                hadSession = true
                markSessionEnded()
                activeUserId = null
                clearKeyAndCachedUser()
                onSessionEnded()
                mutableState.value = SessionState.SignedOut
                // Аккаунт, из которого уходили, больше не на устройстве.
                discardAccountDatabase(previous)
            }
            throw e
        }
    }

    private suspend fun signedInAfterTyped(
        previous: String?,
        user: UserDto,
        usedKey: String,
        typed: Boolean,
    ): SessionState {
        val state = finishSignIn(previous, applyUser(user, usedKey = usedKey))
        settlePendingAfterSignIn(usedKey, typed)
        val key = mutableRecoveryKey.value
        if (key is CurrentRecoveryKey.Present && !key.savedOnDevice) mutableKeyNotStoredNotice.value = true
        return state
    }

    /**
     * Создание анонимного аккаунта (логин-экран, режим create). [name] —
     * уже тримнутое имя экрана или null (опционально; RN `name.trim() ||
     * undefined` — login.tsx:104): null не пишется в провод, непустое едет
     * в POST create-anonymous. Бросает [ApiError].
     */
    public suspend fun createAnonymousAccount(name: String? = null): SessionState {
        val previous = closingUserId()
        endActiveAccount()
        val created = api.createAnonymous(name)
        if (created.recoveryKey.isNullOrBlank()) {
            // Drop the keyless account's cookie; storage keeps whatever key it had.
            onSessionEnded()
            throw MissingRecoveryKeyException()
        }
        // A new account never inherits another account's key change (F4).
        forgetKeyChange()
        return finishSignIn(previous, applyUser(created, acceptServerKey = true))
    }

    // ------------------------------------------------------------------
    // Рантайм
    // ------------------------------------------------------------------

    /**
     * Смена времени уведомлений (Stage 7 п.4, экран профиля; RN `updateUser`
     * + PATCH update-notification-time — `app/(tabs)/profile.tsx:146-166`):
     * запрос и обновление пользователя ([applyUser] — state + cached_user).
     * Тело — `HH:00` (целые часы); no-op на тот же час решает экран, не
     * менеджер. Бросает [ApiError] — профиль показывает message.
     */
    public suspend fun updateNotificationTime(notificationTime: String): UserDto =
        applyUser(api.updateNotificationTime(notificationTime)).user

    /**
     * Two-phase key change (design step 8 v2, KMP items 5, 6, 9), one at a time and not
     * cancellable by the caller:
     * - a pending key on the phone is confirmed first (no second regenerate may overwrite it);
     *   409 drops it and a fresh regenerate follows;
     * - regenerate → write the pending slot → read it back → `confirm-recovery-key`;
     * - a failed pending write sends no confirm: [KeyRotation.NotStored], the old key stays;
     * - confirm 200 → [SecureStoreKeys.RECOVERY_KEY] = new key, then the pending slot is removed;
     *   404/5xx/no answer keeps both ([KeyRotation.Unfinished]).
     *
     * A response without `pending_recovery_key` and without `user.recovery_key` throws
     * [MissingRecoveryKeyException] and changes nothing. Item 9 fallback: a HEAD (pre-rotation)
     * server commits at once and sends the key only in `user.recovery_key`; that key goes the
     * same way (pending slot → confirm), and its 404 on confirm still shows the key as the new one.
     * Other [ApiError]s of the regenerate request are thrown; the old key keeps working.
     */
    public suspend fun regenerateRecoveryKey(): KeyRotation = runKeyChange(regenerate = true, byUser = true)

    /**
     * Confirms a pending key left by an unfinished change (Profile retry). Never regenerates.
     * After a sign-in the same work runs in the background and raises [keyChangeFinishedNotice].
     */
    public suspend fun finishKeyChange(): KeyRotation = runKeyChange(regenerate = false, byUser = true)

    private val keyChangeGate = Mutex()
    private val keyChangeMutex = Mutex()
    private var keyChangeInFlight: Deferred<KeyRotation>? = null
    private var keyChangeInFlightRegenerates = false

    /**
     * Single flight: a call joins the change in flight when that one covers it (any call joins a
     * regenerate; a finish joins a finish); otherwise it runs after it. The work runs in
     * [startupScope] under [NonCancellable], so leaving Profile never cuts a change in half.
     * [byUser] false: a background finish after sign-in, which raises [keyChangeFinishedNotice].
     */
    private suspend fun runKeyChange(regenerate: Boolean, byUser: Boolean): KeyRotation {
        val deferred = keyChangeGate.withLock {
            val current = keyChangeInFlight
            if (current != null && current.isActive && (keyChangeInFlightRegenerates || !regenerate)) {
                current
            } else {
                startupScope.async {
                    withContext(NonCancellable) {
                        keyChangeMutex.withLock {
                            try {
                                changeKey(regenerate, byUser)
                            } catch (abandoned: ChangeAbandoned) {
                                KeyRotation.Abandoned
                            }
                        }
                    }
                }.also {
                    keyChangeInFlight = it
                    keyChangeInFlightRegenerates = regenerate
                }
            }
        }
        return deferred.await()
    }

    private suspend fun changeKey(regenerate: Boolean, byUser: Boolean): KeyRotation {
        val generation = sessionGeneration.value
        val stored = secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING)
        if (stored != null) {
            val outcome = confirmPending(stored, generation, byUser)
            // Generate goes on once the stored key is proven dead (409, or a 401 on the probe
            // after a 404 - e.g. a rollback to HEAD): it must still give a working new key.
            if (outcome !is KeyRotation.Superseded || !regenerate) return outcome
        }
        if (!regenerate) return KeyRotation.NothingPending

        val regenerated = try {
            api.regenerateRecoveryKey()
        } catch (e: ApiError) {
            if (e is ApiError.Network || e is ApiError.Timeout) regenerateAnswerLost = true
            throw e
        }
        regenerateAnswerLost = false
        val pendingField = regenerated.pendingRecoveryKey?.takeIf { it.isNotBlank() }
        // Item 9 fallback: only when pending_recovery_key is absent and user.recovery_key is there.
        val legacyKey = if (pendingField == null) {
            regenerated.user.recoveryKey?.takeIf { it.isNotBlank() }?.let { canonicalRecoveryKey(it) }
        } else {
            null
        }
        val newKey = pendingField ?: legacyKey ?: throw MissingRecoveryKeyException("regenerate-recovery-key")
        if (sessionGeneration.value != generation) return KeyRotation.Abandoned

        // A new change replaces an unstored key from an earlier one.
        unstoredPendingKey = null
        shownPendingKey = null
        val written = secure.set(SecureStoreKeys.RECOVERY_KEY_PENDING, newKey) &&
            secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING) == newKey
        if (sessionGeneration.value != generation) {
            // The session ended during the write: take the key back out, send nothing.
            if (written) removePendingQuietly(ifValue = newKey)
            return KeyRotation.Abandoned
        }
        if (!written) {
            // No confirm: the old key stays the account's key. The new key is shown so the user
            // can save it by hand; signing in with it commits it on the server.
            removePendingQuietly(ifValue = null)
            sessionMutex.withLock {
                if (sessionGeneration.value != generation) return KeyRotation.Abandoned
                unstoredPendingKey = newKey
                // The stored K1 stays the shown key; K2 goes next to it (R3-2).
                mutableUnconfirmedRecoveryKey.value = newKey
            }
            return KeyRotation.NotStored
        }
        // Not proven yet: the screens show it next to the current key, never instead of it.
        markUnproven(newKey, generation)
        return confirmPending(newKey, generation, byUser)
    }

    /**
     * Confirms [key] from the pending slot and sorts the outcome into three states:
     * - proven: confirm 200 (committed / already_current), or the probe sign-in with [key] gives
     *   200 → promote it;
     * - proven dead: confirm 409, or the probe gives 401 → drop the pending slot, show the
     *   current key; a change the user did not tap raises [keyChangeFailedNotice];
     * - unknown (network, 5xx, 400, 401 without a session, an unresolved 404) → keep both and show
     *   both ([unconfirmedRecoveryKey]).
     *
     * The probe runs once, after a 404 on confirm (HEAD has no confirm route; on the new server
     * 404 means the user is gone). It is a plain login-recovery outside the client seam: no answer
     * erases anything, and a 401 never touches [SecureStoreKeys.RECOVERY_KEY].
     */
    private suspend fun confirmPending(key: String, generation: Long, byUser: Boolean): KeyRotation {
        var dead = false
        var notFound = false
        var foreign = false
        var provenUser: UserDto? = try {
            api.confirmRecoveryKey(key).takeIf { it.status == "committed" || it.status == "already_current" }?.user
        } catch (e: ApiError) {
            if (e is ApiError.Client && e.status == 409) dead = true
            if (e is ApiError.Client && e.status == 404) notFound = true
            null
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A 200 the app cannot read may still have committed: unknown.
            null
        }
        val account = sessionUser()?.id ?: activeUserId
        if (notFound && sessionGeneration.value == generation) {
            provenUser = try {
                val probed = api.loginRecovery(key)
                if (account != null && probed.id != account) {
                    // A key of another account (R3-4, R4-1): never promoted, dropped below.
                    foreign = true
                    null
                } else {
                    probed
                }
            } catch (e: ApiError) {
                if (e is ApiError.Unauthorized) dead = true
                null
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
        }
        if (sessionGeneration.value != generation) return KeyRotation.Abandoned
        if (foreign && account != null) {
            dropForeignPendingAndSignBackIn(key, account, generation)
            return KeyRotation.Superseded
        }
        val user = provenUser
        if (user != null) {
            applyUser(user, usedKey = key, generation = generation)
            promote(key, inPendingSlot = true, notify = !byUser, generation = generation)
            return KeyRotation.Done(user)
        }
        if (dead) {
            removePendingQuietly(ifValue = key)
            showConfirmedKey()
            if (!byUser) {
                sessionMutex.withLock {
                    if (sessionGeneration.value == generation) mutableKeyChangeFailedNotice.value = true
                }
            }
            return KeyRotation.Superseded
        }
        markUnproven(key, generation)
        return KeyRotation.Unfinished
    }

    /**
     * The probe's sign-in with [key] landed on another account (R4-1): the key is useless for
     * [account], and the shared cookie now belongs to the other account. Drop the slot (never
     * promote, never write it to RECOVERY_KEY), then sign straight back in with RECOVERY_KEY,
     * outside the seam. Anything but a 200 for [account] drops the cookie; the seam then holds
     * only RECOVERY_KEY, so the foreign key is never tried again.
     */
    private suspend fun dropForeignPendingAndSignBackIn(key: String, account: String, generation: Long) {
        sessionMutex.withLock {
            if (sessionGeneration.value != generation) return
            removePendingQuietly(ifValue = key)
            mutableUnconfirmedRecoveryKey.value = null
            mutableKeyChangePending.value = false
        }
        val own = secure.get(SecureStoreKeys.RECOVERY_KEY)
        val back = if (own == null || own == key) {
            null
        } else {
            try {
                api.loginRecovery(own)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                null
            }
        }
        if (back?.id != account) onSessionEnded()
    }

    /**
     * [key] is the account's key now: make sure [SecureStoreKeys.RECOVERY_KEY] holds it (read,
     * write if needed), and only then remove the pending slot. A failed write keeps the pending
     * slot, which sign-in still tries after the old key's 401. [notify]: the change finished
     * without a tap in Profile, so the user is told to save the new key.
     */
    private suspend fun promote(key: String, inPendingSlot: Boolean, notify: Boolean, generation: Long? = null) {
        sessionMutex.withLock {
            if (generation != null && sessionGeneration.value != generation) throw ChangeAbandoned()
            val stored = secure.get(SecureStoreKeys.RECOVERY_KEY) == key || secure.set(SecureStoreKeys.RECOVERY_KEY, key)
            if (stored) removePendingQuietly(ifValue = null)
            mutableRecoveryKey.value = CurrentRecoveryKey.Present(key, savedOnDevice = stored || inPendingSlot)
            mutableKeyChangePending.value = false
            mutableUnconfirmedRecoveryKey.value = null
            regenerateAnswerLost = false
            if (unstoredPendingKey == key || stored) unstoredPendingKey = null
            // Not written: the key lives only in the pending slot, so the screens keep it.
            shownPendingKey = if (stored) null else key
            if (notify) mutableKeyChangeFinishedNotice.value = true
        }
    }

    /**
     * An unfinished change the server has not proven: [key] is shown next to the current key
     * (it becomes the key when the change finishes; until then the old key also works).
     */
    private suspend fun markUnproven(key: String, generation: Long? = null) {
        sessionMutex.withLock {
            if (generation != null && sessionGeneration.value != generation) return
            mutableUnconfirmedRecoveryKey.value = key
            mutableKeyChangePending.value = true
        }
    }

    /** Back to the confirmed key (409: the pending key is dead). */
    private suspend fun showConfirmedKey() {
        sessionMutex.withLock {
            shownPendingKey = null
            mutableKeyChangePending.value = false
            mutableUnconfirmedRecoveryKey.value = null
            val stored = secure.get(SecureStoreKeys.RECOVERY_KEY)
            mutableRecoveryKey.value = stored?.let { CurrentRecoveryKey.Present(it, savedOnDevice = true) } ?: CurrentRecoveryKey.Missing
        }
    }

    /** Removes the pending slot; [ifValue] non-null removes it only while it holds that key. */
    private suspend fun removePendingQuietly(ifValue: String?) {
        try {
            if (ifValue == null || secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING) == ifValue) {
                secure.remove(SecureStoreKeys.RECOVERY_KEY_PENDING)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // A leftover slot equal to the current key is dropped at the next startup.
            println("GreenThumb: pending key removal failed: ${t::class.simpleName}")
        }
    }

    /** Drops every trace of a key change (another account takes over). */
    private suspend fun forgetKeyChange() {
        removePendingQuietly(ifValue = null)
        unstoredPendingKey = null
        shownPendingKey = null
        mutableKeyChangePending.value = false
        mutableUnconfirmedRecoveryKey.value = null
        mutableKeyChangeFinishedNotice.value = false
        mutableKeyChangeFailedNotice.value = false
        regenerateAnswerLost = false
    }

    /**
     * After a sign-in with [usedKey]: a pending key that worked is promoted; a typed key that is
     * neither held key (another account, or a key from elsewhere) drops the pending slot; else a
     * pending key left by an unfinished change is shown and confirmed in the background.
     */
    private suspend fun settlePendingAfterSignIn(usedKey: String, typed: Boolean) {
        // A sign-in proves which key the account has now.
        regenerateAnswerLost = false
        val pending = secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING)
        when {
            usedKey == pending -> promote(usedKey, inPendingSlot = true, notify = !typed)
            usedKey == unstoredPendingKey -> promote(usedKey, inPendingSlot = false, notify = !typed)
            pending == null -> Unit
            typed -> {
                removePendingQuietly(ifValue = pending)
                shownPendingKey = null
                mutableKeyChangePending.value = false
                mutableUnconfirmedRecoveryKey.value = null
                mutableKeyChangeFinishedNotice.value = false
                mutableKeyChangeFailedNotice.value = false
            }
            else -> {
                markUnproven(pending)
                finishKeyChangeInBackground()
            }
        }
    }

    private fun finishKeyChangeInBackground() {
        startupScope.launch { runCatching { runKeyChange(regenerate = false, byUser = false) } }
    }

    /** Startup (item 8): a pending slot equal to the current key is a finished change. */
    private suspend fun normalizePendingKey() {
        val pending = secure.get(SecureStoreKeys.RECOVERY_KEY_PENDING)
        if (pending != null && pending == secure.get(SecureStoreKeys.RECOVERY_KEY)) {
            removePendingQuietly(ifValue = pending)
            mutableKeyChangePending.value = false
        } else if (pending != null) {
            markUnproven(pending)
        } else {
            mutableKeyChangePending.value = false
        }
    }

    /**
     * Client seam: login-recovery accepted [key]. A pending key is the account's key now; with
     * the confirmed key, a pending change is finished in the background.
     */
    public suspend fun onReloginSucceeded(key: String) {
        settlePendingAfterSignIn(key, typed = false)
    }

    /**
     * Client seam: login-recovery answered 401 for every key in [rejected]. Erase only when no
     * held key is left untried; otherwise only the session ends.
     */
    public suspend fun onRecoveryKeysRejected(rejected: List<String>) {
        if (sessionRecoveryKeys().all { it in rejected }) onAuthError(ApiError.Unauthorized) else onSessionLost()
    }

    /** Serializes season saves: a rollback must not overwrite a later choice. */
    private val seasonMutex = Mutex()

    /** Bumped under [seasonMutex] on every local season change; lets [refreshUser] spot a stale /me. */
    private var seasonChanges: Long = 0

    /**
     * Profile season switcher (architecture §5.6): the new mode is published to [state] and
     * cached_user before the request, so dashboard, detail and the winter hint recompose at once.
     * Any failure restores the previous mode and rethrows. The save is not cancellable by the
     * caller: leaving Profile mid-request must not roll back a mode the server already stored.
     */
    public suspend fun updateSeasonMode(mode: SeasonMode): UserDto = withContext(NonCancellable) {
        seasonMutex.withLock {
            val previous = sessionUser() ?: throw IllegalStateException("no signed-in user")
            seasonChanges++
            publishUser(previous.copy(seasonMode = mode.wire))
            val saved = try {
                api.updateSeasonMode(mode.wire)
            } catch (error: Throwable) {
                val current = sessionUser()
                if (current?.id == previous.id && current.seasonMode == mode.wire) {
                    publishUser(current.copy(seasonMode = previous.seasonMode))
                }
                throw error
            }
            if (sessionUser()?.id == saved.id) applyUser(saved)
            saved
        }
    }

    /** Serializes timezone syncs: two triggers at once send one PATCH. */
    private val timezoneMutex = Mutex()

    /**
     * Sends `PATCH /api/auth/update-timezone` when [zoneId] differs from the signed-in user's
     * `timezone` (NULL included). Only the timezone is merged into [state], so an optimistic season
     * mode in flight stays. A failure is swallowed: the sync never blocks or signs out.
     * Returns true when the server stored the zone.
     */
    public suspend fun syncTimezone(zoneId: String): Boolean = timezoneMutex.withLock {
        val user = (mutableState.value as? SessionState.SignedIn)?.user ?: return false
        if (user.timezone == zoneId) return false
        val saved = try {
            api.updateTimezone(zoneId)
        } catch (error: ApiError) {
            return false
        }
        val current = sessionUser()
        if (current?.id == saved.id) publishUser(current.copy(timezone = saved.timezone))
        true
    }

    /**
     * Re-reads the user (`GET /api/auth/me`) for an existing session, e.g. on app resume, so a mode
     * changed elsewhere reaches Profile and the dashboard. Failures keep the current state; an
     * explicit 401 goes through the client's session seam as usual. Returns the fresh user or null.
     */
    public suspend fun refreshUser(): UserDto? {
        val before = sessionUser() ?: return null
        val seasonChangesBefore = seasonMutex.withLock { seasonChanges }
        val me = try {
            api.me()
        } catch (error: ApiError) {
            return null
        }
        return seasonMutex.withLock {
            val current = sessionUser()
            if (me.id != before.id || current?.id != me.id) return@withLock null
            // A season save that started after this /me was sent wins over the server's older copy.
            val fresh = if (seasonChanges == seasonChangesBefore) me else me.copy(seasonMode = current.seasonMode)
            applyUser(fresh).user
        }
    }

    /**
     * Восстановление сессии сохранённым ключом (шов M2; вызывается
     * ApiClient-провайдером и тестами). Пишет cached_user; Бросает [ApiError].
     */
    public suspend fun loginRecovery(recoveryKey: String): UserDto {
        return applyUser(api.loginRecovery(recoveryKey), usedKey = recoveryKey).user
    }

    /** The recovery key in the phone's secure storage, or null. Screens use [recoveryKey]. */
    public suspend fun storedRecoveryKey(): String? = secure.get(SecureStoreKeys.RECOVERY_KEY)

    /**
     * Рантайм-шов для ApiClient (Stage 2 п.7): исход сетевого вызова наверх.
     * Явный 401 (ключ не принял сервер) → ключ + cached_user чистятся
     * (VAL-LOGIN-004); транзиентные — ключ хранятся. Явный 401 при живом
     * ключе — тоже признак «сессия жила» (см. [signedOutOrKeyNotFound]).
     */
    public suspend fun onAuthError(error: ApiError) {
        if (error is ApiError.Unauthorized) {
            sessionMutex.withLock { endSessionGeneration() }
            val closing = closingUserId()
            val hadKey = secure.get(SecureStoreKeys.RECOVERY_KEY) != null
            if (hadKey) {
                hadSession = true
                markSessionEnded()
            }
            activeUserId = null
            clearKeyAndCachedUser()
            onSessionEnded()
            mutableState.value = SessionState.SignedOut
            discardAccountDatabase(closing)
        }
    }

    /**
     * Client seam: login-recovery accepted the key, but the retried request still got 401. The
     * key works, so it stays together with cached_user and the account database; only the
     * session ends, and the start screen offers a retry ([SessionState.RetryNeeded]).
     */
    public suspend fun onSessionLost() {
        activeUserId = null
        onSessionEnded()
        mutableState.value = if (sessionRecoveryKey() != null) SessionState.RetryNeeded else SessionState.SignedOut
    }

    /**
     * Выход (Stage 3 п.5 + Stage 4 п.5, VAL-DATA-008): серверный logout
     * best-effort, затем [onSessionEnded] (отмена запросов, новый session-id,
     * сброс cookies), затем ключ, cached_user и handoff-файл. База этого
     * пользователя удаляется вместе с WAL/SHM уже после сброса cookies —
     * открытое соединение закрывает [deleteUserDatabase]. Предпочтения
     * (язык/тема/сетка/интро) не тронуты. Обновляет [state]. Итог — обычный
     * [SessionState.SignedOut] (экран входа): пользователь сам вышел, экран
     * «ключ не найден» [SessionState.KeyNotFound] тут не показывается
     * (Stage 3 п.6 — только холодный старт без ключа).
     */
    public suspend fun signOut(): SessionState {
        // A key change still running drops its work (F5): the bump is under the same lock as its
        // writes. No lock is held across the network.
        sessionMutex.withLock { endSessionGeneration() }
        val closing = closingUserId()
        try {
            api.logout()
        } catch (e: ApiError) {
            // logout best-effort (parity RN): локальная чистка не зависит от сети.
        }
        // Выход — действие пользователя: исход после него уже не «холодный
        // старт», «ключ не найден» больше не показывается (см. hadSession).
        hadSession = true
        markSessionEnded()
        activeUserId = null
        onSessionEnded()
        sessionMutex.withLock { clearHeldKeys() }
        handoff.clearHandoff()
        mutableState.value = SessionState.SignedOut
        discardAccountDatabase(closing)
        return SessionState.SignedOut
    }

    public suspend fun deleteAccount(): SessionState {
        val closing = closingUserId()
        // Сначала сервер (Stage 12 п.4, VAL-REL-003): DELETE /api/auth/account
        // каскадно стирает растения, push-подписки (все три вида) и строку
        // пользователя в ОДНОЙ транзакции, затем уничтожает сессию
        // (backend-contract.md №27) — отдельного logout, как в [signOut],
        // здесь нет. Провал ([ApiError]) бросается наверх с ЦЕЛЫМ локальным
        // состоянием: ключ живого аккаунта не стирается потерей ответа.
        // Если сервер удалил аккаунт, а ответ потерялся, ключ стирает
        // 401-дисциплина: следующий me/login-recovery даёт явный 401
        // (строки юзера нет) → [onAuthError].
        api.deleteAccount()
        sessionMutex.withLock { endSessionGeneration() }
        // Успех подтверждён — локальная чистка = матрица signOut
        // (VAL-DATA-008): сессия (cookies, запросы), ключ, cached_user,
        // handoff-файл, база пользователя (+WAL/SHM). Предпочтения (язык/
        // тема/сетка/интро) не тронуты. Исход — [SessionState.SignedOut]:
        // действие пользователя, экран «ключ не найден» не показывается
        // ([hadSession]).
        hadSession = true
        markSessionEnded()
        activeUserId = null
        onSessionEnded()
        sessionMutex.withLock { clearHeldKeys() }
        handoff.clearHandoff()
        mutableState.value = SessionState.SignedOut
        discardAccountDatabase(closing)
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
        // Memory first: a key the phone failed to store is the only one the server accepts.
        val keys = sessionRecoveryKeys()
        if (keys.isEmpty()) return SessionState.SignedOut
        for (key in keys) {
            val user = try {
                api.loginRecovery(key)
            } catch (e: ApiError) {
                when (e) {
                    // The next held key gets its chance (item 7): only a 401 moves on.
                    is ApiError.Unauthorized -> continue
                    is ApiError.Network, is ApiError.Timeout ->
                        // Провал сети при recovery: офлайн-ветка Stage 3 п.5
                        // (сессия не ожила, но пользователь есть локально).
                        return offlineOrNull() ?: SessionState.RetryNeeded
                    // Транзиентные 429/5xx recovery ключ ХРАНЯТ (VAL-LOGIN-004):
                    // the start screen offers a retry with it.
                    else -> return SessionState.RetryNeeded
                }
            }
            val state = applyUser(user, usedKey = key)
            settlePendingAfterSignIn(key, typed = false)
            return state
        }
        // Явный 401 на КАЖДЫЙ ключ телефона: ключи невалидны — чистим (VAL-LOGIN-004)
        // вместе с базой этого пользователя, если она была. The marker goes first: a restart
        // after the key is gone must still read as an ended session (VAL-CRED-022), not as
        // "Key not found".
        val closing = closingUserId()
        hadSession = true
        markSessionEnded()
        clearKeyAndCachedUser()
        discardAccountDatabase(closing)
        return SessionState.SignedOut
    }

    /**
     * Успешный вход/рантайм-обновление пользователя: ключ (сервера — только
     * create/regenerate, см. acceptServerKey) + cached_user → SignedIn. Публикует [SessionState.SignedIn]
     * в [state]: рантайм-обновления (PATCH времени, регенерация ключа —
     * Stage 7 п.4) должны доехать до экранов (RN-паритет `updateUser` в
     * AuthContext: cached_user + setUser(data.user)); вход-пути публикуют
     * то же значение повторно в [finishSignIn]/[startup] — безвредно.
     */
    private suspend fun applyUser(
        user: UserDto,
        usedKey: String? = null,
        /**
         * True only for create-anonymous and regenerate-recovery-key. Any other response (me,
         * login-recovery, PATCH) may have left the server before a regenerate committed, so its
         * `recovery_key` (the old server still sends one) could be the dead old key.
         */
        acceptServerKey: Boolean = false,
        /**
         * A key change's session generation: every write and the publish re-check it under
         * [sessionMutex], so a sign-out in between wins ([ChangeAbandoned]).
         */
        generation: Long? = null,
    ): SessionState.SignedIn {
        if (generation != null && sessionGeneration.value != generation) throw ChangeAbandoned()
        // Признак «в этом процессе уже был успешный вход» (см.
        // [signedOutOrKeyNotFound]): пустота после такого шага — не стартовая.
        hadSession = true
        activeUserId = user.id
        if (settings.isSessionEnded()) writeSessionEnded(false)
        val serverKey = if (acceptServerKey) user.recoveryKey?.takeIf { it.isNotBlank() } else null
        // The key first: a failing cached_user write must not skip it. The lock keeps a slow
        // concurrent applyUser (refreshUser) from writing a stale storage read over a newer key.
        sessionMutex.withLock {
            if (generation != null && sessionGeneration.value != generation) throw ChangeAbandoned()
            // The server's new key wins; then the key the sign-in used ([usedKey]); then a key
            // this process holds but could not store (the write is retried here).
            val unstoredPresent = (mutableRecoveryKey.value as? CurrentRecoveryKey.Present)
                ?.takeIf { !it.savedOnDevice }
            // An unconfirmed key that failed its pending write must never replace the current key.
            val unstored = unstoredPresent?.key?.takeIf { it != unstoredPendingKey }
            val key = serverKey ?: usedKey ?: unstored
            val stored = secure.get(SecureStoreKeys.RECOVERY_KEY)
            val shown = (mutableRecoveryKey.value as? CurrentRecoveryKey.Present)?.key
            val keepShowingUnstoredPending = key == null && shown != null &&
                (shown == unstoredPendingKey || shown == shownPendingKey)
            // Set before [state]: a screen must not see the new user with the old key.
            if (!keepShowingUnstoredPending) mutableRecoveryKey.value = when {
                key == null -> stored?.let { CurrentRecoveryKey.Present(it, savedOnDevice = true) } ?: CurrentRecoveryKey.Missing
                key == stored -> CurrentRecoveryKey.Present(key, savedOnDevice = true)
                // A failed write keeps the key in memory: the server already accepts only this one.
                else -> CurrentRecoveryKey.Present(key, savedOnDevice = secure.set(SecureStoreKeys.RECOVERY_KEY, key))
            }
        }
        cacheUser(user)
        val state = SessionState.SignedIn(user)
        sessionMutex.withLock {
            if (generation != null && sessionGeneration.value != generation) {
                // The sign-out cleared cached_user while this write was running.
                try {
                    settings.clearCachedUser()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    println("GreenThumb: cached_user cleanup failed: ${t::class.simpleName}")
                }
                throw ChangeAbandoned()
            }
            mutableState.value = state
        }
        return state
    }

    /** A key change outlived its session (sign-out, deletion, 401): it stops without writing. */
    private class ChangeAbandoned : RuntimeException("key change abandoned")

    private fun sessionUser(): UserDto? = when (val current = mutableState.value) {
        is SessionState.SignedIn -> current.user
        is SessionState.Offline -> current.user
        else -> null
    }

    /** Replaces the user of a signed-in or offline session (state + cached_user), keeping its kind. */
    private suspend fun publishUser(user: UserDto) {
        mutableState.value = when (mutableState.value) {
            is SessionState.SignedIn -> SessionState.SignedIn(user)
            is SessionState.Offline -> SessionState.Offline(user)
            else -> return
        }
        cacheUser(user)
    }

    /**
     * cached_user is unencrypted: the key lives only in secure storage. A failed write (full disk)
     * is not fatal: cached_user only serves an offline start.
     */
    private suspend fun cacheUser(user: UserDto) {
        try {
            settings.setCachedUser(user.copy(recoveryKey = null))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            println("GreenThumb: cached_user write failed: ${t::class.simpleName}")
        }
    }

    /** Older builds kept the plaintext key in cached_user; remove it once. Best-effort. */
    private suspend fun stripKeyFromCachedUser() {
        try {
            val cached = settings.getCachedUser() ?: return
            if (cached.recoveryKey != null) settings.setCachedUser(cached.copy(recoveryKey = null))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            println("GreenThumb: cached_user cleanup failed: ${t::class.simpleName}")
        }
    }

    /**
     * Уже есть аккаунт в этом процессе: перед новым входом сбросить сессию,
     * чтобы запрос логина не уехал со старой cookie и поздний ответ не
     * записался в данные нового пользователя.
     */
    private suspend fun endActiveAccount() {
        if (activeUserId == null) return
        activeUserId = null
        onSessionEnded()
    }

    /**
     * Пишется до стирания ключа: если процесс умрёт между шагами, ключ ещё
     * на месте, и следующий вход через [applyUser] снимет отметку.
     */
    private suspend fun markSessionEnded() {
        writeSessionEnded(true)
    }

    /** Провал записи не ломает выход: худший исход — экран «ключ не найден». */
    private suspend fun writeSessionEnded(ended: Boolean) {
        try {
            settings.setSessionEnded(ended)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // отметка — подсказка для экрана старта, не часть чистки ключа
        }
    }

    /** Pending first: a death between the two removals leaves the confirmed key, never a lone pending one. */
    private suspend fun clearHeldKeys() {
        secure.remove(SecureStoreKeys.RECOVERY_KEY_PENDING)
        secure.remove(SecureStoreKeys.RECOVERY_KEY)
        settings.clearCachedUser()
        unstoredPendingKey = null
        shownPendingKey = null
        mutableKeyChangePending.value = false
        mutableUnconfirmedRecoveryKey.value = null
        mutableKeyChangeFinishedNotice.value = false
        mutableKeyChangeFailedNotice.value = false
        regenerateAnswerLost = false
        mutableRecoveryKey.value = CurrentRecoveryKey.Missing
    }

    /** Единая чистка: только явный 401 (VAL-LOGIN-004). */
    private suspend fun clearKeyAndCachedUser() {
        sessionMutex.withLock { clearHeldKeys() }
    }

    /**
     * Кого закрываем: живой [activeUserId], иначе пользователь на экране,
     * иначе cached_user (офлайн-старт ещё не писал [activeUserId]).
     */
    private suspend fun closingUserId(): String? {
        activeUserId?.let { return it }
        when (val current = mutableState.value) {
            is SessionState.SignedIn -> return current.user.id
            is SessionState.Offline -> return current.user.id
            else -> Unit
        }
        return settings.getCachedUser()?.id
    }

    /**
     * Успешный вход поверх другого аккаунта стирает базу предыдущего
     * (architecture.md §7). Повторный вход в того же пользователя базу
     * не трогает. [state] публикуется после удаления, чтобы UI не открыл
     * новый файл, пока старое соединение ещё живо.
     */
    private suspend fun finishSignIn(previousUserId: String?, state: SessionState.SignedIn): SessionState {
        if (previousUserId != null && previousUserId != state.user.id) {
            discardAccountDatabase(previousUserId)
        }
        mutableState.value = state
        return state
    }

    /** Файл базы + журналы. Нет id — нечего стирать. Ошибка удаления не откатывает выход. */
    private suspend fun discardAccountDatabase(userId: String?) {
        if (userId.isNullOrBlank()) return
        deleteUserDatabase(userId)
    }
}
