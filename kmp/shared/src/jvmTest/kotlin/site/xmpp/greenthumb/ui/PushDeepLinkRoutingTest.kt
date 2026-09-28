@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.AppSettingsKeys
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.ThemePreference
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.PlantRepositoryOpener

/**
 * Deep-link пуша (Stage 9 п.7, фича kmp-push-offline-routing) на fake-SignedIn
 * харнессе полной поверхности App(): `data.plant_id` из launch-intent
 * (эквивалент — параметр [App.launchPlantId], MainActivity извлекает extra)
 *
 * 1. при SignedIn маршрутизируется к plant/{id} — карточка растения
 *    открывается сразу (данные доставляет refresh репозитория, если холодный
 *    старт ушёл на карточку раньше первого sync дашборда);
 * 2. БЕЗ сессии extra не открывает данные и не обходит вход (экран входа,
 *    plants-запросов нет), а переход ОТКЛАДЫВАЕТСЯ до SignedIn: после входа
 *    отложенный id потребляется (отложенный переход).
 */
@OptIn(ExperimentalTestApi::class)
class PushDeepLinkRoutingTest {

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика LocaleNavigationParityTest)
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
        val map = mutableMapOf<String, String>()
        private val languageState = MutableStateFlow<AppLanguage?>(null)
        private val themeState = MutableStateFlow<ThemePreference?>(null)
        private val layoutState = MutableStateFlow<LayoutMode?>(null)
        private val introState = MutableStateFlow(false)
        private val cachedUserState = MutableStateFlow<UserDto?>(null)

        override suspend fun getLanguage(): AppLanguage? =
            map[AppSettingsKeys.LANGUAGE]?.let { AppLanguage.fromWire(it) }

        override suspend fun setLanguage(language: AppLanguage) {
            map[AppSettingsKeys.LANGUAGE] = language.wire
            languageState.value = language
        }

        override suspend fun getLayoutMode(): LayoutMode? =
            map[AppSettingsKeys.LAYOUT_MODE]?.let { LayoutMode.fromWire(it) }

        override suspend fun setLayoutMode(mode: LayoutMode) {
            map[AppSettingsKeys.LAYOUT_MODE] = mode.wire
            layoutState.value = mode
        }

        override suspend fun getTheme(): ThemePreference? =
            map[AppSettingsKeys.THEME]?.let { ThemePreference.fromWire(it) }

        override suspend fun setTheme(theme: ThemePreference) {
            map[AppSettingsKeys.THEME] = theme.wire
            themeState.value = theme
        }

        override suspend fun isIntroSeen(): Boolean =
            map[AppSettingsKeys.INTRO_SEEN] == "1"

        override suspend fun setIntroSeen() {
            map[AppSettingsKeys.INTRO_SEEN] = "1"
            introState.value = true
        }

        override suspend fun getCachedUser(): UserDto? = cachedUserState.value

        override suspend fun setCachedUser(user: UserDto) {
            cachedUserState.value = user
        }

        override suspend fun clearCachedUser() {
            cachedUserState.value = null
        }

        override val language: Flow<AppLanguage?> = languageState
        override val layoutMode: Flow<LayoutMode?> = layoutState
        override val theme: Flow<ThemePreference?> = themeState
        override val introSeen: Flow<Boolean> = introState
        override val cachedUser: Flow<UserDto?> = cachedUserState

        override suspend fun applyLegacyHandoffValues(
            language: AppLanguage?,
            theme: ThemePreference?,
            layoutMode: LayoutMode?,
            introSeen: Boolean?,
        ) {
            language?.let { setLanguage(it) }
            theme?.let { setTheme(it) }
            layoutMode?.let { setLayoutMode(it) }
            if (introSeen == true) setIntroSeen()
        }
    }

    private class NoHandoff : HandoffSource {
        override fun readHandoff(): site.xmpp.greenthumb.core.storage.HandoffPayload? = null

        override fun clearHandoff() {}
    }

    private class FakeServer {
        var meCount = 0
        var plantsCount = 0
        var loginCount = 0

        /** Живая сессия на me (false → 401: SignedOut, ключа нет). */
        var meAuthorized: Boolean = true

        /** Растения, отдаваемые GET /api/plants (для карточки deep-link). */
        var plantsJson: String = "[]"

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":61,"name":"kmp-val-deeplink","recovery_key":"key-deeplink-61",""" +
                """"created_at":"2026-09-28T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> {
                    meCount++
                    if (meAuthorized) {
                        respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                    } else {
                        respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    }
                }
                "/api/plants" -> {
                    plantsCount++
                    respond(plantsJson, HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/login-recovery" -> {
                    loginCount++
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.NotFound, JSON_HEADERS)
            }
        }
    }

    private class Harness(
        val server: FakeServer,
        val settings: FakeSettings,
        val secure: FakeSecureStore,
        val session: SessionManager,
        val opener: PlantRepositoryOpener,
        val connectivity: Connectivity,
        private val client: ApiClient,
        private val repos: MutableList<PlantRepository>,
        private val dir: File,
    ) : AutoCloseable {
        override fun close() {
            repos.forEach { repo -> runCatching { repo.close() } }
            runCatching { client.close() }
            runCatching { connectivity.close() }
            runCatching { dir.toPath().deleteRecursively() }
        }
    }

    private class InMemoryRecoveryProvider(
        private val secure: FakeSecureStore,
        private val settings: FakeSettings,
    ) : SessionRecoveryProvider {
        override suspend fun getRecoveryKey(): String? =
            secure.get(SecureStoreKeys.RECOVERY_KEY)

        override suspend fun onSessionReset() {
            secure.remove(SecureStoreKeys.RECOVERY_KEY)
            settings.clearCachedUser()
        }
    }

    private fun newHarness(): Harness {
        val dir: File = createTempDirectory(prefix = "gt-push-deeplink").toFile()
        val databases = JvmPlantDatabases(dir)
        val server = FakeServer()
        val settings = FakeSettings()
        val secure = FakeSecureStore()
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(secure, settings))
        val api = GreenThumbApi(client)
        val session = SessionManager(secure, settings, NoHandoff(), api)
        val gate = AccountPlantGate(
            openDatabase = { userId -> databases.open(userId) },
            deleteDatabase = { userId -> databases.delete(userId) },
            api = api,
            session = AccountSession(),
        )
        val repos = mutableListOf<PlantRepository>()
        val opener = PlantRepositoryOpener { userId ->
            gate.open(userId).also { repo ->
                synchronized(repos) { if (repos.none { it === repo }) repos.add(repo) }
            }
        }
        val connectivity = Connectivity(Any())
        return Harness(server, settings, secure, session, opener, connectivity, client, repos, dir)
            .also { harness = it }
    }

    /** Растение карточки deep-link (id совпадает с pending plant_id). */
    private fun plantJson(id: String, name: String): String =
        """{"id":"$id","user_id":"61","name":"$name","location":"","photo_url":"",""" +
            """"water_frequency_days":3,"last_watered_date":"2026-09-27","created_at":"2026-09-28T10:00:00.000Z"}"""

    // ------------------------------------------------------------------
    // SignedIn + pending plant_id → карточка plant/{id}
    // ------------------------------------------------------------------

    @Test
    fun pendingPlantId_navigatesToCard_whenSignedIn() {
        val graph = newHarness()
        runBlocking { graph.settings.setLanguage(AppLanguage.En) }
        graph.server.plantsJson = "[${plantJson("plant-9", "Fern")}]"
        // Держатель launch-intent'а (эквивалент MainActivity): id от тапа по
        // пушу пришёл ДО композиции (холодный старт) — App получает его сразу.
        var pendingPlantId by mutableStateOf<String?>("plant-9")
        var consumeCalls = 0
        runDesktopComposeUiTest {
            setContent {
                App(
                    graph.session,
                    graph.connectivity,
                    graph.opener,
                    graph.settings,
                    PushTokens(),
                    launchPlantId = pendingPlantId,
                    onLaunchPlantIdConsumed = {
                        consumeCalls++
                        pendingPlantId = null
                    },
                )
            }

            // fake-SignedIn (me → 200) → переход выполняется сразу: карточка
            // «Fern» (id = plant-9). Refresh в пути потребления кладёт растение
            // в Room — наблюдение карточки реактивно меняет Loading/NotFound →
            // данные.
            waitUntil(timeoutMillis = TIMEOUT) { consumeCalls >= 1 }
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            // Ровно один переход: повторных навигаций нет (id потреблён).
            assertEquals(1, consumeCalls, "id потреблён однократно")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Без сессии: extra не открывает данные и не обходит вход; переход
    // откладывается до SignedIn
    // ------------------------------------------------------------------

    @Test
    fun pendingPlantId_deferredUntilSignedIn_noBypassWhenSignedOut() {
        val graph = newHarness()
        runBlocking {
            graph.settings.setLanguage(AppLanguage.En)
            graph.settings.setIntroSeen()
        }
        // Нет сессии: me → 401, ключа нет → SignedOut (экран входа).
        graph.server.meAuthorized = false
        graph.server.plantsJson = "[${plantJson("plant-9", "Fern")}]"
        var pendingPlantId by mutableStateOf<String?>("plant-9")
        var consumeCalls = 0
        runDesktopComposeUiTest {
            setContent {
                App(
                    graph.session,
                    graph.connectivity,
                    graph.opener,
                    graph.settings,
                    PushTokens(),
                    launchPlantId = pendingPlantId,
                    onLaunchPlantIdConsumed = {
                        consumeCalls++
                        pendingPlantId = null
                    },
                )
            }

            // Нет сессии (me → 401, ключа нет → SignedOut): экран входа, БЕЗ
            // карточки и БЕЗ данных; id не потреблён (переход отложен).
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.meCount >= 1 }
            onNodeWithText("Fern").assertDoesNotExist()
            assertEquals(0, graph.server.plantsCount, "без сессии данные не открываются")
            assertEquals(0, consumeCalls, "id ждёт входа")

            // Вход (тот же путь, что логин-экран): отложенный id потребляется
            // после SignedIn — переход к карточке выполняется.
            runBlocking { graph.session.signInWithRecoveryKey("key-deeplink-61") }
            waitUntil(timeoutMillis = TIMEOUT) { consumeCalls >= 1 }
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            assertEquals(1, consumeCalls, "отложенный переход ровно один")
            assertTrue(graph.server.plantsCount >= 1, "после входа данные доступны")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Отсутствие pending id — обычный старт без переходов
    // ------------------------------------------------------------------

    @Test
    fun noPendingPlantId_staysOnDashboard() {
        val graph = newHarness()
        runBlocking { graph.settings.setLanguage(AppLanguage.En) }
        graph.server.plantsJson = "[${plantJson("plant-9", "Fern")}]"
        var consumeCalls = 0
        runDesktopComposeUiTest {
            setContent {
                App(
                    graph.session,
                    graph.connectivity,
                    graph.opener,
                    graph.settings,
                    PushTokens(),
                    launchPlantId = null,
                    onLaunchPlantIdConsumed = { consumeCalls++ },
                )
            }

            // Обычный старт на дашборде: consume не вызывается, карточка не
            // открывается (растение видно только в списке дашборда).
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.meCount == 1 }
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            assertEquals(0, consumeCalls, "без pending id переходов нет")

            runOnIdle { graph.close() }
        }
    }

    private companion object {
        /** Дедлайн ожиданий теста (мс): бут сессии + пересборка дерева. */
        const val TIMEOUT: Long = 10_000L
    }
}
