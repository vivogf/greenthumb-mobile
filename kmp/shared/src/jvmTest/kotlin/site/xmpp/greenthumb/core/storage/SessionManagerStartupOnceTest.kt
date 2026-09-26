package site.xmpp.greenthumb.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Единственный прогон стартовой последовательности на процесс
 * (architecture.md §9, фикс M6 kmp-android-activity-session-graph):
 * Android пересоздаёт Activity при повороте/смене масштаба шрифта, и новый
 * состав UI запускает `session.startup()` заново на том же менеджере.
 * Повторный прогон сбрасывает [SessionManager.state] в null и повторяет
 * сетевую последовательность (сплеш-вспышка, второй me-запрос). Требование:
 * `startupIfNeeded` выполняет последовательность один раз на процесс и
 * возвращает уже установленное состояние при повторных/параллельных вызовах.
 */
class SessionManagerStartupOnceTest {

    // ------------------------------------------------------------------
    // Двойники (та же механика, что в SessionManager*Test)
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

    private class FakeHandoff : HandoffSource {
        var clearCount = 0

        override fun readHandoff(): HandoffPayload? = null

        override fun clearHandoff() {
            clearCount++
        }
    }

    /**
     * «Сервер»: me отвечает 200 user'ом; [meGate] держит ответ открытым,
     * чтобы второй параллельный startup гарантированно успел войти, а
     * [meCount] считает фактические запросы. [failMeTransport] = транспортный
     * провал me до гейта (холодный старт без сети → SignedOut).
     */
    private class GatedServer {
        var meCount = 0
        var failMeTransport = false
        val meGate = CompletableDeferred<Unit>()

        private fun userJson(): String =
            """{"user":{"id":71,"name":"kmp-val-startup-once","recovery_key":"key-71","created_at":"2026-09-26T10:00:00.000Z"}}"""

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> {
                    meCount++
                    if (failMeTransport) throw IllegalStateException("Connection reset")
                    meGate.await()
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/login-recovery" -> respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
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

    private fun newManager(server: GatedServer): SessionManager {
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(secure))
        clientRef = client
        return SessionManager(secure, settings, FakeHandoff(), GreenThumbApi(client))
    }

    // ------------------------------------------------------------------
    // Единственный прогон
    // ------------------------------------------------------------------

    @Test
    fun `repeated startupIfNeeded runs startup once`() = runBlocking {
        val server = GatedServer()
        val manager = newManager(server)
        server.meGate.complete(Unit)

        val first = manager.startupIfNeeded()
        val second = manager.startupIfNeeded()

        assertEquals(1, server.meCount, "повторный вызов не крутит me-запрос заново")
        assertEquals(first, second, "оба вызова получают то же состояние сессии")
    }

    @Test
    fun `startupIfNeeded after explicit startup does not re-run`() = runBlocking {
        val server = GatedServer()
        val manager = newManager(server)
        server.meGate.complete(Unit)

        val started = manager.startup()
        val again = manager.startupIfNeeded()

        assertEquals(1, server.meCount, "инициализированная сессия не перезапускается")
        assertEquals(started, again)
    }

    @Test
    fun `startupIfNeeded after session actions returns state without re-running`() = runBlocking {
        val server = GatedServer()
        server.failMeTransport = true
        val manager = newManager(server)
        // Холодный старт без сети и без ключа → SignedOut; затем явный выход.
        assertEquals(SessionState.SignedOut, manager.startupIfNeeded())
        manager.signOut()

        val after = manager.startupIfNeeded()

        assertEquals(SessionState.SignedOut, after)
        assertEquals(1, server.meCount, "выход и повторный вызов не повторяют стартовую последовательность")
    }

    @Test
    fun `concurrent startupIfNeeded awaits single run`() = runBlocking {
        val server = GatedServer()
        val manager = newManager(server)

        val first = async { manager.startupIfNeeded() }
        // Второй состав UI (пересозданная Activity) стартует, пока первый
        // ещё в сети: гейт держит ответ me, доля ожидания даёт второму
        // вызову дойти до менеджера.
        withTimeout(5000) { while (server.meCount == 0) delay(10) }
        val second = async { manager.startupIfNeeded() }
        delay(300)
        server.meGate.complete(Unit)

        val s1 = first.await()
        val s2 = second.await()

        assertEquals(1, server.meCount, "параллельные вызовы делят один прогон, а не стартуют второй")
        assertEquals(s1, s2)
        assertEquals(SessionState.SignedIn::class, s1::class)
    }
}
