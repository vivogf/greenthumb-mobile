@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.PlantRepositoryOpener

/**
 * VAL-I18N-006: смена языка на выбранной вкладке «Профиль» обновляет подписи,
 * но профиль остаётся выбранным — перехода на дашборд и перезапуска сессии нет.
 *
 * Полная поверхность App(): живая сессия (MockEngine: me → 200), стартовая
 * маршрутизация → дашборд, переход на «Профиль», затем смена языка через
 * AppSettings (тот же триггер, что у dev-переключателя InterimProfileScreen и
 * будущего пикера Stage 7). Счётчики сервера — независимые свидетели:
 * me-запрос один на процесс (startup не переигрывается), /api/plants не
 * перезагружается при смене языка (дашборд не перемонтируется).
 *
 * RED-прогон фиксировался временной правкой AppEnvironment (key(customAppLocale)
 * вернулся на место) — профиль-кейс падал на assertExists("tabs/profile")
 * (маршрут сбрасывался на дашборд); дашборд-кейс был зелёным в обоих
 * состояниях. Механика падения «Database is closed» у ранних красных
 * прогонов — двойник opener'а, а не продукт: прод-opener создаёт свежий
 * репозиторий на каждый open(), тест теперь повторяет это.
 */
@OptIn(ExperimentalTestApi::class)
class LocaleNavigationParityTest {

    private var originalLocale: Locale? = null

