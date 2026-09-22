package site.xmpp.greenthumb.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 3 п.5 (фича kmp-offline-session): офлайн-сессия на старте.
 *
 * Ожидаемые поведения (features.json kmp-offline-session, VAL-OFF-003):
 *  - сетевой провал me ([ApiError.Network]/[ApiError.Timeout]) + ключ в
 *    SecureStore + cached_user → [SessionState.Offline] (пользователь
 *    продолжает работу; данные/очередь — M4, Room + pending_mutations);
 *  - явный 401 офлайн-режим НЕ включает: экран входа, ключ + cached_user
 *    чистятся (VAL-LOGIN-004);
 *  - нет cached_user ИЛИ нет ключа → [SessionState.SignedOut];
 *  - 5xx/429 — тоже НЕ офлайн (по плану офлайн только на Network/Timeout);
 *  - cached_user стирается при signOut.
 */
class SessionManagerOfflineTest {

    // ------------------------------------------------------------------
    // Двойники (та же механика, что в SessionManagerTest)
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

    private class FakeSettings : AppPreferencesStore {
        val values = mutableMapOf<String, String>()
        var storedUser: UserDto? = null

        override suspend fun getLanguage(): AppLanguage? =
            values[AppSettingsKeys.LANGUAGE]?.let { AppLanguage.fromWire(it) }

        override suspend fun setLanguage(language: AppLanguage) {
            values[AppSettingsKeys.LANGUAGE] = language.wire
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

        override val language: kotlinx.coroutines.flow.Flow<AppLanguage?> =
            kotlinx.coroutines.flow.emptyFlow()

        override val layoutMode: kotlinx.coroutines.flow.Flow<LayoutMode?> =
            kotlinx.coroutines.flow.emptyFlow()

        override val theme: kotlinx.coroutines.flow.Flow<ThemePreference?> =
            kotlinx.coroutines.flow.emptyFlow()

        override val introSeen: kotlinx.coroutines.flow.Flow<Boolean> =
            kotlinx.coroutines.flow.emptyFlow()

        override val cachedUser: kotlinx.coroutines.flow.Flow<UserDto?> =
            kotlinx.coroutines.flow.emptyFlow()

        override suspend fun applyLegacyHandoffValues(
            language: AppLanguage?,
            theme: ThemePreference?,
            layoutMode: LayoutMode?,
            introSeen: Boolean?,
        ) {
            language?.let { values[AppSettingsKeys.LANGUAGE] = it.wire }
            theme?.let { values[AppSettingsKeys.THEME] = it.wire }
            layoutMode?.let { values[AppSettingsKeys.LAYOUT_MODE] = it.wire }
            if (introSeen == true) values[AppSettingsKeys.INTRO_SEEN] = "1"
        }
    }

    private class NoHandoff : HandoffSource {
        override fun readHandoff(): HandoffPayload? = null

        override fun clearHandoff() {}
    }

    private class FakeServer {
        var meCount = 0
        var loginCount = 0
        var meStatus: HttpStatusCode = HttpStatusCode.OK
        var failMeTransport = false

