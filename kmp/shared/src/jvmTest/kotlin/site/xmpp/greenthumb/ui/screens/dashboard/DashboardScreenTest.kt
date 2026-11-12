@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
import kotlinx.coroutines.CompletableDeferred
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
 *   «Water (2)», карточки со статус-пилюлями; массовые кнопки на фильтре
 *   All отсутствуют (RN-паритет — только на needsWater);
 * - фильтры (ключевое действие): «Water (2)» → массовые кнопки видны,
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
        @Volatile var plantsEmpty = false

        /** When > 0, GET /api/plants returns this many plants instead of the three-plant fixture. */
        @Volatile var plantCount = 0

        /** GET /api/plants answers 500 while set (test-only fault of the existing network boundary). */
        @Volatile var failGets = false

        /** When set, GET /api/plants waits for it after counting the request (an in-flight refresh). */
        @Volatile var holdGet: CompletableDeferred<Unit>? = null

        /** Long Cyrillic/Latin grid names with 1–3 digit day counts instead of the three-plant fixture. */
        @Volatile var longNames = false
        var gridNamePlants = LONG_NAME_PLANTS

        var waterAllCount = 0
        var postponeAllCount = 0
        val bulkRequests = mutableListOf<String>()

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        // Season off: under the default auto mode the fixture's intervals stretch in the dormant months (Nov-Feb).
        private fun userJson(): String =
            """{"user":{"id":73,"name":"kmp-val-dash","recovery_key":"fixture-not-credential",""" +
                """"season_mode":"off","created_at":"2026-09-27T10:00:00.000Z"}}"""

        /**
         * Фикстура из трёх растений; даты относительно СЕГОДНЯШНЕГО дня
         * [pickerToday] — того же, что читает экран (статусы пилюль
         * детерминированы, UTC/зона теста не влияют).
         */
        private fun plantsJson(): String {
            if (plantsEmpty) return "[]"
            val today = pickerToday()
            fun daysAgo(days: Long): String =
                LocalDate.fromEpochDays(today.toEpochDays() - days).toString()
            if (longNames) {
                return gridNamePlants.joinToString(separator = ",", prefix = "[", postfix = "]") { (id, name, wateredAgo) ->
                    """{"id":"$id","user_id":"73","name":"$name","location":"Shelf",""" +
                        """"photo_url":"","water_frequency_days":7,"last_watered_date":"${daysAgo(wateredAgo)}",""" +
                        """"notes":"","created_at":"2026-09-01T10:00:00.000Z"}"""
                }
            }
            if (plantCount > 0) {
                // «Plant NN» watered NN days ago, every 30 days: sorted by urgency, «Plant 00» is the last card.
                return (0 until plantCount).joinToString(separator = ",", prefix = "[", postfix = "]") { n ->
                    val name = "Plant " + n.toString().padStart(2, '0')
                    """{"id":"p$n","user_id":"73","name":"$name","location":"Shelf",""" +
                        """"photo_url":"","water_frequency_days":30,"last_watered_date":"${daysAgo(n.toLong())}",""" +
                        """"notes":"","created_at":"2026-09-01T10:00:00.000Z"}"""
                }
            }
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
                    holdGet?.await()
                    if (failGets) {
                        respond("""{"error":"fixture failure"}""", HttpStatusCode.InternalServerError, JSON_HEADERS)
                    } else {
                        respond(plantsJson(), HttpStatusCode.OK, JSON_HEADERS)
                    }
                }
                path == "/api/plants/water-all" || path == "/api/plants/postpone-all" -> {
                    if (path.endsWith("water-all")) waterAllCount++ else postponeAllCount++
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    bulkRequests += "${request.method.value} $path body=[$body]"
                    respond("""{"success":true,"count":2}""", HttpStatusCode.OK, JSON_HEADERS)
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
    private fun androidx.compose.ui.test.ComposeUiTest.openDashboard(firstVisible: String = "Fern") {
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
        waitUntilAtLeastOneExists(hasText(firstVisible), TIMEOUT)
    }

    /**
     * One pull-down gesture in the content area below the header, search and
     * chips (the desktop test window is 1024×768).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.pullDownOnce() {
        onRoot().performTouchInput {
            swipeDown(startY = height * 0.45f, endY = height * 0.85f, durationMillis = 400)
        }
    }

    /** Waits for exactly one extra GET /api/plants after [before]. */
    private fun androidx.compose.ui.test.ComposeUiTest.assertOneRefresh(before: Int) {
        val h = harness!!
        waitUntil(timeoutMillis = TIMEOUT) { h.server.getCount >= before + 1 }
        waitForIdle()
        assertEquals(before + 1, h.server.getCount, "one pull-down = one GET /api/plants")
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
            waitUntilAtLeastOneExists(hasText("Water (2)"), TIMEOUT)
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
            clickAt(hasClickAction() and hasText("Water (2)"))
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

    // ------------------------------------------------------------------
    // Pull-to-refresh: empty states sync too (VAL-APP-018)
    // ------------------------------------------------------------------

    @Test
    fun pull_to_refresh_on_zero_plants_empty_state_syncs_and_shows_new_plant() {
        val graph = newHarness()
        graph.server.plantsEmpty = true
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard(firstVisible = "No plants yet")
            waitForIdle()
            val before = graph.server.getCount

            // A plant appears on the server; no tab switch or resume, only the swipe.
            graph.server.plantsEmpty = false
            pullDownOnce()
            assertOneRefresh(before)
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // VAL-CAM-001: icon-only smart camera button on the filled and the empty dashboard.
    private fun androidx.compose.ui.test.ComposeUiTest.assertSmartCameraOpens() {
        val button = hasContentDescription("Smart camera") and hasClickAction()
        waitUntilAtLeastOneExists(button, TIMEOUT)
        onAllNodes(button).assertCountEquals(1)
        onNode(button).assertWidthIsAtLeast(Sizes.touch).assertHeightIsAtLeast(Sizes.touch)
        val shown = onNode(button).fetchSemanticsNode().config
        assertTrue(
            !shown.contains(SemanticsProperties.Text) || shown[SemanticsProperties.Text].all { it.text.isEmpty() },
            "the smart camera button has no text",
        )
        clickAt(button)
        // VAL-CAM11-001: the viewfinder opens at once, without the M10 mode-choice screen.
        waitUntilAtLeastOneExists(hasTestTag("smartcam_viewfinder"), TIMEOUT)
        onNode(hasTestTag("smartcam_mode_plant") and hasText("Plant")).assertExists()
        onNode(hasTestTag("smartcam_mode_disease") and hasText("Problem")).assertExists()
        onNode(hasTestTag("smartcam_close")).assertExists()
        onAllNodes(hasText("Take photo")).assertCountEquals(0)
    }

    @Test
    fun smart_camera_button_on_filled_dashboard_opens_the_smart_camera() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            onNode(hasContentDescription("Add a new plant")).assertExists()
            assertSmartCameraOpens()
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun smart_camera_button_on_empty_dashboard_opens_the_smart_camera() {
        val graph = newHarness()
        graph.server.plantsEmpty = true
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard(firstVisible = "No plants yet")
            assertSmartCameraOpens()
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun pull_to_refresh_on_empty_search_result_syncs() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            onNode(hasSetTextAction()).performTextInput("zzz")
            waitUntilAtLeastOneExists(hasText("No plants found"), TIMEOUT)
            waitForIdle()
            val before = graph.server.getCount

            pullDownOnce()
            assertOneRefresh(before)
            waitUntilAtLeastOneExists(hasText("No plants found"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun pull_to_refresh_on_plant_list_still_syncs() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            waitForIdle()
            val before = graph.server.getCount

            pullDownOnce()
            assertOneRefresh(before)
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // M11c item 1: the list runs under the floating camera and «+» buttons
    // ------------------------------------------------------------------

    /**
     * VAL-DASH12-001/002 at 384 dp: in list, card and grid modes the list viewport reaches the dashboard bottom
     * (the buttons sit [Spacing.xl] above it, no reserved band), cards are visible beside/under the buttons before
     * the end, and at the end the whole last card and its status pill are above both buttons.
     */
    private fun underFloatingButtonsCase(fontScale: Float, language: AppLanguage = AppLanguage.En) {
        val graph = newHarness()
        graph.server.plantCount = 20
        presetEnglishSignedOut()
        runDesktopComposeUiTest(width = 384, height = 800) {
            setContent {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push)
                }
            }
            openDashboard(firstVisible = "Plant 19")
            runBlocking { graph.settings.setLanguage(language) }
            waitForIdle()
            val ru = language == AppLanguage.Ru
            val cameraMatcher = hasContentDescription(if (ru) "Умная камера" else "Smart camera") and hasClickAction()
            val openPrefix = if (ru) "Открыть карточку растения" else "Open plant card"
            val lastCard = hasContentDescription("$openPrefix Plant 00")
            val toggles = if (ru) {
                listOf("Отображение списком", "Отображение карточками", "Отображение сеткой")
            } else {
                listOf("List view", "Card view", "Grid view")
            }
            for ((modeIndex, toggle) in toggles.withIndex()) {
                // Смена языка перерисовывает шапку: подпись переключателя появляется не в том же кадре.
                waitUntilAtLeastOneExists(hasClickAction() and hasContentDescription(toggle), TIMEOUT)
                clickAt(hasClickAction() and hasContentDescription(toggle))
                waitForIdle()
                waitUntilAtLeastOneExists(hasTestTag("dashboard_plants"), TIMEOUT)
                val camera = onNode(cameraMatcher).getBoundsInRoot()
                val add = onNode(hasContentDescription(if (ru) "Добавить новое растение" else "Add a new plant"))
                    .getBoundsInRoot()
                val buttonsTop = minOf(camera.top, add.top)
                val list = onNodeWithTag("dashboard_plants").getBoundsInRoot()
                assertTrue(
                    list.bottom >= camera.bottom + Spacing.xl - 1.dp,
                    "$toggle at $fontScale: the list reaches the dashboard bottom under the buttons: list=$list camera=$camera",
                )
                val cards = onAllNodes(hasContentDescription(openPrefix, substring = true))
                    .fetchSemanticsNodes()
                assertTrue(
                    cards.any { node ->
                        with(density) { node.boundsInRoot.bottom.toDp() } > buttonsTop &&
                            with(density) { node.boundsInRoot.top.toDp() } < camera.bottom
                    },
                    "$toggle at $fontScale: before the end a card is drawn beside/under the buttons",
                )
                onNodeWithTag("dashboard_plants").performScrollToIndex(19)
                // Scroll to the actual end, including content padding, not just to the last item.
                onNodeWithTag("dashboard_plants").performSemanticsAction(
                    androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
                ) { it(0f, 10000f) }
                waitForIdle()
                val card = onNode(lastCard).getBoundsInRoot()
                assertEquals(camera, onNode(cameraMatcher).getBoundsInRoot(), "camera stays fixed")
                assertEquals(
                    add,
                    onNode(hasContentDescription(if (ru) "Добавить новое растение" else "Add a new plant")).getBoundsInRoot(),
                    "add stays fixed",
                )
                println("M17 $language font=$fontScale $toggle: lastCard=$card buttonsTop=$buttonsTop")
                assertTrue(
                    card.bottom <= buttonsTop,
                    "$toggle at $fontScale: the last card scrolls fully above the buttons: card=$card buttonsTop=$buttonsTop",
                )
                assertTrue(card.bottom <= list.bottom && card.top >= list.top, "$toggle: the last card is inside the list")
                if (modeIndex != 2) {
                    val pill = onNode(
                        hasText(if (ru) "Осталось 30 дней" else "30 days left"),
                        useUnmergedTree = true,
                    ).getBoundsInRoot()
                    assertTrue(
                        pill.bottom <= buttonsTop && pill.bottom <= card.bottom,
                        "$toggle at $fontScale: the status pill is above the buttons: pill=$pill buttonsTop=$buttonsTop",
                    )
                    assertTrue(pill.right > pill.left && pill.bottom > pill.top, "$toggle: the status pill is laid out")
                }
                onNodeWithTag("dashboard_plants").performScrollToIndex(0)
                waitForIdle()
            }
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun plant_list_runs_under_the_floating_buttons_at_font_1_15() = underFloatingButtonsCase(1.15f)

    @Test
    fun plant_list_runs_under_the_floating_buttons_at_font_1_3() = underFloatingButtonsCase(1.3f)

    @Test
    fun m17_scroll_clearance_en_font_1_0() = underFloatingButtonsCase(1f)

    @Test
    fun m17_scroll_clearance_ru_font_1_0() = underFloatingButtonsCase(1f, AppLanguage.Ru)

    @Test
    fun m17_scroll_clearance_ru_font_1_3() = underFloatingButtonsCase(1.3f, AppLanguage.Ru)

    private fun gridWordBoundariesCase(fontScale: Float, language: AppLanguage) {
        val graph = newHarness()
        val names = listOf(
            "Диффенбахия", "Dieffenbachia", "Монстера большая", "Monstera deliciosa",
            "Оченьдлинноеназваниебезпробелов", "ExtraordinarilyLongUnbrokenPlantName",
        )
        graph.server.longNames = true
        graph.server.gridNamePlants = names.mapIndexed { index, name -> Triple("word$index", name, 1L) }
        presetEnglishSignedOut()
        runBlocking { graph.settings.setLayoutMode(LayoutMode.Grid) }
        runDesktopComposeUiTest(width = 384, height = 800) {
            val baseFontSize = site.xmpp.greenthumb.ui.theme.GtTypeScale.labelSmall.fontSize.value
            setContent {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push)
                }
            }
            openDashboard(firstVisible = names.first())
            runBlocking { graph.settings.setLanguage(language) }
            waitForIdle()
            for ((index, name) in names.withIndex()) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                onNodeWithTag("dashboard_grid_name_word$index", useUnmergedTree = true)
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                        assertTrue(it(results))
                    }
                val result = results.single()
                // labelSmall is the unchanged base style, 13 sp in GtTypography.
                val actualFontSize = result.layoutInput.style.fontSize.value
                assertTrue(actualFontSize >= baseFontSize * 0.8f - 0.01f && actualFontSize <= baseFontSize)
                if (' ' !in name) {
                    assertEquals(1, result.lineCount, "$language $name at $fontScale must not split mid-word")
                } else {
                    assertEquals(2, result.lineCount, "$name still wraps at a space")
                    val end = result.getLineEnd(0, visibleEnd = true)
                    assertTrue(name[end - 1].isWhitespace() || name[end].isWhitespace(), "$name breaks only at spaces")
                }
                if (index >= 4) {
                    assertEquals(baseFontSize * 0.8f, actualFontSize, 0.01f, "ellipsis only after shrinking to the floor")
                    assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, result.layoutInput.overflow)
                    assertTrue(result.getLineEnd(0, visibleEnd = true) < name.length, "$name is truncated at the floor")
                }
                println(
                    "M17 $language font=$fontScale $name: lines=${result.lineCount} size=$actualFontSize " +
                        "visibleEnd=${result.getLineEnd(0, visibleEnd = true)} overflow=${result.layoutInput.overflow}",
                )
            }
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun m17_grid_word_boundaries_en_font_1_0() = gridWordBoundariesCase(1f, AppLanguage.En)

    @Test
    fun m17_grid_word_boundaries_en_font_1_3() = gridWordBoundariesCase(1.3f, AppLanguage.En)

    @Test
    fun m17_grid_word_boundaries_ru_font_1_0() = gridWordBoundariesCase(1f, AppLanguage.Ru)

    @Test
    fun m17_grid_word_boundaries_ru_font_1_3() = gridWordBoundariesCase(1.3f, AppLanguage.Ru)

    // ------------------------------------------------------------------
    // M12a UX1: grid names and day counts in separate regions
    // ------------------------------------------------------------------

    /**
     * VAL-UX12-002 at 384 dp: long Cyrillic and Latin names and 1–3 digit day counts do not overlap, both stay inside
     * the square cell, the name keeps a readable share of the width, and due cells keep their water button.
     */
    private fun gridNamesAndDaysCase(fontScale: Float) {
        val graph = newHarness()
        graph.server.longNames = true
        presetEnglishSignedOut()
        runBlocking { graph.settings.setLayoutMode(LayoutMode.Grid) }
        runDesktopComposeUiTest(width = 384, height = 800) {
            setContent {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push)
                }
            }
            openDashboard(firstVisible = LONG_NAME_PLANTS[2].second)
            waitForIdle()
            for ((id, name, _) in LONG_NAME_PLANTS) {
                val cell = onNode(hasContentDescription("Open plant card $name")).getBoundsInRoot()
                val label = onNodeWithTag("dashboard_grid_name_$id", useUnmergedTree = true).getBoundsInRoot()
                val days = onNodeWithTag("dashboard_grid_days_$id", useUnmergedTree = true).getBoundsInRoot()
                val overlap = label.left < days.right && days.left < label.right &&
                    label.top < days.bottom && days.top < label.bottom
                assertTrue(!overlap, "$name at $fontScale: name $label and day count $days overlap")
                for ((part, rect) in listOf("name" to label, "day count" to days)) {
                    assertTrue(
                        rect.left >= cell.left - 1.dp && rect.right <= cell.right + 1.dp &&
                            rect.top >= cell.top - 1.dp && rect.bottom <= cell.bottom + 1.dp,
                        "$name at $fontScale: the $part $rect stays inside the cell $cell",
                    )
                }
                assertTrue(
                    label.right - label.left >= (cell.right - cell.left) * 0.4f,
                    "$name at $fontScale: the name keeps a readable width: $label in $cell",
                )
                assertTrue(
                    kotlin.math.abs((cell.right - cell.left).value - (cell.bottom - cell.top).value) <= 1f,
                    "$name at $fontScale: the cell photo stays square: $cell",
                )
            }
            // Due cells keep the separate water action, and it covers neither the name nor the day count.
            for (index in listOf(0, 1, 2)) {
                val (id, name, _) = LONG_NAME_PLANTS[index]
                val water = onNode(hasClickAction() and hasContentDescription("Water plant $name")).getBoundsInRoot()
                for (tag in listOf("dashboard_grid_name_$id", "dashboard_grid_days_$id")) {
                    val rect = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
                    val overlap = rect.left < water.right && water.left < rect.right &&
                        rect.top < water.bottom && water.top < rect.bottom
                    assertTrue(!overlap, "$name at $fontScale: $tag $rect overlaps the water button $water")
                }
            }
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun grid_long_names_and_day_counts_do_not_overlap_at_font_1_0() = gridNamesAndDaysCase(1.0f)

    @Test
    fun grid_long_names_and_day_counts_do_not_overlap_at_font_1_3() = gridNamesAndDaysCase(1.3f)

    // ------------------------------------------------------------------
    // M12a UX6: first-load failure has an explicit Retry
    // ------------------------------------------------------------------

    @Test
    fun initial_load_failure_shows_retry_single_inflight_and_recovers_with_plants() {
        val graph = newHarness()
        graph.server.failGets = true
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard(firstVisible = LOAD_FAILED_EN)
            // Truthful error: no confirmed-empty garden and no claim that saved plants are shown.
            onNodeWithText("No plants yet").assertDoesNotExist()
            onNode(hasText("showing saved data", substring = true)).assertDoesNotExist()
            onNodeWithTag("dashboard_retry").assertIsEnabled()
            onNode(hasTestTag("dashboard_retry") and hasText("Try again")).assertExists()

            // Five fast taps while the refresh is in flight: one GET, pending feedback, button disabled.
            val gate = CompletableDeferred<Unit>()
            graph.server.holdGet = gate
            val before = graph.server.getCount
            repeat(5) { onNodeWithTag("dashboard_retry").performClick() }
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.getCount >= before + 1 }
            waitUntilAtLeastOneExists(hasTestTag("dashboard_retry_pending"), TIMEOUT)
            onNodeWithTag("dashboard_retry").assertIsNotEnabled()

            // The retried refresh fails again: the error stays and Retry is available again.
            gate.complete(Unit)
            waitUntilDoesNotExist(hasTestTag("dashboard_retry_pending"), TIMEOUT)
            waitForIdle()
            assertEquals(before + 1, graph.server.getCount, "five taps = one GET /api/plants")
            onNodeWithText(LOAD_FAILED_EN).assertExists()
            onNodeWithText("No plants yet").assertDoesNotExist()
            onNodeWithTag("dashboard_retry").assertIsEnabled()

            // Server recovers: Retry shows the real plants.
            graph.server.holdGet = null
            graph.server.failGets = false
            onNodeWithTag("dashboard_retry").performClick()
            waitUntilAtLeastOneExists(hasText("Fern"), TIMEOUT)
            onNodeWithText(LOAD_FAILED_EN).assertDoesNotExist()
            onNodeWithTag("dashboard_retry").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun initial_load_retry_with_empty_success_shows_the_empty_garden() {
        val graph = newHarness()
        graph.server.failGets = true
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard(firstVisible = LOAD_FAILED_EN)

            graph.server.failGets = false
            graph.server.plantsEmpty = true
            onNodeWithTag("dashboard_retry").performClick()
            waitUntilAtLeastOneExists(hasText("No plants yet"), TIMEOUT)
            onNodeWithText(LOAD_FAILED_EN).assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun initial_load_failure_then_reconnect_with_empty_success_shows_the_empty_garden() {
        val graph = newHarness()
        graph.server.failGets = true
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard(firstVisible = LOAD_FAILED_EN)

            graph.connectivity.setOnline(false)
            waitForIdle()
            graph.server.failGets = false
            graph.server.plantsEmpty = true
            val before = graph.server.getCount

            // Connectivity restore (not Retry) performs the refresh that succeeds with an empty list.
            graph.connectivity.setOnline(true)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.getCount >= before + 1 }
            waitUntilAtLeastOneExists(hasText("No plants yet"), TIMEOUT)
            onNodeWithText(LOAD_FAILED_EN).assertDoesNotExist()
            onNodeWithTag("dashboard_retry").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun later_refresh_failure_keeps_the_loaded_plants() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            waitForIdle()
            val before = graph.server.getCount

            graph.server.failGets = true
            pullDownOnce()
            assertOneRefresh(before)
            waitUntilAtLeastOneExists(hasText("showing saved data", substring = true), TIMEOUT)
            onNodeWithText("Fern").assertExists()
            onNodeWithText("Monstera").assertExists()
            onNodeWithText(LOAD_FAILED_EN).assertDoesNotExist()
            onNodeWithTag("dashboard_retry").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // M12a UX3: bulk actions explain their server-defined scope
    // ------------------------------------------------------------------

    @Test
    fun bulk_actions_explain_scope_with_search_hidden_due_plants_and_keep_requests() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            clickAt(hasClickAction() and hasText("Water (2)"))
            waitUntilAtLeastOneExists(hasText("Postpone All"), TIMEOUT)

            // Fern is due but hidden by the search; the explanation still covers it.
            onNode(hasSetTextAction()).performTextInput("bas")
            waitUntilDoesNotExist(hasText("Fern"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Basil"), TIMEOUT)
            val scope = onNodeWithTag("dashboard_bulkScope").fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString(" ") { it.text }
            assertTrue(scope.contains("every plant that needs water"), scope)
            assertTrue(scope.contains("hidden by search"), scope)
            assertTrue(scope.contains("last watering to yesterday"), scope)
            assertTrue(!scope.contains("day later") && !scope.contains("by one day"), "no one-day shift promise: $scope")
            assertTrue(scope.none { it.isDigit() }, "no local count claim: $scope")

            // Requests are unchanged: one bare POST each, the server chooses the plants.
            clickAt(hasClickAction() and hasText("Postpone All"))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.postponeAllCount == 1 }
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Water All") and hasClickAction(), TIMEOUT)
            clickAt(hasClickAction() and hasText("Water All"))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.waterAllCount == 1 }
            waitForIdle()
            assertEquals(
                listOf("POST /api/plants/postpone-all body=[]", "POST /api/plants/water-all body=[]"),
                graph.server.bulkRequests.toList(),
            )

            runOnIdle { graph.close() }
        }
    }
    private companion object {
        const val TIMEOUT: Long = 10_000L

        const val LOAD_FAILED_EN: String = "Could not load your plants. Check your connection and try again."

        /** id, name, days since watering (every 7 days): -393, -9, -1, 6 and 0 days. */
        val LONG_NAME_PLANTS: List<Triple<String, String, Long>> = listOf(
            Triple("g1", "Сансевиерия трёхполосая Лауренти", 400L),
            Triple("g2", "Монстера деликатесная вариегатная Альбо Борсигиана", 16L),
            Triple("g3", "Chlorophytum comosum Variegatum Ocean", 8L),
            Triple("g4", "Philodendron hederaceum Brasil Lemon Lime", 1L),
            Triple("g5", "Замиокулькас замиелистный", 7L),
        )
    }
}