    /** Активный харнесс; закрывается в конце каждого теста ВНУТРИ runDesktopComposeUiTest. */
    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        // jvm-actual LocalAppLocale мутирует Locale.setDefault — возвращаем исходное.
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика SessionManagerTest/SessionManagerOfflineTest)
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

    /** Настройки с реактивными Flow (App читает их collectAsState'ом). */
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

        private val JSON_HEADERS = io.ktor.http.headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":55,"name":"kmp-val-nav","recovery_key":"key-nav-55",""" +
                """"created_at":"2026-09-21T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> {
                    meCount++
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/plants" -> {
                    plantsCount++
                    respond("[]", HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.NotFound, JSON_HEADERS)
            }
        }
    }

    private class Harness(
        val server: FakeServer,
        val settings: FakeSettings,
        val session: SessionManager,
        val opener: PlantRepositoryOpener,
        val connectivity: Connectivity,
        private val client: ApiClient,
        private val repos: MutableList<PlantRepository>,
        private val dir: java.io.File,
    ) : AutoCloseable {
        override fun close() {
            // Каждый созданный opener'ом репозиторий (идемпотентно: close
            // повторный вызов игнорирует).
            repos.forEach { repo -> runCatching { repo.close() } }
            runCatching { client.close() }
            runCatching { connectivity.close() }
            runCatching { dir.toPath().deleteRecursively() }
        }
    }

    /** Провайдер восстановления поверх двойников (без второго 401-шва M2). */
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
        if (originalLocale == null) originalLocale = Locale.getDefault()
        val dir: File = createTempDirectory(prefix = "gt-locale-nav").toFile()
        val databases = JvmPlantDatabases(dir)
        val server = FakeServer()
        val settings = FakeSettings()
        val secure = FakeSecureStore()
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(secure, settings))
        val api = GreenThumbApi(client)
        val session = SessionManager(secure, settings, NoHandoff(), api)
        // Прод-семантика opener'а (PlantDatabases.openerFor): СВЕЖИЙ
        // репозиторий (новое Room-соединение) на каждый open(userId).
        // DisposableEffect экрана закрывает репозиторий при выходе из
        // композиции; key(locale) пересоздаёт поддерево экрана, следующий
        // open() обязан отдать живой экземпляр — иначе закрытая БД падает
        // в createFlow. Это и есть механика бага VAL-I18N-006: старый
        // AppEnvironment{key} ронял приложение в такой же точке.
        val repos = mutableListOf<PlantRepository>()
        val opener = PlantRepositoryOpener { userId ->
            PlantRepository(
                userId = userId,
                db = databases.open(userId),
                api = api,
                deleteFiles = { id -> databases.delete(id) },
                session = AccountSession(),
            ).also { repo ->
                synchronized(repos) { repos.add(repo) }
            }
        }
        val connectivity = Connectivity(Any())
        return Harness(server, settings, session, opener, connectivity, client, repos, dir)
            .also { harness = it }
    }

    private fun FakeSettings.presetRussian(): Unit = runBlocking {
        setLanguage(AppLanguage.Ru)
        setIntroSeen()
    }

    // ------------------------------------------------------------------
    // Основной кейс VAL-I18N-006: язык меняется на выбранном «Профиле»
    // ------------------------------------------------------------------

    @Test
    fun language_switch_on_selected_profile_keeps_route_and_translates_tabs() {
        val graph = newHarness()
        graph.settings.presetRussian()
        runDesktopComposeUiTest {
            setContent {
                App(graph.session, graph.connectivity, graph.opener, graph.settings)
            }

            // Старт: сессия решена (me → 200), дашборд с вкладками на ru.
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.meCount == 1 }
            waitUntilAtLeastOneExists(hasText("Растения"), TIMEOUT)
            onNodeWithText("Профиль").performClick()
            waitUntilAtLeastOneExists(hasText("tabs/profile"), TIMEOUT)
            onNodeWithText("tabs/dashboard").assertDoesNotExist()
            assertEquals(1, graph.server.meCount, "один me-запрос на старте")

            // Смена языка — тот же триггер, что у переключателя в профиле.
            runBlocking { graph.settings.setLanguage(AppLanguage.En) }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Растения"), TIMEOUT)

            // Профиль остаётся выбранным: редиректа на дашборд нет.
            onNodeWithText("tabs/profile").assertExists()
            onNodeWithText("tabs/dashboard").assertDoesNotExist()
            // Сессия не переигрывается, данные не перезагружаются.
            assertEquals(1, graph.server.meCount, "перезапуска сессии нет")
            assertEquals(1, graph.server.plantsCount, "дашборд не перемонтируется")

            // Цикл обратно (ru) — профиль по-прежнему выбран, подписи переведены.
            runBlocking { graph.settings.setLanguage(AppLanguage.Ru) }
            waitUntilAtLeastOneExists(hasText("Растения"), TIMEOUT)
            onNodeWithText("tabs/profile").assertExists()
            onNodeWithText("tabs/dashboard").assertDoesNotExist()
            assertEquals(1, graph.server.meCount, "перезапуска сессии нет и в обратную сторону")

            // Закрытие харнесса до выхода из композиции: активные Room-флоу
            // снимаются в кадре (runOnIdle), иначе закрытие БД в @AfterTest
            // попадает в живую подписку наблюдения.
            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // На дашборде: вкладки переводятся без перемонтирования данных
    // ------------------------------------------------------------------

    @Test
    fun language_switch_on_dashboard_translates_tabs_without_data_remount() {
        val graph = newHarness()
        graph.settings.presetRussian()
        runDesktopComposeUiTest {
            setContent {
                App(graph.session, graph.connectivity, graph.opener, graph.settings)
            }
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.meCount == 1 }
            waitUntilAtLeastOneExists(hasText("Растения"), TIMEOUT)
            val plantsBefore = graph.server.plantsCount

            runBlocking { graph.settings.setLanguage(AppLanguage.En) }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Растения"), TIMEOUT)

            // Дашборд — та же вкладка, данные не перезагружались, сессия цела.
            assertEquals(1, graph.server.meCount, "перезапуска сессии нет")
            assertEquals(plantsBefore, graph.server.plantsCount, "лишнего refresh нет")

            // Закрытие до выхода: Room-флоу снимаются в кадре (см. первый тест).
            runOnIdle { graph.close() }
        }
    }

    private companion object {
        /** Дедлайн ожиданий теста (мс): бут сессии + пересборка дерева. */
        const val TIMEOUT: Long = 10_000L
    }
}
