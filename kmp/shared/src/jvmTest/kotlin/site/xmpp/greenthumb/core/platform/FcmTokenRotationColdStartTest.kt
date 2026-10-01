package site.xmpp.greenthumb.core.platform

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.AppSettingsKeys
import site.xmpp.greenthumb.core.storage.HandoffPayload
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.core.storage.ThemePreference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ротация FCM-токена в холодном процессе (фикс scrutiny m9-push): FCM может
 * поднять процесс ТОЛЬКО ради [android-app onNewToken] — MainActivity/App()
 * не запускались, [SessionManager.state] ещё null (стартовая
 * последовательность крутится только из App()). Фоновый путь обязан сам
 * запустить [SessionManager.startupIfNeeded] (идемпотентен на процесс,
 * SessionManagerStartupOnceTest) и решать по его результату:
 * - холодный старт восстановил SignedIn → ротация доходит до шва подписки;
 * - SignedOut → шов НЕ трогается (никаких 401-восстановлений из фона сверх
 *   того, что делает сам startup);
 * - приложение уже стартовало (SignedIn) → прямой путь, startup второй раз
 *   не запускается.
 *
 * Всё через ЛОКАЛЬНЫЕ двойники (MockEngine + двойник [PushSubscriptions]),
 * без live-запросов (стоп M9 на live-аккаунты).
 */
class FcmTokenRotationColdStartTest {

    // ------------------------------------------------------------------
    // Двойники (та же механика, что в SessionManagerStartupOnceTest)
    // ------------------------------------------------------------------

    private class FakeSecureStore : SecureKeyValueStore {
        val map = linkedMapOf<String, String>()

        override suspend fun get(key: String): String? = map[key]

        override suspend fun set(key: String, value: String): Boolean {
            map[key] = value
            return true
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    /** Настройки с ЖИВЫМ language-флоу (ротация читает его через first()). */
    private class FakeSettings : AppPreferencesStore {
        val values = mutableMapOf<String, String>()
        var storedUser: UserDto? = null
        private val languageFlow = MutableStateFlow<AppLanguage?>(AppLanguage.En)

        override suspend fun getLanguage(): AppLanguage? =
            values[AppSettingsKeys.LANGUAGE]?.let { AppLanguage.fromWire(it) }

        override suspend fun setLanguage(language: AppLanguage) {
            values[AppSettingsKeys.LANGUAGE] = language.wire
            languageFlow.value = language
        }

        override suspend fun getLayoutMode(): LayoutMode? =
            values[AppSettingsKeys.LAYOUT_MODE]?.let { LayoutMode.fromWire(it) }

        override suspend fun setLayoutMode(mode: LayoutMode) {
            values[AppSettingsKeys.LAYOUT_MODE] = mode.wire
        }

        override suspend fun getTheme(): ThemePreference? =
            values[AppSettingsKeys.THEME]?.let { ThemePreference.fromWire(it) }

        override suspend fun setTheme(theme: ThemePreference) {
            values[AppSettingsKeys.THEME] = theme.wire
        }

        override suspend fun isIntroSeen(): Boolean = values[AppSettingsKeys.INTRO_SEEN] == "1"

        override suspend fun setIntroSeen() {
            values[AppSettingsKeys.INTRO_SEEN] = "1"
        }

        override suspend fun getCachedUser(): UserDto? = storedUser

        override suspend fun setCachedUser(user: UserDto) {
            storedUser = user
        }

        override suspend fun clearCachedUser() {
            storedUser = null
        }

        override val language: Flow<AppLanguage?> = languageFlow

        override val layoutMode: Flow<LayoutMode?> = MutableStateFlow(null)

        override val theme: Flow<ThemePreference?> = MutableStateFlow(null)

        override val introSeen: Flow<Boolean> = MutableStateFlow(false)

        override val cachedUser: Flow<UserDto?> = MutableStateFlow(null)

        override suspend fun applyLegacyHandoffValues(
            language: AppLanguage?,
            theme: ThemePreference?,
            layoutMode: LayoutMode?,
            introSeen: Boolean?,
        ) {
            language?.let {
                values[AppSettingsKeys.LANGUAGE] = it.wire
                languageFlow.value = it
            }
            theme?.let { values[AppSettingsKeys.THEME] = it.wire }
            layoutMode?.let { values[AppSettingsKeys.LAYOUT_MODE] = it.wire }
            if (introSeen == true) values[AppSettingsKeys.INTRO_SEEN] = "1"
        }
    }

    private class FakeHandoff : HandoffSource {
        override fun readHandoff(): HandoffPayload? = null

        override fun clearHandoff() {}
    }

    /**
     * «Сервер» холодного процесса: cookie-сессии нет (процесс свежий), поэтому
     * me по умолчанию отвечает 401; [recoveryOk] решает, принимает ли сервер
     * ключ. Успешный login-recovery «ставит cookie» ([sessionAlive] = true) —
     * повторный me живой сессии отвечает 200, как реальный сервер; иначе
     * второй 401 сбрасывал бы сессию (шов клиента зовёт onSessionReset).
     * [meOk] = true — ветка «приложение уже стартовало» с живой сессией.
     * Счётчики — для проверки, что стартовая последовательность не крутится
     * второй раз.
     */
    private class ColdServer {
        var meCount = 0
        var recoveryCount = 0
        var recoveryOk = true
        var meOk = false
        var sessionAlive = false

        private fun userJson(): String =
            """{"user":{"id":71,"name":"kmp-val-cold-rotation","recovery_key":"key-71","created_at":"2026-09-26T10:00:00.000Z"}}"""

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> {
                    meCount++
                    if (meOk || sessionAlive) respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                    else respond("unauthorized", HttpStatusCode.Unauthorized, JSON_HEADERS)
                }
                "/api/auth/login-recovery" -> {
                    recoveryCount++
                    if (recoveryOk) {
                        sessionAlive = true
                        respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                    } else {
                        respond("unauthorized", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    }
                }
                else -> respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS)
            }
        }
    }

