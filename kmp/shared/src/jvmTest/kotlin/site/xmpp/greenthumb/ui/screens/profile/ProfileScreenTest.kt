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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.unit.dp
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

        /** The store refuses every write (a broken keystore). */
        @Volatile
        var failSet = false

        /** After the next successful write of the current key, reads wait for [readGate] (a slow keystore). */
        @Volatile
        var holdReadsAfterSet = false

        val readGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        @Volatile
        private var holding = false

        override suspend fun get(key: String): String? {
            if (holding) readGate.await()
            return map[key]
        }

        override suspend fun set(key: String, value: String): Boolean {
            if (failSet) return false
            map[key] = value
            if (holdReadsAfterSet && key == SecureStoreKeys.RECOVERY_KEY) holding = true
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

        /** M9: инжекция сбоя тестового уведомления (реальный actual может бросать). */
        var sendThrow: Throwable? = null

        override suspend fun requestSubscribe(language: String): PushOutcome {
            subscribeCalls++
            lastLanguage = language
            return outcome
        }

        override suspend fun subscriptionStatus(): Boolean = subscribed

        override suspend fun unsubscribe() {}

        override suspend fun sendLocalTestNotification() {
            sendThrow?.let { throw it }
        }
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

        /** The account's keys on the server (two-phase change: regenerate → pending, confirm → current). */
        @Volatile private var current: String? = null
        @Volatile private var pending: String? = null
        private val currentKey: String get() = current ?: initialKey

        var confirmCount = 0

        /** Fixed confirm answer (e.g. 503); null = the contract. */
        @Volatile var confirmStatus: HttpStatusCode? = null

        /** Server that no longer returns `recovery_key` from me / login-recovery. */
        var omitRecoveryKey = false

        /** `/api/auth/me` answers 200 (live cookie session) instead of 401. */
        var meSignedIn = false

        var loginCount = 0

        /** Key sent by the last login-recovery request. */
        @Volatile var lastLoginKey: String? = null

        /** Regenerate answers 200 without `recovery_key`. */
        @Volatile var regenerateOmitsKey = false

        /** Regenerate fails in transport (the answer is lost). */
        @Volatile var regenerateFailTransport = false

        /** When set, `/api/auth/me` answers this status (e.g. 503 at startup). */
        @Volatile var meStatusOverride: HttpStatusCode? = null

        private fun userJson(withKey: Boolean = true): String =
            """{"user":{"id":81,"name":"kmp-val-profile",""" +
                (if (withKey) """"recovery_key":"$currentKey",""" else "") +
                """"notification_time":"${patchedTime ?: "09:00"}","created_at":"2026-09-26T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                // Старт: cookie-сессии нет → 401 → SignedOut (welcome первым).
                "/api/auth/me" -> {
                    meCount++
                    val override = meStatusOverride
                    if (override != null) {
                        respond("{}", override, JSON_HEADERS)
                    } else if (meSignedIn) {
                        respond(userJson(withKey = !omitRecoveryKey), HttpStatusCode.OK, JSON_HEADERS)
                    } else {
                        respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                    }
                }
                "/api/auth/login-recovery" -> {
                    loginCount++
                    val sent = Regex("\"recoveryKey\":\"([^\"]*)\"")
                        .find((request.body as io.ktor.http.content.TextContent).text)?.groupValues?.get(1)
                    lastLoginKey = sent
                    if (sent != null && sent == pending) {
                        current = sent
                        pending = null
                    }
                    respond(userJson(withKey = !omitRecoveryKey), HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/plants" -> {
                    plantsCount++
                    respond("[]", HttpStatusCode.OK, JSON_HEADERS)
                }
                "/api/auth/create-anonymous" -> {
                    createCount++
                    current = initialKey
                    pending = null
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
                    if (regenerateFailTransport) throw IllegalStateException("Connection reset")
                    if (regenerateStatus == HttpStatusCode.OK && !regenerateOmitsKey) pending = regeneratedKey
                    // The new key is only in `pending_recovery_key`; the user carries no key.
                    val user = userJson(withKey = false).removeSuffix("}")
                    val field = if (regenerateOmitsKey) "" else ""","pending_recovery_key":"$regeneratedKey""""
                    respond("$user$field}", regenerateStatus, JSON_HEADERS)
                }
                "/api/auth/confirm-recovery-key" -> {
                    confirmCount++
                    val sent = Regex("\"recoveryKey\":\"([^\"]*)\"")
                        .find((request.body as io.ktor.http.content.TextContent).text)?.groupValues?.get(1)
                    val fixed = confirmStatus
                    val user = userJson(withKey = false).removeSuffix("}")
                    when {
                        fixed != null -> respond("{}", fixed, JSON_HEADERS)
                        sent != null && sent == pending -> {
                            current = sent
                            pending = null
                            respond("""$user,"status":"committed"}""", HttpStatusCode.OK, JSON_HEADERS)
                        }
                        sent == current -> respond("""$user,"status":"already_current"}""", HttpStatusCode.OK, JSON_HEADERS)
                        else -> respond("""{"code":"not_pending"}""", HttpStatusCode.Conflict, JSON_HEADERS)
                    }
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
            clickByLabel("Every day at 09:00")
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

    /**
     * VAL-FIX11-004/-007: one reminders card — the title row is the one switch node, the time row
     * «Every day at HH:00» is a separate button in the same card, the long hint and the separate
     * «Notification Time» row are gone.
     */
    @Test
    fun remindersCard_titleSwitch_andEveryDayRow_withoutOldTexts() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            val isSwitch = hasAnyDescendantOrSelfRole(Role.Switch)
            waitUntilAtLeastOneExists(isSwitch and hasText("Watering Reminders"), TIMEOUT)
            assertEquals(1, allNodes(hasText("Watering Reminders")).size, "the title is not a second node")
            assertEquals(1, allNodes(isSwitch).size, "one switch node")
            val timeRow = allNodes(hasClickAction() and hasText("Every day at 09:00") and hasAnyDescendantOrSelfRole(Role.Button))
            assertEquals(1, timeRow.size, "the time row is its own button")
            assertTrue(allNodes(hasSubText("Get notified when your plants need watering")).isEmpty(), "long hint removed")
            assertTrue(allNodes(hasText("Notification Time")).isEmpty(), "separate time row removed")
            assertTrue(allNodes(hasSubText("Choose when you want to receive daily reminders")).isEmpty(), "time hint removed")

            runOnIdle { graph.close() }
        }
    }

    /**
     * With reminders off the «Every day at HH:00» row is shown but is not a button: no click action
     * and no Button role, so TalkBack and uiautomator do not offer a tap that does nothing.
     */
    @Test
    fun remindersOff_timeRowIsNotClickable() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = false)

            waitUntilAtLeastOneExists(hasText("Every day at 09:00"), TIMEOUT)
            assertTrue(allNodes(hasClickAction() and hasText("Every day at 09:00")).isEmpty(), "no click action when off")
            assertTrue(
                allNodes(hasText("Every day at 09:00") and hasAnyDescendantOrSelfRole(Role.Button)).isEmpty(),
                "no Button role when off",
            )

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

            clickByLabel("Every day at 09:00")
            waitUntilAtLeastOneExists(hasSubText("14:00"), TIMEOUT)
            clickByExactLabel("14:00")
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.timePatchCount == 1 }
            // user обновлён: пикер закрыт (23:00 — только внутри открытого
            // пикера), строка времени показывает новый час.
            waitUntilDoesNotExist(hasSubText("23:00"), TIMEOUT)
            onNodeWithText("Every day at 14:00").assertExists()

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

            clickByLabel("Every day at 09:00")
            waitUntilAtLeastOneExists(hasSubText("09:00"), TIMEOUT)
            clickAt(hasClickAction() and hasText("09:00") and hasAnyDescendantOrSelfRole(Role.Button))
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

            clickByLabel("Every day at 09:00")
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
    fun keyActionRows_areAtLeast48dpHigh() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.initialKey = "key-profile-81"
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)
            waitUntilAtLeastOneExists(hasText("Generate New Key"), TIMEOUT)
            val minPx = with(density) { 48.dp.toPx() } - 0.5f
            val rows = allNodes(hasClickAction() and (hasText("Generate New Key") or hasText("Show key as QR code")))
            assertEquals(2, rows.size, "both key action rows are shown")
            val small = rows.filter { node ->
                node.size.width < minPx || node.size.height < minPx
            }.map { node ->
                val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                    ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                "$label ${node.size}"
            }
            assertTrue(small.isEmpty(), "key action rows under 48 dp: $small")
            runOnIdle { graph.close() }
        }
    }

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

    @Test
    fun regenerateKey_storageWriteFails_showsNewKeyAndWarning() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        val regeneratedKey = listOf("key", "regen", 84).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)

            graph.secure.failSet = true
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.regenerateCount == 1 }

            // The pending key could not be stored: no confirm; both keys are shown with the note.
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(NEW_KEY_NOT_STORED), TIMEOUT)
            assertEquals("key-profile-81", secureKey(graph), "the old key is untouched")
            assertEquals(0, graph.server.confirmCount, "no confirm without a stored pending key")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun createAccount_storageWriteFails_showKeyAndProfileWarn() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.secure.failSet = true
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
            onNodeWithText("Skip").performClick()
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            onNodeWithText("Create New Account").performClick()
            waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
            onNode(androidx.compose.ui.test.hasClickAction() and hasText("Create Account")).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.createCount >= 1 }
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(KEY_NOT_STORED), TIMEOUT)

            onNodeWithText("I've Saved My Key", substring = true).performClick()
            waitUntilAtLeastOneExists(hasText("Maybe later"), TIMEOUT)
            onNodeWithText("Maybe later").performClick()
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            onNodeWithText("Profile").performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(KEY_NOT_STORED), TIMEOUT)
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)
            assertEquals(null, secureKey(graph), "the write really failed")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun regenerateKey_newKeyShownWithoutWaitingForStorageRead() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        val regeneratedKey = listOf("key", "regen", 85).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        try {
            runDesktopComposeUiTest {
                setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
                openProfileTab()
                clickByLabel("Show")
                waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)

                // Storage reads after the new key's write hang: the screen must not depend on one.
                graph.secure.holdReadsAfterSet = true
                clickByLabel("Generate New Key")
                waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
                clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
                waitUntil(timeoutMillis = TIMEOUT) { graph.server.regenerateCount == 1 }
                // The key is on screen while storage reads still hang. The change itself reads
                // RECOVERY_KEY back before it drops the pending key, so its OK waits for storage.
                waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)
                assertTrue(allNodes(hasSubText("key-profile-81")).isEmpty(), "the old key is gone")

                graph.secure.readGate.complete(Unit)
                waitUntilAtLeastOneExists(hasText("OK"), TIMEOUT)
                clickByLabel("OK")
                waitUntilDoesNotExist(hasText("OK"), TIMEOUT)
                runOnIdle { graph.close() }
            }
        } finally {
            graph.secure.readGate.complete(Unit)
        }
    }

    @Test
    fun regenerateKey_responseWithoutKey_showsRetryError_keepsCurrentKey() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.initialKey = "key-profile-81"
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)

            graph.server.regenerateOmitsKey = true
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.regenerateCount == 1 }

            waitUntilAtLeastOneExists(hasSubText("did not return a new key"), TIMEOUT)
            assertTrue(allNodes(hasText("Key regenerated")).isEmpty(), "no success for a keyless answer")
            assertEquals("key-profile-81", secureKey(graph), "storage untouched")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun regenerateKey_lostAnswer_saysKeyMayHaveChanged() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            graph.server.regenerateFailTransport = true
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.regenerateCount == 1 }

            waitUntilAtLeastOneExists(hasSubText("Your key may have changed"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun regenerateKey_confirmFails_showsUnfinishedState_retryFinishes() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        val regeneratedKey = listOf("key", "regen", 86).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)

            graph.server.confirmStatus = HttpStatusCode.ServiceUnavailable
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.confirmCount == 1 }

            // Not finished and not proven: both keys are on screen, K1 stays on disk.
            waitUntilAtLeastOneExists(hasSubText(KEY_CHANGE_UNFINISHED), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)
            assertTrue(allNodes(hasSubText("already works")).isEmpty(), "the new key is not called working")
            assertEquals("key-profile-81", secureKey(graph))
            assertEquals(regeneratedKey, graph.secure.map[SecureStoreKeys.RECOVERY_KEY_PENDING])

            graph.server.confirmStatus = null
            clickByLabel("Finish key change")
            waitUntil(timeoutMillis = TIMEOUT) { secureKey(graph) == regeneratedKey }
            waitUntilAtLeastOneExists(hasText("OK"), TIMEOUT)
            clickByLabel("OK")
            waitUntilDoesNotExist(hasSubText(KEY_CHANGE_UNFINISHED), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)
            assertEquals(null, graph.secure.map[SecureStoreKeys.RECOVERY_KEY_PENDING])

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun unfinishedKeyChange_newKeyHasItsOwnCopyButton() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        val regeneratedKey = listOf("key", "regen", 88).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()
            clickByLabel("Show")
            graph.server.confirmStatus = HttpStatusCode.ServiceUnavailable
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntilAtLeastOneExists(hasSubText(KEY_CHANGE_UNFINISHED), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)

            waitUntilAtLeastOneExists(androidx.compose.ui.test.hasTestTag("profile_copyNewKey"), TIMEOUT)
            onNode(androidx.compose.ui.test.hasTestTag("profile_copyNewKey")).performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick,
            )
            // The same copy path as the current key: Clipboard.copy (Android marks the clip sensitive).
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(androidx.compose.ui.test.hasTestTag("profile_newKeyCopied"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val clip = runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
            }.getOrNull()
            if (clip != null) assertEquals(regeneratedKey, clip, "the new key is on the clipboard")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun signOut_withUnfinishedKeyChange_asksToSaveNewKey_signOutAnywayErases() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        val regeneratedKey = listOf("key", "regen", 87).joinToString("-")
        graph.server.initialKey = "key-profile-81"
        graph.server.regeneratedKey = regeneratedKey
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()
            graph.server.confirmStatus = HttpStatusCode.ServiceUnavailable
            clickByLabel("Generate New Key")
            waitUntilAtLeastOneExists(hasSubText("replace your current recovery key"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Generate New Key") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntilAtLeastOneExists(hasSubText(KEY_CHANGE_UNFINISHED), TIMEOUT)

            clickByLabel("Sign Out")
            waitUntilAtLeastOneExists(hasSubText("Sign out?"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Sign Out") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)

            // The change still cannot finish: save both keys first; nothing is erased yet.
            waitUntilAtLeastOneExists(hasText("Sign out anyway"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(regeneratedKey), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("key-profile-81"), TIMEOUT)
            assertEquals("key-profile-81", secureKey(graph))
            assertEquals(regeneratedKey, graph.secure.map[SecureStoreKeys.RECOVERY_KEY_PENDING])
            assertEquals(0, graph.server.logoutCount, "no sign-out without an explicit choice")

            clickByLabel("Sign out anyway")
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.logoutCount == 1 }
            waitUntil(timeoutMillis = TIMEOUT) { secureKey(graph) == null }
            assertEquals(null, graph.secure.map[SecureStoreKeys.RECOVERY_KEY_PENDING])

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Start with a stored key and a transient failure: retry, not a bare login
    // ------------------------------------------------------------------

    @Test
    fun startup_transientFailureWithStoredKey_offersRetry_thenSignsIn() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runBlocking { graph.secure.set(SecureStoreKeys.RECOVERY_KEY, "key-profile-81") }
        graph.server.meStatusOverride = HttpStatusCode.ServiceUnavailable
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Try again"), TIMEOUT)
            assertTrue(allNodes(hasText("Create New Account")).isEmpty(), "not the bare login screen")

            graph.server.meStatusOverride = null
            graph.server.meSignedIn = true
            clickByLabel("Try again")
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            assertEquals("key-profile-81", secureKey(graph))

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun startup_transientFailureWithStoredKey_otherKeyOpensKeyEntry() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        runBlocking { graph.secure.set(SecureStoreKeys.RECOVERY_KEY, "key-profile-81") }
        graph.server.meStatusOverride = HttpStatusCode.ServiceUnavailable
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Use a different key"), TIMEOUT)
            clickByLabel("Use a different key")
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            assertEquals("key-profile-81", secureKey(graph), "the stored key stays")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Sign-in whose key the phone could not store: warn right after sign-in
    // ------------------------------------------------------------------

    @Test
    fun typedKeySignIn_storageWriteFails_warnsRightAfterSignIn() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.omitRecoveryKey = true
        graph.secure.failSet = true
        val key = listOf("0000abcd", "0000", "4000", "8000", "000000000081").joinToString("-")
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
            onNodeWithText("Skip").performClick()
            waitUntilAtLeastOneExists(hasText("I Have a Key"), TIMEOUT)
            onNodeWithText("I Have a Key").performClick()
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            onNode(hasSetTextAction()).performTextInput(key)
            onNodeWithText("Sign In").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.loginCount == 1 }

            waitUntilAtLeastOneExists(hasSubText(KEY_NOT_STORED), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText(key), TIMEOUT)
            clickByLabel("OK")
            waitUntilDoesNotExist(hasSubText(KEY_NOT_STORED), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)

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
    // Push-сбои (M9 kmp-push-fcm): отказ/сбой тумблера и тестового уведомления
    // — локализованное состояние ошибки, БЕЗ краша UI (RN-паритет
    // catch(error: any) → showAlert(error.message))
    // ------------------------------------------------------------------

    @Test
    fun togglePush_errorOutcome_showsAlert_switchStaysOff_noCrash() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.push.outcome = PushOutcome.Error("boom-toggle")
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab()

            // Тумблер вкл → исход Error (сбой подписки) → модалка ошибки.
            // Матч по роли Switch (toggleable ставит Role.Switch; M11:
            // contentDescription «Notifications» теперь входит в семантику —
            // фикс дефекта M9, GtSwitchSemanticsTest — но матч по роли устойчив
            // и не зависит от текста).
            clickAt(hasClickAction() and hasAnyDescendantOrSelfRole(Role.Switch))
            waitUntilAtLeastOneExists(hasText("Error"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("boom-toggle"), TIMEOUT)
            // Тумблер остаётся выключенным (pushEnabled = false): success-модалки
            // «Notifications enabled» нет, семантика switch не selected.
            waitUntilDoesNotExist(hasSubText("You will receive reminders"), TIMEOUT)
            val switchNode = onAllNodes(hasClickAction() and hasAnyDescendantOrSelfRole(Role.Switch))
                .fetchSemanticsNodes().first()
            kotlin.test.assertFalse(
                switchNode.config.getOrNull(SemanticsProperties.Selected) == true,
                "switch остаётся off после Error-исхода",
            )

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun testNotificationException_showsAlert_noCrash() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.push.sendThrow = IllegalStateException("boom-test")
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openProfileTab(pushSubscribed = true)

            // Строка тестового уведомления видна при включённых пулах; бросок
            // sendLocalTestNotification — модалка ошибки с message, не краш.
            clickByLabel("Send Test Notification")
            waitUntilAtLeastOneExists(hasText("Error"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("boom-test"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Server without recovery_key: Profile and the QR screen read the key from
    // the phone's secure storage
    // ------------------------------------------------------------------

    @Test
    fun recoveryKey_serverOmitsKey_typedKeySignIn_profileAndQrUseStoredKey() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.omitRecoveryKey = true
        // Typed in upper case with braces; the app stores the canonical form.
        val canonicalKey = listOf("0000abcd", "0000", "4000", "8000", "000000000081").joinToString("-")
        val typedKey = "{" + canonicalKey.uppercase() + "}"
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
            onNodeWithText("Skip").performClick()
            waitUntilAtLeastOneExists(hasText("I Have a Key"), TIMEOUT)
            onNodeWithText("I Have a Key").performClick()
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            onNode(hasSetTextAction()).performTextInput(typedKey)
            onNodeWithText("Sign In").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.loginCount == 1 }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            assertEquals(canonicalKey, secureKey(graph), "canonical typed key saved to secure storage")

            onNodeWithText("Profile").performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText(canonicalKey), TIMEOUT)
            assertTrue(allNodes(hasText("Key not found")).isEmpty(), "no not-found state")

            clickByLabel("Show key as QR code")
            waitUntilAtLeastOneExists(hasTestTag("keyQr_code"), TIMEOUT)
            assertTrue(allNodes(hasText("Key not found")).isEmpty(), "QR screen has the key")
            assertEquals(canonicalKey, decodeQr(onNode(hasTestTag("keyQr_code")).captureToImage()), "QR encodes the stored key")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun recoveryKey_serverOmitsKey_savedKeyStartup_signOut_signInAgain_sameAccount() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.omitRecoveryKey = true
        val storedKey = listOf("0000abcd", "0000", "4000", "8000", "000000000082").joinToString("-")
        runBlocking {
            graph.settings.setIntroSeen()
            graph.secure.set(SecureStoreKeys.RECOVERY_KEY, storedKey)
        }
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            // No cookie session: the app signs in with the saved key; the answer has no key.
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            // The fake server has no cookie jar, so later 401s re-run login-recovery through the API seam.
            assertTrue(graph.server.loginCount >= 1)
            assertEquals(storedKey, graph.server.lastLoginKey, "sign-in sent the saved key")
            assertEquals("81", graph.settings.getCachedUserBlocking()?.id)
            assertEquals(storedKey, secureKey(graph), "saved key kept after a key-less answer")

            onNodeWithText("Profile").performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasSubText(storedKey), TIMEOUT)
            clickByLabel("Show key as QR code")
            waitUntilAtLeastOneExists(hasTestTag("keyQr_code"), TIMEOUT)
            assertEquals(storedKey, decodeQr(onNode(hasTestTag("keyQr_code")).captureToImage()))
            onNode(androidx.compose.ui.test.hasContentDescription("Back") and hasClickAction()).performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)

            clickByLabel("Sign Out")
            waitUntilAtLeastOneExists(hasSubText("Sign out?"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Sign Out") and hasAnyDescendantOrSelfRole(Role.Button), index = 1)
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            assertEquals(1, graph.server.logoutCount)
            kotlin.test.assertNull(secureKey(graph), "sign-out removes the key")
            val loginsBefore = graph.server.loginCount
            graph.server.lastLoginKey = null

            onNodeWithText("I Have a Key").performClick()
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            onNode(hasSetTextAction()).performTextInput(storedKey)
            onNodeWithText("Sign In").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.loginCount > loginsBefore }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            assertEquals(storedKey, graph.server.lastLoginKey, "sign-in sent the typed key")
            assertEquals("81", graph.settings.getCachedUserBlocking()?.id, "same account after sign-in")
            assertEquals(storedKey, secureKey(graph), "typed key stored, not a key from the answer")

            runOnIdle { graph.close() }
        }
    }
    @Test
    fun recoveryKey_serverOmitsKey_nothingStored_profileShowsKeyNotFound() {
        val graph = newHarness()
        graph.settings.presetEnglishSignedOut()
        graph.server.omitRecoveryKey = true
        graph.server.meSignedIn = true
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            onNodeWithText("Profile").performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            clickByLabel("Show")
            waitUntilAtLeastOneExists(hasText("Key not found"), TIMEOUT)
            assertEquals(null, secureKey(graph))

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Хелперы блокирующих чтений для waitUntil-предикатов
    // ------------------------------------------------------------------

    private fun FakeSettings.getThemeBlocking(): ThemePreference? = runBlocking { getTheme() }

    private fun FakeSettings.getCachedUserBlocking(): UserDto? = runBlocking { getCachedUser() }

    /** Decodes the QR the screen drew, so a test checks what the code really holds. */
    private fun decodeQr(image: androidx.compose.ui.graphics.ImageBitmap): String {
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val source = com.google.zxing.RGBLuminanceSource(image.width, image.height, pixels)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
        return com.google.zxing.qrcode.QRCodeReader().decode(bitmap, hints).text
    }

    private fun secureKey(graph: Harness): String? = runBlocking {
        graph.secure.get(SecureStoreKeys.RECOVERY_KEY)
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L

        /** Part of the "phone could not store the key" warning. */
        const val KEY_CHANGE_UNFINISHED: String = "key change is not finished"
        const val NEW_KEY_NOT_STORED: String = "new key was not saved on this phone"
        const val KEY_NOT_STORED: String = "store your recovery key"
    }
}
