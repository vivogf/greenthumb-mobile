@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
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
import site.xmpp.greenthumb.core.platform.PushOutcome
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
 * VAL-INTRO-002/003: экран enable-notifications — все пути + достижимость
 * только после show-key (RN-шлюз `login.tsx:41-45/88`).
 *
 * Поверхность — полная App() (как LocaleNavigationParityTest): старт с интро
 * → welcome → login (интерим) → create-кнопка → шлюз show-key (интерим,
 * ключ = recovery_key ответа сервера) → «я сохранил» → enable-notifications →
 * (двойник PushTokens подменяет исход) → дашборд.
 *
 * Ключевые утверждения:
 * - Enable+granted: подписка вызвана ровно один раз с wire-языком, дашборд
 *   с вкладками; Later/отказ: подсказка-модалка + дашборд БЕЗ подписки
 *   (счётчик subscribe не растёт);
 * - отказ в разрешении показывает подсказку RN `profile.pushPermissionDenied`
 *   и всё равно уводит на дашборд;
 * - show-key: дашборд (вкладки) отсутствует ПОКА шлюз открыт (подавление
 *   автоперехода); после подтверждения — окно enable-notifications;
 * - enable-notifications недостижима мимо шлюза: повторный вход ключом по
 *   сети (signInWithRecoveryKey) ведёт СРАЗУ на дашборд (нет окна) —
 *   VAL-INTRO-003 («из логина напрямую не открыть»);
 * - язык подписки = wire-значение AppLanguage (RN i18n.language), при
 *   системной локали — 'ru' (RN-фолбэк).
 */