        private fun userJson(): String =
            """{"user":{"id":55,"name":"kmp-val","recovery_key":"key-55","created_at":"2026-09-21T10:00:00.000Z"}}"""

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private var loginSucceeded = false

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> {
                    meCount++
                    if (failMeTransport) throw IllegalStateException("Connection reset")
                    val status = if (loginSucceeded) HttpStatusCode.OK else meStatus
                    respond(userJson(), status, JSON_HEADERS)
                }
                "/api/auth/login-recovery" -> {
                    loginCount++
                    if (meStatus == HttpStatusCode.OK) loginSucceeded = true
                    respond(userJson(), meStatus, JSON_HEADERS)
                }
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS)
            }
        }
    }

    // ------------------------------------------------------------------
    // Фикстура
    // ------------------------------------------------------------------

    private val secure = FakeSecureStore()
    private val settings = FakeSettings()

    private var clientRef: site.xmpp.greenthumb.core.network.ApiClient? = null

    @AfterTest
    fun cleanup() {
        clientRef?.close()
    }

    private fun cachedUser(id: String, recoveryKey: String): UserDto =
        UserDto(id = id, recoveryKey = recoveryKey, createdAt = "2026-09-21T10:00:00.000Z")

    private fun newManager(server: FakeServer = FakeServer()): SessionManager {
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            MockEngine(server.handler),
            InMemoryRecoveryProvider(secure, settings),
        )
        clientRef = client
        return SessionManager(secure, settings, NoHandoff(), GreenThumbApi(client))
    }

    /** Провайдер восстановления поверх двойников (без второго 401-шва M2). */
    private class InMemoryRecoveryProvider(
        private val secure: SecureKeyValueStore,
        private val settings: AppPreferencesStore,
    ) : site.xmpp.greenthumb.core.network.SessionRecoveryProvider {
        private val writeMutex = Mutex()

        override suspend fun getRecoveryKey(): String? =
            secure.get(SecureStoreKeys.RECOVERY_KEY)

        override suspend fun onSessionReset() {
            writeMutex.withLock {
                secure.remove(SecureStoreKeys.RECOVERY_KEY)
                settings.clearCachedUser()
            }
        }
    }

    // ------------------------------------------------------------------
    // Network/Timeout + ключ + cached_user → офлайн
    // ------------------------------------------------------------------

    @Test
    fun `network failure with key and cached user goes offline`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager(server)

        val state = manager.initSession()

        val offline = assertOffline(state)
        assertEquals("55", offline.user.id, "офлайн-режим с cached_user")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ сохранён")
        assertEquals("55", settings.getCachedUser()!!.id, "cached_user сохранён")
        assertEquals(0, server.loginCount, "recovery не запускается на сетевом провале me")
    }

    @Test
    fun `timeout with key and cached user goes offline`() = runBlocking {
        val engine = MockEngine { _ ->
            throw HttpRequestTimeoutException("/api/auth/me", 10_000L)
        }
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            engine,
            InMemoryRecoveryProvider(secure, settings),
        )
        clientRef = client
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = SessionManager(secure, settings, NoHandoff(), GreenThumbApi(client))

        val state = manager.initSession()

        val offline = assertOffline(state)
        assertEquals("55", offline.user.id, "таймаут me — тоже офлайн-режим (Stage 3 п.5)")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun `startup network failure with key and cached user goes offline`() = runBlocking<Unit> {
        val server = FakeServer()
        server.failMeTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager(server)

        val state = manager.startup()

        assertOffline(state, "startup = импорт handoff (нет — NoHandoff) + initSession → офлайн")
    }

    // ------------------------------------------------------------------
    // Явный 401 → НЕ офлайн: экран входа, ключ + cached_user чистятся
    // ------------------------------------------------------------------

    @Test
    fun `me 401 with key and cached user does not go offline`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(
            SessionState.SignedOut,
            state,
            "401 от me при живом ключе ведёт в recovery, а не в офлайн",
        )
        assertEquals(1, server.loginCount, "recovery запущен (401 = нет сессии)")
        // recovery на stored-key сервер принимает (loginStatus=meStatus=401 → второй 401)
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "второй 401 чистит ключ")
        assertNull(settings.getCachedUser(), "cached_user чистится вместе с ключом")
    }

    @Test
    fun `me 401 without key does not go offline`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        settings.storedUser = cachedUser("55", "dead-key")
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "401 без ключа — экран входа, не офлайн")
        assertEquals(0, server.loginCount, "recovery не запускается без ключа")
        // 401-дисциплина: ключа нет — чистить нечего; cached_user сохранён
        // (чистка cached_user только с явным 401 при ключе / на входе / signOut).
        // VAL-OFF-003: 401 не включил офлайн — главное здесь.
        assertEquals("55", settings.getCachedUser()!!.id, "cached_user не тронут (чистить нечем — ключа нет)")
    }

    // ------------------------------------------------------------------
    // Нет cached_user ИЛИ нет ключа → экран входа
    // ------------------------------------------------------------------

    @Test
    fun `network failure without cached user stays signed out`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "сеть умерла, cached_user нет → экран входа")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ транзиентный провал хранит")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `network failure without key stays signed out`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = true
        settings.storedUser = cachedUser("55", "lost-key")
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "сеть умерла, ключа нет → экран входа")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertEquals("55", settings.getCachedUser()!!.id, "cached_user не тронут (чистка — только 401/signOut)")
    }

    // ------------------------------------------------------------------
    // Граница офлайна: только Network/Timeout; 5xx/429 — экран входа
    // ------------------------------------------------------------------

    @Test
    fun `server error with key and cached user does not go offline`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.ServiceUnavailable
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "5xx — НЕ офлайн (офлайн только Network/Timeout)")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ сохранён")
        assertEquals("55", settings.getCachedUser()!!.id, "cached_user сохранён для следующей попытки")
        assertEquals(0, server.loginCount)
    }

    @Test
    fun `rate limit with key and cached user does not go offline`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.TooManyRequests
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager(server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "429 — НЕ офлайн")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertEquals("55", settings.getCachedUser()!!.id)
        assertEquals(0, server.loginCount)
    }

    // ------------------------------------------------------------------
    // signOut стирает cached_user
    // ------------------------------------------------------------------

    @Test
    fun `signOut clears cached user and key`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager()

        val state = manager.signOut()

        assertEquals(SessionState.SignedOut, state)
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ стёрт при выходе")
        assertNull(settings.getCachedUser(), "cached_user стёрт при выходе (Stage 3 п.5)")
    }

    @Test
    fun `auth error 401 clears cached user`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager()

        manager.onAuthError(ApiError.Unauthorized)

        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertNull(settings.getCachedUser(), "runtime-401 чистит cached_user (VAL-OFF-003)")
    }

    @Test
    fun `auth error transient keeps cached user`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = cachedUser("55", "stored-key")
        val manager = newManager()

        manager.onAuthError(ApiError.Server(503, "quota"))
        manager.onAuthError(ApiError.Network)
        manager.onAuthError(ApiError.Timeout)
        manager.onAuthError(ApiError.Client(429, "rate"))

        assertEquals("55", settings.getCachedUser()!!.id, "транзиентные ошибки cached_user не трогают")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private fun assertOffline(
        state: SessionState,
        message: String = "ожидался офлайн-режим",
    ): SessionState.Offline {
        if (state !is SessionState.Offline) {
            throw AssertionError("$message, получено ${state::class.simpleName ?: "null"}")
        }
        return state
    }
}
