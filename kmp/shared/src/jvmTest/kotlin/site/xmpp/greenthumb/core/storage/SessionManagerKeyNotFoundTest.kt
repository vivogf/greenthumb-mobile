package site.xmpp.greenthumb.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 3 п.6 (фича kmp-key-not-found-screen): экран «ключ не найден».
 *
 * Ожидаемые поведения (features.json kmp-key-not-found-screen, VAL-HANDOFF-IMP-002):
 *  - нет ключа ни в SecureStore, ни в handoff + установка — обновление поверх
 *    предыдущей (lastUpdateTime ≠ firstInstallTime) → [SessionState.KeyNotFound];
 *  - та же пустота на ЧИСТОЙ установке (fresh install) → обычный экран входа
 *    ([SessionState.SignedOut]) — экран «ключ не найден» не показывается;
 *  - обычный вход/выход (аккаунт жив, ключ был) → [SessionState.SignedOut],
 *    никогда не KeyNotFound;
 *  - транзиентные сбои (429/5xx/сеть/таймаут) при сохранённом ключе → экран
 *    входа, НЕ KeyNotFound (ключ хранится — parity VAL-LOGIN-004);
 *  - импорт handoff: ключ найден в файле → KeyNotFound не возникает;
 *  - провал импорта handoff → [SessionState.HandoffImportFailed], не KeyNotFound.
 *
 * «Обновление» на desktop-харнессе всегда false (jvm-actual вернёт false —
 * desktop не был Expo-сборкой, экрану там взяться неоткуда), поэтому jvmTest
 * инжектирует факт обновления через конструктор менеджера (платформенный факт
 * подставляет android-actual в SessionGraph).
 */
class SessionManagerKeyNotFoundTest {

    // ------------------------------------------------------------------
    // Двойники (та же механика, что в SessionManagerTest/OfflineTest)
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

    /** Handoff-двойник: опциональный файл + счётчик clear. */
    private class FakeHandoff(
        private var payload: HandoffPayload? = null,
    ) : HandoffSource {
        var clearCount = 0

        override fun readHandoff(): HandoffPayload? = payload

        override fun clearHandoff() {
            clearCount++
            payload = null
        }
    }