    private class InMemoryRecoveryProvider(
        private val secure: SecureKeyValueStore,
    ) : SessionRecoveryProvider {
        override suspend fun getRecoveryKey(): String? = secure.get(SecureStoreKeys.RECOVERY_KEY)

        override suspend fun onSessionReset() {
            secure.remove(SecureStoreKeys.RECOVERY_KEY)
        }
    }

    /** Двойник шва подписки: вызовы и аргументы фиксируются. */
    private class FakeSubs : PushSubscriptions {
        var statusCalls = 0
        var subscribeCalls = 0
        var lastToken: String? = null
        var lastPlatform: String? = null
        var lastLanguage: String? = null

        override suspend fun subscribe(token: String, platform: String, language: String) {
            subscribeCalls++
            lastToken = token
            lastPlatform = platform
            lastLanguage = language
        }

        override suspend fun unsubscribe() {}

        override suspend fun status(): Boolean {
            statusCalls++
            return true
        }
    }

    // ------------------------------------------------------------------
    // Фикстура
    // ------------------------------------------------------------------

    private val secure = FakeSecureStore()
    private val settings = FakeSettings()

    private var clientRef: ApiClient? = null

    @AfterTest
    fun cleanup() {
        clientRef?.close()
    }

    private fun newManager(server: ColdServer): SessionManager {
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(secure))
        clientRef = client
        return SessionManager(secure, settings, FakeHandoff(), GreenThumbApi(client))
    }

    // ------------------------------------------------------------------
    // Три случая
    // ------------------------------------------------------------------

    @Test
    fun coldProcess_startupRestoresSignIn_rotationReachesSeam() = runBlocking {
        // Холодный процесс: ключ в SecureStore есть, cookie-сессии нет
        // (me → 401), App()/MainActivity не запускались — state ещё null.
        val server = ColdServer()
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "key-71"
        val manager = newManager(server)
        val subs = FakeSubs()
        assertNull(manager.state.value, "холодный процесс: стартовая последовательность ещё не крутилась")

        val outcome = rotateFcmTokenAfterColdStart(
            manager, settings, FcmTokenRotation(subs), "cold-token-2", "android",
        )

        // startup сам восстановил сессию ключом (его собственный 401-шов) —
        // ротация доведена до шва подписки с новым токеном.
        assertIs<FcmTokenRotation.Outcome.Updated>(outcome)
        assertEquals(1, subs.subscribeCalls)
        assertEquals("cold-token-2", subs.lastToken)
        assertEquals("android", subs.lastPlatform)
        assertEquals("en", subs.lastLanguage, "язык подписки — текущий язык UI (VAL-PUSH-007)")
        assertTrue(manager.state.value is SessionState.SignedIn, "холодный старт завершился входом")
    }

    @Test
    fun coldProcess_signedOut_seamNotTouched() = runBlocking {
        // Ключа нет нигде: startup честно крутит me (и получает 401) —
        // восстановиться не из чего → SignedOut. Шов подписки после этого
        // НЕ вызывается: «подписка была включена для текущего аккаунта»
        // без аккаунта не существует, 401 от шва не должен запускать
        // восстановление сессии из фонового колбэка.
        val server = ColdServer().apply { recoveryOk = false }
        val manager = newManager(server)
        val subs = FakeSubs()

        val outcome = rotateFcmTokenAfterColdStart(
            manager, settings, FcmTokenRotation(subs), "cold-token-3", "android",
        )

        assertNull(outcome, "без SignedIn-сессии ротация не выполняется")
        assertEquals(0, subs.statusCalls, "шов статуса не тронут без сессии")
        assertEquals(0, subs.subscribeCalls, "подписка не создаётся без сессии")
        assertEquals(SessionState.SignedOut, manager.state.value)
    }

    @Test
    fun alreadySignedIn_directPath_startupNotReRun() = runBlocking {
        // Приложение уже стартовало (App() выполнил startup) — фоновая
        // ротация идёт напрямую к шву и НЕ запускает стартовую
        // последовательность второй раз (ни me-запроса).
        val server = ColdServer().apply { meOk = true }
        val manager = newManager(server)
        val subs = FakeSubs()
        assertTrue(manager.startup() is SessionState.SignedIn)
        assertEquals(1, server.meCount, "базовый прогон: один me-запрос на старт приложения")

        val first = rotateFcmTokenAfterColdStart(
            manager, settings, FcmTokenRotation(subs), "warm-token-1", "android",
        )
        val second = rotateFcmTokenAfterColdStart(
            manager, settings, FcmTokenRotation(subs), "warm-token-2", "android",
        )

        assertIs<FcmTokenRotation.Outcome.Updated>(first)
        assertIs<FcmTokenRotation.Outcome.Updated>(second)
        assertEquals(1, server.meCount, "повторная ротация не перезапускает startup")
        assertEquals(2, subs.subscribeCalls, "обе ротации дошли до шва напрямую")
        assertEquals("warm-token-2", subs.lastToken, "серверу отправлен последний токен")
    }
}
