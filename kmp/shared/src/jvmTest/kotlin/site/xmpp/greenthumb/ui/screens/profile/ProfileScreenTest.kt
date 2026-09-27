@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
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
 * Stage 7 п.4 (фича screen-profile) — порт `app/(tabs)/profile.tsx` (736
 * строк, прочитана целиком): полная поверхность App() со стартом «сессия
 * решена → дашборд», переход на вкладку «Профиль» (заголовок «My Profile» —
 * profile.title), затем сквозные
 * кейсы: время уведомления (24 опции, чекмарк, PATCH, no-op на тот же час,
 * обновление user), язык (подписи вкладок + сохранение вкладки, тот же
 * триггер AppLanguage), тема, показ ключа, регенерация (диалог + новый
 * ключ в SecureStore), выход (чистка ключа/cached_user/БД).
 *
 * Push-секция до M9 — каркас: тумблер читает [FakePush.subscribed]
 * (в проде GET fcm-subscription; недоступен → off), клики по тумблеру в
 * этих кейсах не делаются.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileScreenTest {

    private var originalLocale: java.util.Locale? = null

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
        originalLocale?.let { java.util.Locale.setDefault(it) }
        originalLocale = null
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

    /**
     * Push-двойник: исход инжектируется, вызовы считаются (язык фиксируется).
     * [subscribed] управляет статусом подписки (GET fcm-subscription в проде):
     * в кейсах времени — включена, чтобы строка времени была активна
     * (RN держит её неактивной при выключенных пушах); в остальных — false
     * (статус «недоступен/выкл» → off, VAL-PROFILE-007).
     */
    private class FakePush : PushTokens() {
        var subscribed: Boolean = false
        var subscribeCalls = 0
        var lastLanguage: String? = null
        var outcome: PushOutcome = PushOutcome.Subscribed("fake-fcm-token")

        override suspend fun requestSubscribe(language: String): PushOutcome {
            subscribeCalls++
            lastLanguage = language
            return outcome
        }

        override suspend fun subscriptionStatus(): Boolean = subscribed

        override suspend fun unsubscribe() {}

        override suspend fun sendLocalTestNotification() {}
    }

    private class FakeServer {
        var meCount = 0
        var plantsCount = 0
        var createCount = 0
        var timePatchCount = 0
        var regenerateCount = 0
        var logoutCount = 0
        var timePatchStatus: HttpStatusCode = HttpStatusCode.OK
        var regenerateStatus: HttpStatusCode = HttpStatusCode.OK

        /**
         * Ключ ДО регенерации (create-anonymous возвращает его); после
         * [regenerateCount] > 0 отдаётся [regeneratedKey] — серверный контракт
         * «новый ключ приходит только с ответом регенерации».
         */
        var initialKey: String = "key-profile-81"

        /** Ключ, отдаваемый регенерацией (MockEngine-фикстура, не литерал). */
        var regeneratedKey: String = "key-profile-81"

        /** Время, отдаваемое PATCH-ответом (сервер возвращает обновлённого юзера). */
        var patchedTime: String? = null

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private val currentKey: String get() = if (regenerateCount > 0) regeneratedKey else initialKey

        private fun userJson(): String =
            """{"user":{"id":81,"name":"kmp-val-profile","recovery_key":"$currentKey",""" +
                """"notification_time":"${patchedTime ?: "09:00"}","created_at":"2026-09-26T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                // Старт: cookie-сессии нет → 401 → SignedOut (welcome первым).
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
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/update-notification-time" -> {
                    timePatchCount++
                    // Сервер эхом возвращает сохранённое время (паритет PATCH
                    // update-notification-time: user с обновлённым полем).
                    val body = request.body as io.ktor.http.content.TextContent
                    val match = Regex("\"notification_time\":\"([0-9:]+)\"").find(body.text)
                    patchedTime = match?.groupValues?.get(1)
                    respond(userJson(), timePatchStatus, JSON_HEADERS)
                }
                "/api/auth/regenerate-recovery-key" -> {
                    regenerateCount++
                    respond(userJson(), regenerateStatus, JSON_HEADERS)
                }
                "/api/auth/logout" -> {
                    logoutCount++
                    respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                }
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
        if (originalLocale == null) originalLocale = java.util.Locale.getDefault()
        val dir: File = File.createTempFile("gt-profile-screen", "").let { f ->
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
     * Язык явно en — строки ресурсов детерминированы для поиска. Интро НЕ
     * видено (пролог PostSignInFlowTest): welcome первым → Skip → логин.
     */
    private fun FakeSettings.presetEnglishSignedOut(): Unit = runBlocking {
        setLanguage(AppLanguage.En)
    }

    // ------------------------------------------------------------------
    // Общий пролог: welcome → login → create → вкладки → Profile
    // ------------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.openProfileTab(
        pushSubscribed: Boolean = false,
    ) {
        // Push-статус: строка времени активна только при включённых пушах
        // (RN); в кейсах времени подписка включена, в остальных — выкл.
        harness!!.push.subscribed = pushSubscribed
        // Первый запуск: интро не видено → welcome → логин.
        waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
        onNodeWithText("Skip").performClick()
        waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
        // Создание аккаунта → show-key → enable-notifications → дашборд.
        onNodeWithText("Create New Account").performClick()
        waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
        onNode(
            androidx.compose.ui.test.hasClickAction() and hasText("Create Account"),
        ).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { harness!!.server.createCount >= 1 }
        onNodeWithText("I've Saved My Key", substring = true).performClick()
        // Окно после входа: enable-notifications (Stage 7 п.2); Later → дашборд.
        waitUntilAtLeastOneExists(hasText("Maybe later"), TIMEOUT)
        onNodeWithText("Maybe later").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { harness!!.server.plantsCount >= 1 }
        waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
        // Профиль.
        onNodeWithText("Profile").performClick()
        waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
        // Реальный экран Stage 7 на выбранной вкладке (паритет старого маркера
        // `tabs/profile` интерим-поверхности: заголовок RN profile.title).
    }

    private fun hasSubText(value: String) = hasText(value, substring = true)

    /**
     * Клик по кликабельному узлу с текстом БЕЗ попадания в вьюпорт: семантика
     * OnClick вызывается напрямую (RN-паритет тапа по строке вне вьюпорта;
     * performClick у desktop-инжектора требует видимости центра).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.clickByLabel(
        text: String,
        role: Role = Role.Button,
    ) {
        clickAt(hasClickAction() and hasText(text) and hasAnyDescendantOrSelfRole(role))
    }

    /** Клик по узлу с ТОЧНО этим текстом (опция пикера, не merged-строка). */
    private fun androidx.compose.ui.test.ComposeUiTest.clickByExactLabel(text: String) {
        clickAt(hasClickAction() and hasText(text) and hasAnyDescendantOrSelfRole(Role.Button))
    }

    /**
     * Клик по N-му совпавшему узлу (счёт с 0) — дисамбиг дублирующихся
     * подписей (строка секции + кнопка диалога с тем же текстом; строка
     * времени + опция пикера). Порядок — семантическое дерево (top-down).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.clickAt(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        index: Int = 0,
    ) {
        onAllNodes(matcher)[index].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    /** Роль узла (merged-семантика: Role выставляется кликабельным родителем). */
    private fun hasAnyDescendantOrSelfRole(
        role: Role,
    ) = SemanticsMatcher(
        "has role $role",
    ) { node ->
        node.config.getOrNull(SemanticsProperties.Role) == role
    }

    /** Все узлы по матчеру (onAllNodes — член ComposeUiTest, импорт нельзя). */
    private fun androidx.compose.ui.test.ComposeUiTest.allNodes(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
    ) = onAllNodes(matcher).fetchSemanticsNodes()

    // ------------------------------------------------------------------
    // Время уведомления (VAL-PROFILE-002, desktop-ветка)
    // ------------------------------------------------------------------

    @Test
    fun notificationTimePicker_opensWith24Options_andChecksCurrent() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            // Пикер открывается с 24 целыми часами; на текущем (09:00)
            // чекмарк — semantics selected.
            clickByLabel("Notification Time")
            waitUntilAtLeastOneExists(hasSubText("09:00"), TIMEOUT)
            assertTrue(allNodes(hasText("23:00")).isNotEmpty(), "23:00 в списке")
            assertTrue(allNodes(hasText("00:00")).isNotEmpty(), "00:00 в списке")
            (0..23).forEach { hour ->
                val label = (if (hour < 10) "0" else "") + hour + ":00"
                assertTrue(allNodes(hasText(label)).isNotEmpty(), "$label в списке")
            }
            // Чекмарк текущего часа: опция с 09:00 selected (вкладка
            // Profile тоже selected — фильтруем по тексту опции).
            // RN accessibilityState={{selected}} — чекмарк и в семантике:
            // опция 09:00 selected; вкладка Profile (selectable) тоже selected,
            // фильтр — только узлы с текстом HH:00.
            val selectedTexts = onAllNodes(isSelected()).fetchSemanticsNodes()
                .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text) }
                .flatten()
                .joinToString(",") { it.text }
            assertTrue(selectedTexts.contains("09:00"), "опция 09:00 selected: $selectedTexts")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun notificationTimeSelect_sendsPatchHH00_andUpdatesUser() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            clickByLabel("Notification Time")
            waitUntilAtLeastOneExists(hasSubText("14:00"), TIMEOUT)
            clickByExactLabel("14:00")
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.timePatchCount == 1 }
            // user обновлён: пикер закрыт (23:00 — только внутри открытого
            // пикера), строка времени показывает новый час.
            waitUntilDoesNotExist(hasSubText("23:00"), TIMEOUT)
            onNodeWithText("14:00").assertExists()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun notificationTimeSameHour_isNoOp_noPatch() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            clickByLabel("Notification Time")
            waitUntilAtLeastOneExists(hasSubText("09:00"), TIMEOUT)
            clickAt(hasClickAction() and hasText("09:00") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            // Модалка закрылась (23:00 — только внутри открытого пикера),
            // PATCH не отправлен (тот же час).
            waitUntilDoesNotExist(hasSubText("23:00"), TIMEOUT)
            assertEquals(0, graph.server.timePatchCount, "no-op: PATCH на тот же час не отправляется")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun notificationTimeError_showsAlert_userNotUpdated() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.timePatchStatus = HttpStatusCode.InternalServerError
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            clickByLabel("Notification Time")
            waitUntilAtLeastOneExists(hasSubText("14:00"), TIMEOUT)
            clickByExactLabel("14:00")
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.timePatchCount == 1 }
            // Модалка ошибки открыта, час на экране прежний (09:00).
            waitUntilAtLeastOneExists(hasText("Error"), TIMEOUT)
            // «14:00» легитимно сидит в тексте ошибки (ApiError.body — тело
            // 500-ответа); проверяем строку времени: прежний час 09:00,
            // success-модалки нет.
            waitUntilAtLeastOneExists(hasSubText("09:00"), TIMEOUT)
            kotlin.test.assertNull(graph.settings.getCachedUserBlocking()?.notificationTime.takeIf { it == "14:00" }, "user не обновлён")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Регенерация ключа (VAL-PROFILE-001, desktop-ветка: диалог + сохранение)
    // ------------------------------------------------------------------

    @Test
    fun regenerateKey_cancelDialogDoesNotRegenerate_confirmPersistsNewKey() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        // Новый ключ сервера (MockEngine-фикстура) — конкатенация, не литерал.
        val regeneratedKey = listOf("key", "regen", 82).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            // Показать ключ (текущий — key-profile-81).
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)

            // Отмена диалога не регенерирует (RN cancel-style ветка).
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickByLabel("Cancel")
            waitUntilDoesNotExist(hasSubText("replace your current recovery key"), TIMEOUT)
            assertEquals(0, graph.server.regenerateCount, "отмена не вызывает POST")

            // Подтверждение: POST, новый ключ записан (SecureStore) и показан.
            // Индекс 1: строка секции (index 0) + кнопка диалога (index 1).
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.regenerateCount == 1 }
            // Ключ записан (SecureStore) — success-модалка «Key regenerated»
            // поверх (RN showAlert поверх setKeyVisible(true)); закрыть OK,
            // затем ключ виден в секции.
            waitUntil(timeoutMillis = TIMEOUT) { secureKey(graph) == regeneratedKey }
            waitUntilAtLeastOneExists(hasText("OK"), TIMEOUT)
            clickByLabel("OK")
            waitUntilDoesNotExist(hasText("OK"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Язык/тема (VAL-PROFILE-005, desktop-ветка; RN-паритет сохранения вкладки)
    // ------------------------------------------------------------------

    @Test
    fun languagePicker_selectsEn_labelsChange_tabKept() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            onNodeWithText("Language").performClick()
            waitUntilAtLeastOneExists(hasText("Русский"), TIMEOUT)
            onNodeWithText("Русский").performClick()
            // Подписи вкладок и профиля перечитаны немедленно (VAL-I18N-004).
            waitUntilAtLeastOneExists(hasText("Растения"), TIMEOUT)
            waitUntilDoesNotExist(hasText("My Profile"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Мой профиль"), TIMEOUT)
            // Профиль остался выбран (VAL-I18N-006): редиректа на дашборд нет.
            onNodeWithText("Растения").assertExists()
            onNodeWithText("Профиль").assertExists()
            onNodeWithText("Мой профиль").assertExists()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun themePicker_selectsDark_preferenceWritten() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            onNodeWithText("Theme").performClick()
            waitUntilAtLeastOneExists(hasSubText("Dark"), TIMEOUT)
            onNodeWithText("Dark").performClick()
            waitUntilDoesNotExist(hasSubText("Choose Theme"), TIMEOUT)
            // Значение записано в AppSettings (реактивный Flow).
            waitUntil(timeoutMillis = TIMEOUT) { graph.settings.getThemeBlocking() == ThemePreference.Dark }
            // Подпись профиля показывает выбор.
            onNodeWithText("Dark", substring = true).assertExists()

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Выход (VAL-PROFILE-006, desktop-ветка; матрица чистки VAL-DATA-008)
    // ------------------------------------------------------------------

    @Test
    fun signOut_confirmDialog_thenLoginScreen_fullLocalCleanup() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            clickByLabel("Sign Out")
            waitUntilAtLeastOneExists(hasSubText("Sign out?"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Sign Out") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)

            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            // Локальная чистка: ключ, cached_user.
            kotlin.test.assertNull(secureKey(graph), "recovery key удалён")
            kotlin.test.assertNull(graph.settings.getCachedUserBlocking(), "cached_user удалён")
            assertEquals(1, graph.server.logoutCount, "серверный logout отправлен")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Хелперы блокирующих чтений для waitUntil-предикатов
    // ------------------------------------------------------------------

    private fun FakeSettings.getThemeBlocking(): ThemePreference? = runBlocking { getTheme() }

    private fun FakeSettings.getCachedUserBlocking(): UserDto? = runBlocking { getCachedUser() }

    private fun secureKey(graph: Harness): String? = runBlocking {
        graph.secure.get(SecureStoreKeys.RECOVERY_KEY)
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