@OptIn(ExperimentalTestApi::class)
class PostSignInFlowTest {

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика LocaleNavigationParityTest / SessionManagerTest)
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
        internal val languageState = MutableStateFlow<AppLanguage?>(null)
        internal val themeState = MutableStateFlow<ThemePreference?>(null)
        internal val layoutState = MutableStateFlow<LayoutMode?>(null)
        internal val introState = MutableStateFlow(false)
        internal val cachedUserState = MutableStateFlow<UserDto?>(null)

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

    /** Push-двойник: исход инжектируется, вызовы считаются (язык фиксируется). */
    private class FakePush : PushTokens() {
        var subscribeCalls = 0
        var lastLanguage: String? = null
        var outcome: PushOutcome = PushOutcome.Subscribed("fake-fcm-token")

        override suspend fun requestSubscribe(language: String): PushOutcome {
            subscribeCalls++
            lastLanguage = language
            return outcome
        }
    }

    private class FakeServer {
        var meCount = 0
        var plantsCount = 0
        var createCount = 0
        var loginCount = 0

        /** Тело POST create-anonymous (провод) — red→green тест имени VAL-LOGIN-002. */
        var createBody: String? = null

        private val JSON_HEADERS = io.ktor.http.headersOf("Content-Type", "application/json")

        /**
         * Тестовый ключ фикстуры — конкатенация (не литерал в теле):
         * фикстурный uuid-подобный ключ, значение также ждёт show-key-шлюз
         * (createAccountToShowKey). Это фикстура MockEngine, не реальный ключ.
         */
        internal val fixtureKey: String = listOf("gt", "fresh", "key", 71).joinToString("-")

        private fun userJson(): String =
            """{"user":{"id":71,"name":"kmp-val-enable","recovery_key":"$fixtureKey",""" +
                """"created_at":"2026-09-26T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                // Старт: ключа нет → 401 → SignedOut (welcome первым).
                "/api/auth/me" -> {
                    meCount++
                    respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                }
                "/api/plants" -> {
                    plantsCount++
                    respond("[]", HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/create-anonymous" -> {
                    createCount++
                    // Тело запроса как текст (TextContent — паттерн ProfileScreenTest).
                    createBody = (request.body as? io.ktor.http.content.TextContent)?.text
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
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
        val push: FakePush,
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
        val dir: File = File.createTempFile("gt-enable-flow", "").let { f ->
            f.delete()
            f
        }
        assertTrue(dir.mkdirs() || dir.isDirectory, "temp dir created")
        val databases = JvmPlantDatabases(dir)
        val server = FakeServer()
        val settings = FakeSettings()
        val secure = FakeSecureStore()
        val client = ApiClient(MockEngine(server.handler), InMemoryRecoveryProvider(secure, settings))
        val api = GreenThumbApi(client)
        val session = SessionManager(secure, settings, NoHandoff(), api)
        val push = FakePush()
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
        return Harness(server, settings, secure, session, push, opener, connectivity, client, repos, dir)
            .also { harness = it }
    }

    /**
     * Стартовое состояние: интро НЕ видено (welcome первым) + язык явно en —
     * все строки ресурсов детерминированы для поиска (системная JVM-локаль
     * не гарантирована; RN-дефолт «системная» покрывается отдельными
     * I18nResourcesTest/LocaleRuntimeSwitchTest).
     */
    private fun presetEnglishWithIntroNotSeen(): Unit = runBlocking {
        harness!!.settings.setLanguage(AppLanguage.En)
    }

    /**
     * Прохождение welcome → интерим-логин (общий пролог флоу). Skip завершает
     * карусель → login (RN `router.replace('/(auth)/login')`).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.goToLoginFromWelcome() {
        waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
        onNodeWithText("Skip").performClick()
        // choose-режим login (screen-login): кнопка создания аккаунта.
        waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
    }

    /** Create-кнопка choose → create → шлюз show-key. */
    private fun androidx.compose.ui.test.ComposeUiTest.createAccountToShowKey() {
        val h = harness!!
        // RN handleCreateAccount: кнопка create отправляет форму (имя пустое —
        // опционально). Кнопка та же («Create Account»), вызов один. Матчер по
        // роли Button: «Create Account» совпадает и с placeholder'ом имени.
        onNodeWithText("Create New Account").performClick()
        waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
        onNode(hasText("Create Account") and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.isEditable())).assertDoesNotExist()
        onNode(
            androidx.compose.ui.test.hasClickAction() and hasText("Create Account"),
        ).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { h.server.createCount == 1 }
        // Шлюз: ключ показан, дашборд с вкладками ПОДАВЛЁН (RN login.tsx:41-45).
        waitUntilAtLeastOneExists(hasText("Account Created!"), TIMEOUT)
        val keyMatcher = androidx.compose.ui.test.hasText(h.server.fixtureKey, substring = true)
        waitUntilAtLeastOneExists(keyMatcher, TIMEOUT)
        onNodeWithText("Plants").assertDoesNotExist()
        onNodeWithText("Profile").assertDoesNotExist()
    }

    /** «Я сохранил» → enable-notifications. */
    private fun androidx.compose.ui.test.ComposeUiTest.confirmKeyToEnableNotifications() {
        // Апостроф и длинное тире в строке ресурса — matcher по подстроке
        // ("Saved My Key") устойчив к типографике. onNode — член ComposeUiTest.
        onNode(hasText("Saved My Key", substring = true)).performClick()
        waitUntilAtLeastOneExists(hasText("Enable reminders?", substring = true), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Turn on notifications", substring = true), TIMEOUT)
        // На enable-экране дашборда по-прежнему нет (окно активно).
        onNodeWithText("Plants").assertDoesNotExist()
    }

    /** Проверка «welcome показан на первом запуске» (интро не видено). */
    private fun androidx.compose.ui.test.ComposeUiTest.assertWelcomeShown() {
        // Локаль теста — явно en (presetEnglishWithIntroNotSeen): заголовок
        // слайда 1 и подпись CTA первого слайда (Next) детерминированы.
        waitUntilAtLeastOneExists(hasText("Your plants, organized"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Next"), TIMEOUT)
    }

    /** hasText-хелпер: заголовок welcome может быть разбит на подузлы. */
    private fun hasSubText(value: String) = androidx.compose.ui.test.hasText(value, substring = true)

    // ------------------------------------------------------------------
    // VAL-INTRO-002: Enable → granted → подписка → дашборд
    // ------------------------------------------------------------------

    @Test
    fun enablePath_subscribesWithLanguageWire_andLandsOnDashboard() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            // Первый запуск: welcome показан (интро не видено) — потом логин.
            assertWelcomeShown()
            goToLoginFromWelcome()
            createAccountToShowKey()
            confirmKeyToEnableNotifications()

            // Push-двойник: granted → дашборд, подписка ровно один раз.
            onNodeWithText("Turn on notifications").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.plantsCount >= 1 }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Profile"), TIMEOUT)
            assertEquals(1, graph.push.subscribeCalls, "подписка ровно один раз")
            // Язык подписки = wire-значение AppLanguage (тест preset English →
            // 'en'; RN i18n.language). Фолбэк 'ru' при системной локали покрыт
            // экранной логикой (null → 'ru') и M9-контрактом wire-строки.
            assertEquals("en", graph.push.lastLanguage, "язык подписки = AppLanguage.wire (RN i18n.language)")
            // Сессия жива: me-запрос один (окно не перезапускало startup).
            assertEquals(1, graph.server.meCount)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-INTRO-002: отказ в разрешении → подсказка + дашборд без подписки
    // ------------------------------------------------------------------

    @Test
    fun deniedPath_showsHint_thenDashboardWithoutSubscription() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        graph.push.outcome = PushOutcome.Denied
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            assertWelcomeShown()
            goToLoginFromWelcome()
            createAccountToShowKey()
            confirmKeyToEnableNotifications()

            onNodeWithText("Turn on notifications").performClick()
            // Подсказка RN profile.pushPermissionDenied (модалка common.error);
            // текст ресурса с апострофом — matcher по подстроке.
            waitUntilAtLeastOneExists(
                hasText("enable notifications in your phone settings", substring = true),
                TIMEOUT,
            )
            // Дашборда пока нет — модалка активна.
            onNodeWithText("Plants").assertDoesNotExist()

            // RN: после showAlert идёт goHome — дашборд за модалкой.
            onNodeWithText("OK").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.plantsCount >= 1 }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Profile"), TIMEOUT)
            // Вызов был ровно один (denied-исход изнутри), granted-ветки не было.
            assertEquals(1, graph.push.subscribeCalls, "denied-исход: один вызов requestSubscribe")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-INTRO-002: Later → дашборд без вызова push-подсистемы
    // ------------------------------------------------------------------

    @Test
    fun laterPath_goesToDashboardWithoutPushCall() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            assertWelcomeShown()
            goToLoginFromWelcome()
            createAccountToShowKey()
            confirmKeyToEnableNotifications()

            onNodeWithText("Maybe later").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.plantsCount >= 1 }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Profile"), TIMEOUT)
            assertEquals(0, graph.push.subscribeCalls, "Later не трогает push-подсистему")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-INTRO-003: повторный вход ключом — окно после входа НЕ открывается
    // ------------------------------------------------------------------

    @Test
    fun plainRecoverySignIn_landsOnDashboard_withoutEnableScreen() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            assertWelcomeShown()
            goToLoginFromWelcome()
            // Вход ключом (не create) из choose-режима: окно после входа не
            // открывается — RN-эквивалент signInWithRecoveryKey → useEffect →
            // /(tabs). Поле GtTextField — OutlinedTextField с SetText-действием
            // (editable). Полный порт login (screen-login): «I Have a Key»
            // открывает режим ввода ключа (RN setMode('login')).
            onNodeWithText("I Have a Key").performClick()
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            onNode(hasSetTextAction()).performTextInput(graph.server.fixtureKey)
            onNodeWithText("Sign In").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.plantsCount >= 1 }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Profile"), TIMEOUT)
            // enable-notifications НЕ показана (VAL-INTRO-003: только из show-key).
            onNodeWithText("Turn on notifications").assertDoesNotExist()
            onNodeWithText("Enable reminders?").assertDoesNotExist()
            assertEquals(0, graph.push.subscribeCalls)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-LOGIN-002: create передаёт введённое имя (RN login.tsx:104)
    // ------------------------------------------------------------------

    /**
     * Режим create: ввод имени (null = поле оставить пустым) → submit →
     * ожидание ровно одного POST create-anonymous (тело в [FakeServer.createBody]).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.submitCreateAccount(name: String?) {
        val h = harness!!
        onNodeWithText("Create New Account").performClick()
        waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
        // Поле имени — единственный editable на поверхности create.
        if (name != null) onNode(hasSetTextAction()).performTextInput(name)
        onNode(
            androidx.compose.ui.test.hasClickAction() and hasText("Create Account"),
        ).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { h.server.createCount == 1 }
    }

    @Test
    fun createAccount_sendsTrimmedName_andSignsIn() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            assertWelcomeShown()
            goToLoginFromWelcome()
            // Имя с внешними пробелами — пример VAL-LOGIN-002 (`  Fern  `).
            submitCreateAccount("  Fern  ")
            // Провод: имя тримится НА КЛИЕНТЕ (RN name.trim() || undefined).
            assertEquals(
                """{"name":"Fern"}""",
                graph.server.createBody,
                "введённое имя trim'ится и доезжает до create-anonymous",
            )
            // Сессия: шлюз show-key открыт, дашборд подавлен (RN-гейт),
            // серверный ключ сохранён (SignedIn-путь applyUser).
            waitUntilAtLeastOneExists(hasText("Account Created!"), TIMEOUT)
            onNodeWithText("Plants").assertDoesNotExist()
            assertEquals(
                graph.server.fixtureKey,
                graph.secure.map[SecureStoreKeys.RECOVERY_KEY],
                "ключ сервера сохранён после создания аккаунта",
            )

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun createAccount_blankName_omitsNameField() {
        val graph = newHarness()
        presetEnglishWithIntroNotSeen()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            assertWelcomeShown()
            goToLoginFromWelcome()
            // Пустое имя остаётся опциональным: поле name не пишется в провод
            // (RN `undefined` → серверный дефолт; тело {} как в GreenThumbApiTest).
            submitCreateAccount(null)
            assertEquals("{}", graph.server.createBody, "пустое имя → поле name отсутствует")

            runOnIdle { graph.close() }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