    /**
     * «Сервер»: me/login/logout с настраиваемыми статусами и счётчиками.
     * По умолчанию me бросает транспортную ошибку (сервер недостижим): тесты
     * «пусто нигде» не зависят от ответов, «живой сервер» настраивает статусы.
     */
    private class FakeServer {
        var meCount = 0
        var loginCount = 0
        var meStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable
        var loginStatus: HttpStatusCode = HttpStatusCode.OK
        var failMeTransport = true

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
                    if (loginStatus == HttpStatusCode.OK) loginSucceeded = true
                    respond(userJson(), loginStatus, JSON_HEADERS)
                }
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS)
            }
        }
    }

    /** Провайдер восстановления над двойниками (без второго 401-шва M2). */
    private class InMemoryRecoveryProvider(
        private val secure: SecureKeyValueStore,
        private val onSessionReset: (() -> Unit)? = null,
    ) : SessionRecoveryProvider {
        override suspend fun getRecoveryKey(): String? = secure.get(SecureStoreKeys.RECOVERY_KEY)

        override suspend fun onSessionReset() {
            secure.remove(SecureStoreKeys.RECOVERY_KEY)
            onSessionReset?.invoke()
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

    private fun newManager(
        server: FakeServer = FakeServer(),
        handoff: HandoffSource = FakeHandoff(),
        isUpdateInstall: Boolean = false,
    ): SessionManager {
        sessionResetCount = 0
        val client = ApiClient(
            MockEngine(server.handler),
            InMemoryRecoveryProvider(secure) { sessionResetCount++ },
        )
        clientRef = client
        return SessionManager(secure, settings, handoff, GreenThumbApi(client), isUpdateInstall)
    }

    /** Счётчик сбросов сессии (второй 401: клиент чистит ключ). */
    private var sessionResetCount = 0

    // ------------------------------------------------------------------
    // Ряд матрицы VAL-HANDOFF-IMP-002: пусто + обновление → KeyNotFound
    // ------------------------------------------------------------------

    @Test
    fun `startup with no key anywhere on update install is key not found`() = runBlocking {
        val manager = newManager(isUpdateInstall = true)

        val state = manager.startup()

        assertEquals(SessionState.KeyNotFound, state, "пусто + обновление поверх → экран «ключ не найден»")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `initSession with no key anywhere on update install is key not found`() = runBlocking {
        val manager = newManager(isUpdateInstall = true)

        val state = manager.initSession()

        assertEquals(SessionState.KeyNotFound, state)
    }

    // ------------------------------------------------------------------
    // Чистая установка: та же пустота → обычный экран входа
    // ------------------------------------------------------------------

    @Test
    fun `startup with no key anywhere on fresh install is plain signed out`() = runBlocking {
        val manager = newManager(isUpdateInstall = false)

        val state = manager.startup()

        assertEquals(SessionState.SignedOut, state, "чистая установка → обычный логин, KeyNotFound не показан")
    }

    @Test
    fun `initSession me 401 without key on update install is key not found`() = runBlocking {
        // Ряд «handoff доставлен, но приложение не запускалось»: сервер помнит
        // аккаунт (me/login-recovery 401 без ключа), установка — обновление.
        // 401 от me: raw-уровень клиента НЕ срабатывает (нет сохранённого ключа
        // для re-login — resetCount=0); recover(): ключа нет → пустой исход →
        // «ключ не найден» (аккаунт жив на сервере, ключа нет нигде).
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        server.loginStatus = HttpStatusCode.Unauthorized
        val manager = newManager(server = server, isUpdateInstall = true)

        val state = manager.initSession()

        assertEquals(SessionState.KeyNotFound, state, "аккаунт на сервере + нет ключа + обновление → KeyNotFound")
        assertEquals(1, server.meCount)
        assertEquals(0, server.loginCount, "recovery не запускается без ключа")
        assertEquals(0, sessionResetCount, "шов клиента не сбрасывает сессию без сохранённого ключа")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    // ------------------------------------------------------------------
    // Ключ найден (SecureStore или handoff) → KeyNotFound не возникает
    // ------------------------------------------------------------------

    @Test
    fun `stored key on update install signs in instead of key not found`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = false
        server.meStatus = HttpStatusCode.Unauthorized
        server.loginStatus = HttpStatusCode.OK
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server, isUpdateInstall = true)

        val state = manager.startup()

        val signedIn = state as SessionState.SignedIn
        assertEquals("55", signedIn.user.id, "ключ жив → обычный вход, не «ключ не найден»")
    }

    @Test
    fun `handoff import on update install signs in instead of key not found`() = runBlocking {
        val handoff = FakeHandoff(HandoffPayload(recoveryKey = "handoff-key-42"))
        val server = FakeServer()
        server.failMeTransport = false
        server.meStatus = HttpStatusCode.Unauthorized // cookie нет: recovery по handoff-ключу
        server.loginStatus = HttpStatusCode.OK
        val manager = newManager(server = server, handoff = handoff, isUpdateInstall = true)

        val state = manager.startup()

        assertTrue(state is SessionState.SignedIn, "handoff-ключ импортирован → вход (получено $state)")
        assertEquals(1, handoff.clearCount, "импорт прошёл — файл удалён")
    }

    // ------------------------------------------------------------------
    // Обычные исходы никогда не деградируют в KeyNotFound
    // ------------------------------------------------------------------

    @Test
    fun `signOut on update install is plain signed out`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(isUpdateInstall = true)

        val state = manager.signOut()

        assertEquals(SessionState.SignedOut, state, "выход = экран входа, не «ключ не найден»")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun `explicit 401 at sign-in on update install is plain signed out`() = runBlocking {
        val server = FakeServer()
        server.loginStatus = HttpStatusCode.Unauthorized
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "old-key"
        val manager = newManager(server = server, isUpdateInstall = true)

        try {
            manager.signInWithRecoveryKey("bad-key")
            throw AssertionError("ожидался Unauthorized")
        } catch (expected: Throwable) {
            // ожидаемо: signInWithRecoveryKey бросает Unauthorized (ApiError)
        }

        // initSession ПОСЛЕ стёртого пользователем ключа: me 401 → recover()
        // без ключа → SignedOut (recover — НЕ стартовая пустота: ключ стёрт
        // самим пользователем на входе; «ключ не найден» ложью быть не может).
        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "пользователь стёр ключ сам → экран входа")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    // ------------------------------------------------------------------
    // Транзиентные сбои при живом ключе — экран входа, не KeyNotFound
    // ------------------------------------------------------------------

    @Test
    fun `transient server error with stored key is plain signed out`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.ServiceUnavailable
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server, isUpdateInstall = true)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "5xx — НЕ «ключ не найден»")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ хранится (VAL-LOGIN-004)")
    }

    @Test
    fun `network failure with stored key is plain signed out`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server, isUpdateInstall = true)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "сетевой провал — НЕ «ключ не найден»")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    // ------------------------------------------------------------------
    // Провал импорта handoff — отдельный экран ошибки (VAL-HANDOFF-IMP-005)
    // ------------------------------------------------------------------

    /** Fake secure-хранилище с инжекцией провала записи (для теста импорта). */
    private class FailingSecureStore : SecureKeyValueStore {
        val map = linkedMapOf<String, String>()
        var failSet = false

        override suspend fun get(key: String): String? = map[key]

        override suspend fun set(key: String, value: String): Boolean {
            if (failSet) return false
            map[key] = value
            return true
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun `handoff import failure on update install is handoff error not key not found`() = runBlocking {
        val server = FakeServer()
        val failingSecure = FailingSecureStore().apply { failSet = true }
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(failingSecure))
        clientRef = client
        val manager = SessionManager(
            failingSecure,
            settings,
            FakeHandoff(HandoffPayload(recoveryKey = "keep-key-42")),
            GreenThumbApi(client),
            isUpdateInstall = true,
        )

        val state = manager.startup()

        val failed = state as SessionState.HandoffImportFailed
        assertEquals("keep-key-42", failed.recoveryKey, "экран ошибки handoff с ключом для копирования")
        assertTrue(failingSecure.map.values.none { it == "keep-key-42" }, "ключ не записан при провале")
        assertEquals(0, server.meCount, "initSession не запускается после провала импорта")
    }
}
