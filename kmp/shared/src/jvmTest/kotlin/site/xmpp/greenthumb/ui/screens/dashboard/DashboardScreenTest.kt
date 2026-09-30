@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
import kotlinx.datetime.LocalDate
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
import site.xmpp.greenthumb.ui.components.pickerToday

/**
 * Stage 11 п.3 (фича kmp-compose-ui-tests, VAL-TEST-002): экран дашборда —
 * открытие + ключевые действия + семантическая проверка (до сих пор у
 * дашборда были только логические тесты: DashboardLogicTest,
 * BulkWaterBannerPluralTest, SnapRenderOrderTest).
 *
 * Поверхность — полная App() (механика AddPlantScreenTest): welcome → Skip →
 * create → show-key → enable (Later) → дашборд с фикстурой из трёх растений
 * (GET /api/plants отдаёт даты относительно [pickerToday] — того же «сегодня»,
 * что читает экран):
 * - Fern: полит 10 дней назад, частота 7 → «3 days overdue» (кнопка полива);
 * - Basil: полит 7 дней назад → «Water today» (кнопка полива);
 * - Monstera: полит вчера → «6 days left» (healthy, галочка вместо кнопки).
 *
 * Сценарии:
 * - открытие: заголовок/имя пользователя, поиск, чипы фильтров со счётчиком
 *   «Needs Water (2)», карточки со статус-пилюлями; массовые кнопки на фильтре
 *   All отсутствуют (RN-паритет — только на needsWater);
 * - фильтры (ключевое действие): «Needs Water (2)» → массовые кнопки видны,
 *   healthy-растение скрыто; «Healthy» → только Monstera, массовых нет;
 * - полив (ключевое действие): тап по «Water plant Fern» → PATCH
 *   /api/plants/fern с last_watered_date = сегодня → пилюля «7 days left»,
 *   кнопка полива исчезла (оптимистичный Room-статус до/без refetch);
 * - поиск (ключевое действие): «bas» → остаётся один Basil; очистка → все
 *   три; «zzz» → отдельный empty-state «No plants found» + подсказка поиска.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardScreenTest {

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика AddPlantScreenTest)
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

    private class FakePush : PushTokens() {
        override suspend fun requestSubscribe(language: String) =
            site.xmpp.greenthumb.core.platform.PushOutcome.Denied

        override suspend fun subscriptionStatus(): Boolean = false

        override suspend fun unsubscribe() {}

        override suspend fun sendLocalTestNotification() {}
    }

    private class FakeServer {
        var getCount = 0
        var createCount = 0
        var patchCount = 0
        var lastPatchPath: String? = null
        var lastPatchBody: String? = null

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":73,"name":"kmp-val-dash","recovery_key":"fixture-not-credential",""" +
                """"created_at":"2026-09-27T10:00:00.000Z"}}"""

        /**
         * Фикстура из трёх растений; даты относительно СЕГОДНЯШНЕГО дня
         * [pickerToday] — того же, что читает экран (статусы пилюль
         * детерминированы, UTC/зона теста не влияют).
         */
        private fun plantsJson(): String {
            val today = pickerToday()
            fun daysAgo(days: Long): String =
                LocalDate.fromEpochDays(today.toEpochDays() - days).toString()
            fun plant(id: String, name: String, location: String, lastWatered: String) =
                """{"id":"$id","user_id":"73","name":"$name","location":"$location",""" +
                    """"photo_url":"","water_frequency_days":7,"last_watered_date":"$lastWatered",""" +
                    """"notes":"","created_at":"2026-09-01T10:00:00.000Z"}"""
            return "[" + listOf(
                plant("fern", "Fern", "Living Room", daysAgo(10)), // −3 дня → overdue
                plant("basil", "Basil", "Kitchen", daysAgo(7)), // 0 → water today
                plant("monstera", "Monstera", "Balcony", daysAgo(1)), // +6 → healthy
            ).joinToString(separator = ",") + "]"
        }

        /** PATCH-эхо: обновлённое растение с датой из тела патча. */
        private fun patchedPlantJson(body: String): String {
            val date = Regex("\"last_watered_date\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                ?: "2026-09-27"
            return """{"id":"fern","user_id":"73","name":"Fern","location":"Living Room",""" +
                """"photo_url":"","water_frequency_days":7,"last_watered_date":"$date",""" +
                """"notes":"","created_at":"2026-09-01T10:00:00.000Z"}"""
        }

        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/auth/me" ->
                    respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                path == "/api/plants" && request.method == HttpMethod.Get -> {
                    getCount++
                    respond(plantsJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                path.startsWith("/api/plants/") && request.method == HttpMethod.Patch -> {
                    patchCount++
                    lastPatchPath = path
                    val body = (request.body as? TextContent)?.text
                    lastPatchBody = body
                    respond(patchedPlantJson(body ?: ""), HttpStatusCode.OK, JSON_HEADERS)
                }
                path == "/api/auth/create-anonymous" -> {
                    createCount++
                    respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                }
                path == "/api/auth/logout" ->
                    respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
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
        val dir: File = File.createTempFile("gt-dash-screen", "").let { f ->
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

    private fun presetEnglishSignedOut(): Unit = runBlocking {
        harness!!.settings.setLanguage(AppLanguage.En)
    }

    // ------------------------------------------------------------------
    // Пролог и клики (механика AddPlantScreenTest / ProfileScreenTest)
    // ------------------------------------------------------------------

    private fun hasSubText(value: String) = hasText(value, substring = true)

    /** welcome → Skip → create → show-key → enable (Later) → дашборд. */
    private fun androidx.compose.ui.test.ComposeUiTest.openDashboard() {
        val h = harness!!
        waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
        onNodeWithText("Skip").performClick()
        waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
        onNodeWithText("Create New Account").performClick()
        waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
        onNode(hasClickAction() and hasText("Create Account")).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { h.server.createCount >= 1 }
        onNode(hasText("Saved My Key", substring = true)).performClick()
        waitUntilAtLeastOneExists(hasText("Maybe later"), TIMEOUT)
        onNodeWithText("Maybe later").performClick()
        // Роутится на дашборд → refresh → фикстура в Room.
        waitUntil(timeoutMillis = TIMEOUT) { h.server.getCount >= 1 }
        waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
    }

    /** Клик по N-му совпавшему узлу — OnClick напрямую, без вьюпорта. */
    private fun androidx.compose.ui.test.ComposeUiTest.clickAt(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        index: Int = 0,
    ) {
        onAllNodes(matcher)[index].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    // ------------------------------------------------------------------
    // Открытие: шапка, фильтры со счётчиком, статусы, отсутствие массовых
    // ------------------------------------------------------------------

    @Test
    fun dashboard_opens_with_header_filters_and_status_pills() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()

            // Шапка: заголовок + имя пользователя сессии.
            waitUntilAtLeastOneExists(hasText("My Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("kmp-val-dash"), TIMEOUT)
            // Поиск и три фильтр-чипа; счётчик needsWater — на чипе (RN-паритет).
            waitUntilAtLeastOneExists(hasText("Search plants..."), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("All"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Needs Water (2)"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Healthy"), TIMEOUT)
            // Карточки с пилюлями статуса (сортировка по срочности —
            // DashboardLogicTest; здесь — само наличие).
            waitUntilAtLeastOneExists(hasSubText("3 days overdue"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Water today"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("6 days left"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Basil"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Monstera"), TIMEOUT)
            // Переключатели вида — a11y-имена из ресурсов.
            waitUntilAtLeastOneExists(hasContentDescription("List view"), TIMEOUT)
            waitUntilAtLeastOneExists(hasContentDescription("Card view"), TIMEOUT)
            waitUntilAtLeastOneExists(hasContentDescription("Grid view"), TIMEOUT)
            // Массовые действия НЕ видны на фильтре All (RN-паритет: только
            // needsWater при needsWaterCount>0).
            onNodeWithText("Water All").assertDoesNotExist()
            onNodeWithText("Postpone All").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Фильтр-чипы: наборы растений и видимость массовых кнопок
    // ------------------------------------------------------------------

    @Test
    fun filter_tabs_switch_sets_and_bulk_actions_only_on_needs_water() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()

            // Needs Water: overdue + today, healthy скрыт; массовые кнопки видны.
            clickAt(hasClickAction() and hasText("Needs Water (2)"))
            waitUntilAtLeastOneExists(hasText("Water All"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Postpone All"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Basil"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Monstera"), TIMEOUT)

            // Healthy: только Monstera; массовые кнопки скрыты.
            clickAt(hasClickAction() and hasText("Healthy"))
            waitUntilAtLeastOneExists(hasText("Monstera"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Fern"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Basil"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Water All"), TIMEOUT)

            // Обратно на All: все три, массовых нет.
            clickAt(hasClickAction() and hasText("All"))
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Monstera"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Water All"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Полив: оптимистичная запись + PATCH, пилюля → healthy
    // ------------------------------------------------------------------

    @Test
    fun water_action_sendsPatch_and_status_becomes_healthy() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()

            // Кнопка полива карточки Fern — a11y-имя из ресурсов
            // («Water plant Fern»); здоровые растения кнопки не имеют.
            waitUntilAtLeastOneExists(hasContentDescription("Water plant Fern"), TIMEOUT)
            clickAt(hasClickAction() and hasContentDescription("Water plant Fern"))

            // GtWaterButton держит RN-ритм: лид до мутации → затем PATCH.
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 1 }
            assertEquals("/api/plants/fern", graph.server.lastPatchPath)
            val body = graph.server.lastPatchBody.orEmpty()
            assertTrue(
                body.contains("\"last_watered_date\":\"${pickerToday()}\""),
                "патч несёт сегодняшнюю дату: $body",
            )

            // Оптимистичный статус: Fern полит сегодня → «7 days left»
            // (Room уже отдал карточку, refetch не требовался), кнопки полива
            // у Fern больше нет.
            waitUntilAtLeastOneExists(hasText("7 days left"), TIMEOUT)
            waitUntilDoesNotExist(hasContentDescription("Water plant Fern"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Поиск: фильтр по имени, очистка, отдельный empty-state
    // ------------------------------------------------------------------

    @Test
    fun search_filters_cards_clears_and_shows_empty_state() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()

            // Ключевое действие: ввод в поиск → остаётся только Basil.
            onNode(hasSetTextAction()).performTextInput("bas")
            waitUntilDoesNotExist(hasText("Fern"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Monstera"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Basil"), TIMEOUT)

            // Кнопка очистки → все три карточки возвращаются.
            clickAt(hasClickAction() and hasContentDescription("Clear search"))
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Monstera"), TIMEOUT)

            // Без совпадений — отдельный empty-state (не «нет растений»).
            onNode(hasSetTextAction()).performTextInput("zzz")
            waitUntilAtLeastOneExists(hasText("No plants found"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Try a different search term"), TIMEOUT)
            waitUntilDoesNotExist(hasText("Fern"), TIMEOUT)

            // Очистка — список снова на месте.
            clickAt(hasClickAction() and hasContentDescription("Clear search"))
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
