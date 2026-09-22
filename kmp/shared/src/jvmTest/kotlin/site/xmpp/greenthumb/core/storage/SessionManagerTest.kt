package site.xmpp.greenthumb.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 3 п.4 (фича kmp-startup-session): jvmTest стартовой последовательности
 * сессии и правил ключа.
 *
 * Ожидаемые поведения (features.json kmp-startup-session):
 *  - провал записи ключа/перечитывания → handoff-файл остаётся на диске,
 *    [SessionResult.Failed] с ключом для копирования (VAL-HANDOFF-IMP-005);
 *  - успешный импорт → ключ записан и перечитан, настройки применены,
 *    clearHandoff только после этого (VAL-HANDOFF-IMP-001, jvm-половина);
 *  - initSession 3 шага RN `contexts/AuthContext.tsx:53-91`: me 200 → вход;
 *    401 → recovery: 401 recovery чистит ключ, транзиентные — хранят
 *    (VAL-LOGIN-004);
 *  - cached_user пишется после успешного me/логина.
 *
 * Handoff-файл в jvmTest — файловый двойник [FileHandoff] (jvm-actual
 * [LegacyHandoff] всегда null/no-op: desktop-харнесс Expo-сборкой не был);
 * физическая проверка «файл остаётся на диске» через тот же parse/clear
 * контракт, что у android-actual.
 */
@OptIn(ExperimentalPathApi::class)
class SessionManagerTest {

    // ------------------------------------------------------------------
    // Двойники
    // ------------------------------------------------------------------

    /** Fake secure-хранилище: полная механика + инжекция провалов записи. */
    private class FakeSecureStore : SecureKeyValueStore {
        val map = linkedMapOf<String, String>()
        var failSet = false
        var rewriteOnSet = false
        var setCount = 0

        override suspend fun get(key: String): String? = map[key]

        override suspend fun set(key: String, value: String): Boolean {
            setCount++
            if (failSet) return false
            map[key] = if (rewriteOnSet) "$value-corrupted" else value
            return true
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    /** Fake настроек: in-memory; запоминает применённые handoff-значения. */
    private class FakeSettings : AppPreferencesStore {
        val values = mutableMapOf<String, String>()
        var storedUser: UserDto? = null
        var lastApplied: String? = null
        var failApply = false

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
            if (failApply) throw IllegalStateException("injected apply failure")
            language?.let { values[AppSettingsKeys.LANGUAGE] = it.wire }
            theme?.let { values[AppSettingsKeys.THEME] = it.wire }
            layoutMode?.let { values[AppSettingsKeys.LAYOUT_MODE] = it.wire }
            if (introSeen == true) values[AppSettingsKeys.INTRO_SEEN] = "1"
            lastApplied =
                "lang=${language?.wire ?: "-"} theme=${theme?.wire ?: "-"} " +
                    "layout=${layoutMode?.wire ?: "-"} intro=$introSeen"
        }
    }

    /**
     * Файловый двойник handoff (контракт android-actual: read = parse файла,
     * clear = delete файла). Даёт jvm-тесту физическую проверку
     * «файл остаётся/удаляется».
     */
    private class FileHandoff(
        private val file: java.nio.file.Path,
    ) : HandoffSource {
        var clearCount = 0

        override fun readHandoff(): HandoffPayload? {
            if (!file.exists()) return null
            return HandoffPayload.parse(file.readText())
        }

        override fun clearHandoff() {
            clearCount++
            file.toFile().delete()
        }
    }

    /**
     * «Сервер» на MockEngine: настраиваемые исходы me/login-recovery/logout,
     * счётчики вызовов. Тела — формат контракта бэка; id числом (провод
     * исторический — StringOrNumberAsStringSerializer).
     */
    private class FakeServer {
        var meCount = 0
        var loginCount = 0
        var logoutCount = 0
        var meStatus: HttpStatusCode = HttpStatusCode.OK
        var loginStatus: HttpStatusCode = HttpStatusCode.OK
        var failMeTransport = false
        var failLoginTransport = false

        /** JSON-тело auth-эндпоинтов фикстуры (id числом, ключ «key-<id>»). */
        private fun userJson(): String =
            """{"user":{"id":55,"name":"kmp-val","recovery_key":"key-55","created_at":"2026-09-21T10:00:00.000Z"}}"""

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        /** Успешный login-recovery «устанавливает cookie» → последующий me 200. */
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
                    if (failLoginTransport) throw IllegalStateException("Connection reset")
                    if (loginStatus == HttpStatusCode.OK) loginSucceeded = true
                    respond(userJson(), loginStatus, JSON_HEADERS)
                }
                "/api/auth/create-anonymous" -> {
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/logout" -> {
                    logoutCount++
                    respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                }
                else -> respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS)
            }
        }
    }

    // ------------------------------------------------------------------
    // Фикстура
    // ------------------------------------------------------------------

    private val dir = createTempDirectory(prefix = "gt-session-mgr")
    private val handoffFile = dir / "gt-handoff.json"

    private val secure = FakeSecureStore()
    private val settings = FakeSettings()
    private val jvmStores = mutableListOf<JvmSecureStoreStorage>()

    /** Клиент текущего менеджера (закрывается в cleanup). */
    private var clientRef: site.xmpp.greenthumb.core.network.ApiClient? = null

    @AfterTest
    fun cleanup() {
        clientRef?.close()
        jvmStores.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** Ссылка на in-memory ключ менеджера (подставляется после создания). */
    private var sessionKeyProvider: () -> String? = { null }

    private fun newManager(
        handoff: HandoffSource = FileHandoff(handoffFile),
        server: FakeServer = FakeServer(),
    ): SessionManager {
        val engine = MockEngine(server.handler)
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            engine,
            InMemoryRecoveryProvider(secure, settings) { sessionKeyProvider() },
        )
        clientRef = client
        return SessionManager(secure, settings, handoff, GreenThumbApi(client))
    }

    /** Провайдер над инжектированными двойниками (ключ живёт в secure.map). */
    private class InMemoryRecoveryProvider(
        private val secure: SecureKeyValueStore,
        private val settings: AppPreferencesStore,
        private val readInMemoryKey: () -> String?,
    ) : site.xmpp.greenthumb.core.network.SessionRecoveryProvider {
        private val writeMutex = Mutex()

        override suspend fun getRecoveryKey(): String? =
            secure.get(SecureStoreKeys.RECOVERY_KEY) ?: readInMemoryKey()

        override suspend fun onSessionReset() {
            writeMutex.withLock {
                secure.remove(SecureStoreKeys.RECOVERY_KEY)
                settings.clearCachedUser()
            }
        }
    }

    private fun writeHandoffFile(
        recoveryKey: String,
        language: String? = "ru",
        theme: String? = "dark",
        layout: String? = "card",
        intro: Boolean = true,
    ) {
        val settingsPart =
            "\"language\":${jsonOrNull(language)},\"theme\":${jsonOrNull(theme)}," +
                "\"layout_mode\":${jsonOrNull(layout)},\"intro_seen\":$intro"
        handoffFile.writeText("""{"v":1,"recovery_key":"$recoveryKey",$settingsPart}""")
    }

    private fun jsonOrNull(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private fun userDto(id: String, recoveryKey: String): UserDto =
        UserDto(id = id, recoveryKey = recoveryKey, createdAt = "2026-09-21T10:00:00.000Z")

    // ------------------------------------------------------------------
    // Импорт handoff: happy path (VAL-HANDOFF-IMP-001, jvm-половина)
    // ------------------------------------------------------------------

    @Test
    fun `import writes key applies settings verifies and clears`() = runBlocking {
        val manager = newManager()
        writeHandoffFile("handoff-key-42")

        val result = manager.importHandoff()

        assertEquals(SessionResult.Imported, result, "успех → Imported")
        assertEquals("handoff-key-42", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ записан в secure")
        assertEquals(
            "lang=ru theme=dark layout=card intro=true",
            settings.lastApplied,
            "настройки из handoff применены",
        )
        assertEquals(1, secure.setCount, "запись ключа ровно одна")
        assertEquals(1, (manager.handoff as FileHandoff).clearCount, "clearHandoff ровно один, после верификации")
        assertFalse(handoffFile.exists(), "файл удалён после проверенной записи")
    }

    @Test
    fun `import without handoff is a no-op`() = runBlocking {
        val manager = newManager()

        val result = manager.importHandoff()

        assertEquals(SessionResult.NoHandoff, result)
        assertEquals(0, secure.setCount, "без handoff запись ключа не делается")
        assertNull(settings.lastApplied)
        assertFalse(handoffFile.exists())
    }

    @Test
    fun `garbage handoff file is absent and stays on disk`() = runBlocking {
        val manager = newManager()
        handoffFile.writeText("not-json-at-all")

        val result = manager.importHandoff()

        assertEquals(SessionResult.NoHandoff, result, "мусор = «handoff отсутствует» (VAL-HANDOFF-IMP-004)")
        assertEquals(0, secure.setCount)
        assertTrue(handoffFile.exists(), "мусорный файл не удаляется — потенциально единственная копия ключа")
    }

    @Test
    fun `import drops stale cached user`() = runBlocking {
        settings.storedUser = userDto("777", "old-key")
        val manager = newManager()
        writeHandoffFile("handoff-key-42")

        val result = manager.importHandoff()

        assertEquals(SessionResult.Imported, result)
        assertNull(settings.getCachedUser(), "cached_user прошлого аккаунта сброшен при импорте нового ключа")
    }

    // ------------------------------------------------------------------
    // Провал записи / перечитывания / настроек: файл остаётся, ключ для
    // копирования (VAL-HANDOFF-IMP-005)
    // ------------------------------------------------------------------

    @Test
    fun `import with set failure keeps file and exposes key`() = runBlocking {
        val manager = newManager()
        writeHandoffFile("keep-key-42")
        secure.failSet = true

        val result = manager.importHandoff()

        val failure = assertFailed(result)
        assertEquals("keep-key-42", failure.recoveryKey, "ключ доступен для копирования на экране ошибки")
        assertTrue(handoffFile.exists(), "файл остаётся на диске при провале записи")
        assertEquals(0, (manager.handoff as FileHandoff).clearCount, "clearHandoff НЕ вызван")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun `import with settings apply failure keeps file and exposes key`() = runBlocking {
        val manager = newManager()
        writeHandoffFile("keep-key-42")
        settings.failApply = true

        val result = manager.importHandoff()

        val failure = assertFailed(result)
        assertEquals("keep-key-42", failure.recoveryKey)
        assertTrue(handoffFile.exists())
        assertEquals(0, (manager.handoff as FileHandoff).clearCount)
    }

    @Test
    fun `import with re-read mismatch keeps file and exposes key`() = runBlocking {
        val manager = newManager()
        writeHandoffFile("mismatch-key-42")
        secure.rewriteOnSet = true // перечитанный ключ ≠ записанный → отказ от clearHandoff

        val result = manager.importHandoff()

        val failure = assertFailed(result)
        assertEquals("mismatch-key-42", failure.recoveryKey)
        assertTrue(handoffFile.exists())
        assertEquals(0, (manager.handoff as FileHandoff).clearCount)
    }

    @Test
    fun `import with real jvm storage failure keeps file`() = runBlocking {
        // Реальный jvm-контур: настоящий AES-хранилище + read-only каталог
        // (приём из SecureStoreJvmTest — инжекция провала записи).
        val storageDir = createTempDirectory(prefix = "gt-session-real")
        val store = JvmSecureStoreStorage(storageDir.toFile())
        jvmStores.add(store)
        assertTrue(store.set("warmup", "x"), "прогрев: файл ключа AES создан")

        val engine = MockEngine(FakeServer().handler)
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            engine,
            InMemoryRecoveryProvider(secure, settings) { sessionKeyProvider() },
        )
        clientRef = client
        val manager = SessionManager(store, settings, FileHandoff(handoffFile), GreenThumbApi(client))
        writeHandoffFile("real-key-42")
        storageDir.toFile().setWritable(false)
        try {
            val result = manager.importHandoff()

            val failure = assertFailed(result)
            assertEquals("real-key-42", failure.recoveryKey)
            assertTrue(handoffFile.exists(), "файл не удалён при провале записи реального хранилища")
            assertNull(store.get(SecureStoreKeys.RECOVERY_KEY))
        } finally {
            storageDir.toFile().setWritable(true)
            storageDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // startup(): импорт + initSession как единая стартовая последовательность
    // ------------------------------------------------------------------

    @Test
    fun `startup with write failure shows handoff error and skips initSession`() = runBlocking {
        val server = FakeServer()
        secure.failSet = true
        writeHandoffFile("keep-key-42")
        val manager = newManager(server = server)

        val state = manager.startup()

        assertTrue(state is SessionState.HandoffImportFailed, "экран ошибки handoff, получено $state")
        val failedState = state as SessionState.HandoffImportFailed
        assertEquals("keep-key-42", failedState.recoveryKey, "ключ на экране ошибки для копирования")
        assertTrue(handoffFile.exists())
        assertEquals(0, server.meCount, "initSession не запускается после провала импорта")
    }

    @Test
    fun `startup imports handoff and signs in`() = runBlocking {
        val server = FakeServer()
        writeHandoffFile("handoff-key-42")
        val manager = newManager(server = server)

        val state = manager.startup()

        // Импорт сохранил handoff-ключ; initSession: me 401 (cookie нет) → recovery
        // ключом (сервер отвечает 200 + cookie) → повтор me уже 200 → вход.
        val signedIn = state as SessionState.SignedIn
        assertEquals("55", signedIn.user.id)
        assertEquals("key-55", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ сервера сохранён вместо handoff-ключа")
        assertEquals(1, (manager.handoff as FileHandoff).clearCount)
        assertFalse(handoffFile.exists())
    }

    // ------------------------------------------------------------------
    // initSession: 3 шага (VAL-LOGIN-004; contexts/AuthContext.tsx:53-91)
    // ------------------------------------------------------------------

    @Test
    fun `initSession me 200 signs in and writes cached user`() = runBlocking {
        val server = FakeServer()
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        val signedIn = assertSignedIn(state)
        assertEquals("55", signedIn.user.id)
        assertEquals("key-55", settings.getCachedUser()!!.recoveryKey, "cached_user записан после успешного me")
        assertEquals(1, server.meCount, "recovery не запускался")
        assertEquals(0, server.loginCount)
    }

    @Test
    fun `initSession me 401 recovers with stored key`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        val signedIn = assertSignedIn(state)
        assertEquals("55", signedIn.user.id)
        assertEquals(1, server.loginCount, "recovery запущен ровно один раз")
        assertEquals("key-55", settings.getCachedUser()!!.recoveryKey, "cached_user записан после recovery")
        assertEquals("key-55", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ сервера обновлён в хранилище")
    }

    @Test
    fun `initSession me 401 recovery 401 clears key and cached user`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        server.loginStatus = HttpStatusCode.Unauthorized
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = userDto("55", "stored-key")
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state, "экран входа")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "явный 401 recovery чистит ключ (VAL-LOGIN-004)")
        assertNull(settings.getCachedUser(), "cached_user сброшен вместе с ключом")
        assertEquals(1, server.meCount)
        assertEquals(1, server.loginCount)
    }

    @Test
    fun `initSession me 429 keeps key without recovery`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.TooManyRequests
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "транзиент (429) ключ сохраняет")
        assertNull(settings.getCachedUser())
        assertEquals(1, server.meCount)
        assertEquals(0, server.loginCount, "recovery не запускается на 429")
    }

    @Test
    fun `initSession me 500 keeps key`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.ServiceUnavailable
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "5xx ключ сохраняет")
        assertEquals(0, server.loginCount)
    }

    @Test
    fun `initSession network error keeps key`() = runBlocking {
        val server = FakeServer()
        server.failMeTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "сетевой провал ключ сохраняет")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `initSession timeout keeps key`() = runBlocking {
        val engine = MockEngine { _ ->
            throw io.ktor.client.plugins.HttpRequestTimeoutException("/api/auth/me", 10_000L)
        }
        val client = site.xmpp.greenthumb.core.network.ApiClient(engine, InMemoryRecoveryProvider(secure, settings) { sessionKeyProvider() })
        clientRef = client
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = SessionManager(secure, settings, FileHandoff(handoffFile), GreenThumbApi(client))

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "таймаут ключ сохраняет")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `initSession me 401 recovery transient keeps key`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        server.loginStatus = HttpStatusCode.TooManyRequests
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "транзиентный сбой recovery ключ сохраняет")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `initSession me 401 recovery network error keeps key`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        server.failLoginTransport = true
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "сетевой сбой recovery ключ сохраняет")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `initSession without key does not attempt recovery`() = runBlocking {
        val server = FakeServer()
        server.meStatus = HttpStatusCode.Unauthorized
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertEquals(SessionState.SignedOut, state)
        assertEquals(1, server.meCount)
        assertEquals(0, server.loginCount, "без ключа recovery не запускается")
    }

    @Test
    fun `initSession me 200 without key signs in`() = runBlocking {
        val server = FakeServer()
        val manager = newManager(server = server)

        val state = manager.initSession()

        assertSignedIn(state)
        assertEquals(1, server.meCount)
        assertEquals(0, server.loginCount)
    }

    // ------------------------------------------------------------------
    // signOut
    // ------------------------------------------------------------------

    @Test
    fun `signOut clears key cached user handoff keeps preferences`() = runBlocking {
        val server = FakeServer()
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = userDto("55", "stored-key")
        settings.values[AppSettingsKeys.LANGUAGE] = "ru"
        settings.values[AppSettingsKeys.THEME] = "dark"
        settings.values[AppSettingsKeys.LAYOUT_MODE] = "grid"
        settings.values[AppSettingsKeys.INTRO_SEEN] = "1"
        handoffFile.writeText("""{"v":1,"recovery_key":"stale","language":"ru"}""")
        val handoff = FileHandoff(handoffFile)
        val manager = newManager(handoff = handoff, server = server)

        val state = manager.signOut()

        assertEquals(SessionState.SignedOut, state)
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ стёрт")
        assertNull(settings.getCachedUser(), "cached_user стёрт")
        assertEquals(1, handoff.clearCount, "handoff-файл стёрт (Stage 0 signOut)")
        assertFalse(handoffFile.exists())
        assertEquals("ru", settings.values[AppSettingsKeys.LANGUAGE], "предпочтения не тронуты")
        assertEquals("dark", settings.values[AppSettingsKeys.THEME])
        assertEquals("grid", settings.values[AppSettingsKeys.LAYOUT_MODE])
        assertEquals("1", settings.values[AppSettingsKeys.INTRO_SEEN])
        assertEquals(1, server.logoutCount, "server logout вызван")
    }

    @Test
    fun `signOut with network error still clears locally`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        settings.storedUser = userDto("55", "stored-key")
        handoffFile.writeText("""{"v":1,"recovery_key":"stale","language":"ru"}""")
        val handoff = FileHandoff(handoffFile)
        val networkEngine = MockEngine { _ -> throw IllegalStateException("Connection reset") }
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            networkEngine,
            InMemoryRecoveryProvider(secure, settings) { sessionKeyProvider() },
        )
        clientRef = client
        val manager = SessionManager(secure, settings, handoff, GreenThumbApi(client))

        val state = manager.signOut()

        assertEquals(SessionState.SignedOut, state, "локальная чистка не зависит от сети")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertNull(settings.getCachedUser())
        assertFalse(handoffFile.exists())
    }

    // ------------------------------------------------------------------
    // Вход ключом и анонимное создание — обе дороги пишут ключ + cached_user
    // ------------------------------------------------------------------

    @Test
    fun `signInWithRecoveryKey success persists server key and cached user`() = runBlocking {
        val manager = newManager()

        val state = manager.signInWithRecoveryKey("typed-key")

        assertSignedIn(state)
        assertEquals("key-55", secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ из ответа сервера сохранён")
        assertEquals("key-55", settings.getCachedUser()!!.recoveryKey, "cached_user с актуальным ключом")
    }

    @Test
    fun `signInWithRecoveryKey 401 clears stored key`() = runBlocking {
        val server = FakeServer()
        server.loginStatus = HttpStatusCode.Unauthorized
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "old-key"
        settings.storedUser = userDto("55", "old-key")
        val manager = newManager(server = server)

        val error = expectApiError { manager.signInWithRecoveryKey("bad-key") }

        assertEquals("Unauthorized", error.message, "явный 401 на вход ключом")
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "401 чистит сохранённый ключ (VAL-LOGIN-004)")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `signInWithRecoveryKey transient error keeps stored key`() = runBlocking {
        val server = FakeServer()
        server.loginStatus = HttpStatusCode.TooManyRequests
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "stored-key"
        val manager = newManager(server = server)

        val error = expectApiError { manager.signInWithRecoveryKey("typed-key") }

        assertEquals(429, (error as site.xmpp.greenthumb.core.network.ApiError.Client).status, "транзиентный 429 на вход ключом")
        assertEquals("stored-key", secure.get(SecureStoreKeys.RECOVERY_KEY), "транзиент ключ сохраняет (VAL-LOGIN-004)")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `createAnonymous persists server key and cached user`() = runBlocking {
        val manager = newManager()

        val state = manager.createAnonymousAccount()

        assertSignedIn(state)
        assertEquals("key-55", secure.get(SecureStoreKeys.RECOVERY_KEY))
        assertEquals("key-55", settings.getCachedUser()!!.recoveryKey)
    }

    // ------------------------------------------------------------------
    // onAuthError: runtime-шов M2 (VAL-LOGIN-004 в рантайме)
    // ------------------------------------------------------------------

    @Test
    fun `onAuthError 401 clears key and cached user`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "key-to-clear"
        settings.storedUser = userDto("55", "key-to-clear")
        val manager = newManager()

        manager.onAuthError(ApiError.Unauthorized)

        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "явный 401 чистит ключ")
        assertNull(settings.getCachedUser())
    }

    @Test
    fun `onAuthError transient errors keep key and cached user`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = "key-to-keep"
        settings.storedUser = userDto("55", "key-to-keep")
        val manager = newManager()

        manager.onAuthError(ApiError.Server(503, "quota"))
        manager.onAuthError(ApiError.Timeout)
        manager.onAuthError(ApiError.Network)
        manager.onAuthError(ApiError.Client(429, "rate"))

        assertEquals("key-to-keep", secure.get(SecureStoreKeys.RECOVERY_KEY), "429/5xx/сеть/таймаут ключ сохраняют")
        assertEquals("55", settings.getCachedUser()!!.id, "cached_user не тронут транзиентами")
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private fun assertFailed(result: SessionResult): SessionResult.Failed {
        if (result !is SessionResult.Failed) {
            throw AssertionError("ожидался Failed, получено ${result::class.simpleName}")
        }
        return result
    }

    private fun assertSignedIn(state: SessionState): SessionState.SignedIn {
        if (state !is SessionState.SignedIn) {
            throw AssertionError("ожидался SignedIn, получено ${state::class.simpleName}")
        }
        return state
    }

    /** Локальный suspend-хелпер: ловит ApiError и возвращает его. */
    private suspend fun expectApiError(block: suspend () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiError) {
            return e
        } catch (e: Throwable) {
            throw AssertionError("Ожидался ApiError, получен ${e::class.simpleName}: ${e.message}", e)
        }
        throw AssertionError("Ожидался ApiError, но вызов прошёл успешно")
    }
}
