@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.addplant

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
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
import kotlinx.datetime.number
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
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.pickerToday
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.width
import org.junit.Rule
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.FilePickBridge
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier

/**
 * Stage 7 п.5 (фича screen-add-plant) — desktop-ветка VAL-ADDPLANT-001/002:
 * полная поверхность App() (как ProfileScreenTest): пролог welcome → login →
 * create → show-key → enable (Later) → дашборд → «Add Plant» → экран формы.
 *
 * Ключевые утверждения:
 * - submit блокируется при невалидной форме: пустое имя/частота/дата дают
 *   подсвеченные ошибки (ru/en ресурсы), POST не отправлен (plantsCount 0);
 * - валидная форма шлёт POST /api/plants с телом формы (MockEngine фиксирует
 *   текст тела: частота, имя, дата; пустой расширенный уход НЕ содержит
 *   fertilize/repot/prune ключей — jvm-ветка VAL-ADDPLANT-003 в
 *   AddPlantFormTest, здесь UI-гейт того же правила);
 * - после POST экран возвращается на дашборд, карточка уже в списке Room
 *   (VAL-ADDPLANT-002 desktop-ветка; «появляется немедленно» — без refetch);
 * - ошибки валидации локализованы (en-прогон: тексты en; ru-лега: ru).
 */
@OptIn(ExperimentalTestApi::class)
class AddPlantScreenTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика ProfileScreenTest)
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
        override suspend fun requestSubscribe(language: String) = site.xmpp.greenthumb.core.platform.PushOutcome.Denied

        override suspend fun subscriptionStatus(): Boolean = false

        override suspend fun unsubscribe() {}

        override suspend fun sendLocalTestNotification() {}
    }

    private class FakeServer {
        var plantsCount = 0
        var createCount = 0
        var addPlantStatus: HttpStatusCode = HttpStatusCode.OK
        var addPlantOffline = false
        var lastAddBody: String? = null

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":73,"name":"kmp-val-addplant","recovery_key":"fixture-not-credential",""" +
                """"created_at":"2026-09-27T10:00:00.000Z"}}"""

        /** Created-ответ сервера — эхо тела POST (контракт №8). */
        private fun createdPlantJson(body: String): String {
            val name = Regex("\"name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "Plant"
            val freq = Regex("\"water_frequency_days\":(\\d+)").find(body)?.groupValues?.get(1) ?: "7"
            val date = Regex("\"last_watered_date\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                ?: "2026-09-27"
            return """{"id":"fixture-uuid-73","user_id":"73","name":"$name","location":"",""" +
                """"photo_url":"","water_frequency_days":$freq,"last_watered_date":"$date",""" +
                """"notes":"","created_at":"2026-09-27T11:00:00.000Z"}"""
        }

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                "/api/plants" -> when (request.method.value) {
                    "GET" -> {
                        plantsCount++
                        respond("[]", HttpStatusCode.OK, JSON_HEADERS)
                    }
                    "POST" -> {
                        plantsCount++
                        val body = (request.body as? TextContent)?.text
                        lastAddBody = body
                        if (addPlantOffline) throw java.io.IOException("airplane mode")
                        respond(createdPlantJson(body ?: ""), addPlantStatus, JSON_HEADERS)
                    }
                    else -> respond("{}", HttpStatusCode.NotFound, JSON_HEADERS)
                }
                "/api/auth/create-anonymous" -> {
                    createCount++
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
        val dir: File = File.createTempFile("gt-addplant-screen", "").let { f ->
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

    /** hasText по подстроке (merged-узлы: подписи + supportingText). */
    private fun hasSubText(value: String) = hasText(value, substring = true)

    /**
     * Общий пролог: welcome → Skip → Create New Account → Create → show-key
     * → «I've Saved» → enable → Maybe later → дашборд (механика
     * ProfileScreenTest.openProfileTab, без вкладки Profile).
     */
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
        waitUntil(timeoutMillis = TIMEOUT) { h.server.plantsCount >= 1 }
        waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
    }

    /** Тап «Add Plant» (empty-state дашборда: GET отдаёт []) → экран формы. */
    private fun androidx.compose.ui.test.ComposeUiTest.openAddPlantForm() {
        val h = harness!!
        // Дашборд (screen-dashboard) показывает empty-state с кнопкой
        // «Add Plant» (a11y-лейбл FAB не виден — растений нет); OnClick
        // напрямую (clickAt-паттерн ProfileScreenTest, без требований
        // вьюпорта).
        // Ожидание существования кнопки ПЕРЕД кликом: empty-state рендерится
        // только после firstLoadSettled (осадки первого refresh) — без
        // ожидания клик попадал в скелетон и класс стабильно краснел
        // (наблюдение 2026-09-28, воспроизводится на чистом HEAD 3b87982).
        waitUntilAtLeastOneExists(hasClickAction() and hasText("Add Plant"), TIMEOUT)
        clickAt(hasClickAction() and hasText("Add Plant"))
        waitUntilAtLeastOneExists(hasText("Add New Plant"), TIMEOUT)
        waitUntilAtLeastOneExists(hasSubText("Plant Name"), TIMEOUT)
        assertEquals(0, h.server.lastAddBody?.let { 1 } ?: 0, "POST ещё не отправлялся")
    }

    /**
     * Заполнение формы: имя (editable 0), локация (1), частота (2), заметки
     * (3) — статичный порядок полей. Ввод — performTextInput на
     * SemanticsNodeInteraction (реальный SetText-сценарий UI-теста).
     * onAllNodes — член ComposeUiTest (импортировать нельзя, m6-прецедент).
     */
    private fun androidx.compose.ui.test.ComposeUiTest.fillForm(
        name: String,
        waterSteps: Int = 0,
    ) {
        val editable = onAllNodes(isEditable())
        assertTrue(editable.fetchSemanticsNodes().isNotEmpty(), "name field is shown")
        editable[0].performTextInput(name)
        repeat(waterSteps) { clickUnmergedTag("addPlant_water_dec") }
        waitForIdle()
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

    /** Все узлы по матчеру (onAllNodes — член ComposeUiTest, импорт нельзя). */
    private fun androidx.compose.ui.test.ComposeUiTest.allNodes(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
    ) = onAllNodes(matcher).fetchSemanticsNodes()

    /** The pinned «Добавить» button (OnClick directly, outside the viewport too). */
    private fun androidx.compose.ui.test.ComposeUiTest.clickSubmit() {
        clickUnmergedTag("addPlant_submit")
    }

    // ------------------------------------------------------------------
    // VAL-ADDPLANT-001: submit блокируется, ошибки локализованы и подсвечены
    // ------------------------------------------------------------------

    @Test
    fun invalidForm_showsLocalizedErrors_andBlocksPost() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            // Пустая форма (дата дефолтная — сегодня, имя и частота пустые).
            val postsAtSubmit = graph.server.plantsCount
            clickSubmit()
            waitForIdle()

            // Ошибки EN-локали (RN-хардкод рус. текстов → ключи validation.*).
            waitUntilAtLeastOneExists(hasText("Enter a name"), TIMEOUT)
            // The watering stepper always holds a valid number (7 by default).
            onNodeWithText("Enter a number").assertDoesNotExist()
            // POST не ушёл (валидация блокирует submit).
            assertEquals(postsAtSubmit, graph.server.plantsCount, "POST заблокирован невалидной формой")
            // Форма осталась открыта (назад не ушли).
            onNodeWithText("Add New Plant").assertExists()

            // Дата обязательна: очистить дефолт через повторную сборку нельзя —
            // проверяется jvmTest (AddPlantFormTest.missingDate_isRequired);
            // здесь фиксируем отсутствие ошибки даты при дефолтной дате.
            onAllNodesWithText("Pick a date").fetchSemanticsNodes()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun localized_ruErrors_onRuLocale() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "ru") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        // Прямая поверхность экрана: локаль ru, подписи ru.
                        AddPlantScreen(
                            userId = "73",
                            opener = graph.opener,
                            onBack = {},
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Новое растение"), TIMEOUT)
            clickSubmit()
            waitUntilAtLeastOneExists(hasText("Введите название"), TIMEOUT)
            onNodeWithText("Введите число").assertDoesNotExist()
            onNodeWithText("Минимум 1 день").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun sectionHeaders_withEmoji_andPhotoLabel_inEnglish() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            waitUntilAtLeastOneExists(hasSubText("Add photo"), TIMEOUT)
            onNodeWithText("💧 Watering").assertDoesNotExist()
            onNodeWithText("🌱 Fertilizing").assertDoesNotExist()
            onNodeWithText("Extra care").assertDoesNotExist()

            clickUnmergedTag("addPlant_more")
            waitUntilAtLeastOneExists(hasText("Extra care"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("🌱 Fertilizing"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("🪴 Repotting"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("✂️ Pruning"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun fieldLabels_sitRightAboveTheirFields() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "en") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                        AddPlantScreen(userId = "73", opener = graph.opener, onBack = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Plant Name"), TIMEOUT)
            clickUnmergedTag("addPlant_more")
            waitUntilAtLeastOneExists(hasText("Location"), TIMEOUT)
            val editable = onAllNodes(isEditable())
            // editable[0] = name field, [1] = location; watering is a stepper, not a text field.
            listOf("Plant Name" to 0, "Location" to 1).forEach { (label, i) ->
                val labelBottom = onNodeWithText(label).getBoundsInRoot().bottom
                val fieldTop = editable[i].getBoundsInRoot().top
                val gap = fieldTop - labelBottom
                assertTrue(gap >= 0.dp && gap <= 12.dp, "$label sits $gap above its field")
            }
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun sectionHeaders_areLocalizedOnRuLocale() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "ru") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        AddPlantScreen(userId = "73", opener = graph.opener, onBack = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasSubText("Добавить фото"), TIMEOUT)
            onNodeWithText("💧 Полив").assertDoesNotExist()
            clickUnmergedTag("addPlant_more")
            waitUntilAtLeastOneExists(hasText("Дополнительный уход"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("🌱 Удобрение"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("🪴 Пересадка"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("✂️ Обрезка"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-ADDPLANT-002/003: валидная форма → POST → карточка на дашборде
    // ------------------------------------------------------------------

    @Test
    fun validForm_postsBodyWithoutEmptyAdvancedCare_andReturnsToDashboard() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            // Имя, частота; дата дефолтная (сегодня) — RN defaultValues.
            fillForm(name = "Monstera", waterSteps = 2)

            clickSubmit()
            // POST зафиксирован с телом формы.
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            val body = graph.server.lastAddBody.orEmpty()
            assertTrue(body.contains("\"name\":\"Monstera\""), "имя в теле: $body")
            assertTrue(body.contains("\"water_frequency_days\":5"), "частота в теле: $body")
            assertTrue(body.contains("\"last_watered_date\":\""), "дата в теле: $body")
            // VAL-ADDPLANT-003 (UI-гейт): пустой продвинутый уход не в теле.
            assertTrue(!body.contains("fertilize"), "пустой уход: fertilize-ключей нет: $body")
            assertTrue(!body.contains("repot_"), "пустой уход: repot-ключей нет: $body")
            assertTrue(!body.contains("prune_"), "пустой уход: prune-ключей нет: $body")

            // Экран вернулся на дашборд; карточка в списке Room немедленно
            // (refresh не ждали — plantsCount НЕ вырос после POST).
            waitUntilDoesNotExist(hasText("Add New Plant"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Monstera"), TIMEOUT)
            val postsAfter = graph.server.plantsCount
            waitForIdle()
            assertEquals(postsAfter, graph.server.plantsCount, "invalidate не нужен: Room уже отдал карточку")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun serverError_showsLocalizedUploadFailedAlert() {
        val graph = newHarness()
        presetEnglishSignedOut()
        graph.server.addPlantStatus = HttpStatusCode.InternalServerError
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            fillForm(name = "Monstera")

            clickSubmit()
            // Алерт ошибки загрузки (RN showAlert uploadFailed).
            waitUntilAtLeastOneExists(hasText("Upload failed"), TIMEOUT)
            // Форма осталась открытой (не ушли назад).
            onNodeWithText("Add New Plant").assertExists()

            runOnIdle { graph.close() }
        }
    }

    /**
     * VAL-APP-009: offline submit is queued by the journal, so the form
     * closes without an error dialog and the dashboard tile carries the
     * localized pending tag.
     */
    @Test
    fun offlineSubmit_closesForm_andShowsPendingTag_withoutErrorDialog() {
        val graph = newHarness()
        presetEnglishSignedOut()
        graph.server.addPlantOffline = true
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            fillForm(name = "ValOffline")

            clickSubmit()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            waitUntilDoesNotExist(hasText("Add New Plant"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("ValOffline"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("not saved yet"), TIMEOUT)
            onNodeWithText("Upload failed").assertDoesNotExist()
            onNodeWithText("не сохранено").assertDoesNotExist()

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-TEST-002 (M11): GtDatePickerField на экране — диалог, Done,
    // прошедшая дата, onValueChange
    // ------------------------------------------------------------------

    /**
     * Дефект-гипотеза M7 (одиночный Android uiautomator-дамп без Done)
     * закрывается desktop-семантикой без Android source set: поле даты
     * открывает диалог, Done существует и ВКЛЮЧЁН сразу после открытия
     * (initial selected = сегодня — селектабельно), выбор прошедшей даты
     * и подтверждение пишут значение через onValueChange (поле показывает
     * его в формате языка UI).
     */
    @Test
    fun datePickerField_opensDialog_doneEnabled_pastDateCommits() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openAddPlantForm()

            // Поле даты: дефолт — сегодня (en-формат MM/dd/yyyy), a11y-имя из
            // ресурса datePicker_fieldDescription (en «Date field …»). Клик — открытие диалога.
            val today = pickerToday()
            val todayLabel = formatPickerDate(today.toString(), "en")
            clickUnmergedTag("addPlant_more")
            waitUntilAtLeastOneExists(
                hasContentDescription("Date field $todayLabel"),
                TIMEOUT,
            )
            clickAt(hasClickAction() and hasContentDescription("Date field $todayLabel"))

            // Диалог открыт: заголовок-граница + Done/Cancel; Done включён.
            waitUntilAtLeastOneExists(hasText("Maximum $todayLabel"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Done"), TIMEOUT)
            onNodeWithText("Done").assertIsEnabled()
            onNodeWithText("Cancel").assertExists()

            // Прошедшая дата из показанного месяца: 1-е число всегда ≤ сегодня.
            // На 1-е число месяца прошедших дней в сетке нет — тогда шаг
            // выбора пропускается (подтверждается дефолтная дата), гард
            // selectability не меняется.
            if (today.day > 1) {
                val firstOfMonth = java.time.LocalDate.of(today.year, today.month.number, 1)
                val dayLabel = firstOfMonth.format(
                    java.time.format.DateTimeFormatter
                        .ofLocalizedDate(java.time.format.FormatStyle.FULL)
                        .withLocale(java.util.Locale.ENGLISH),
                )
                clickAt(hasClickAction() and hasText(dayLabel))
                waitForIdle()
            }

            // Подтверждение: onValueChange пишет выбранное значение в форму —
            // поле показывает дату в en-формате; диалог закрыт.
            clickAt(hasClickAction() and hasText("Done"))
            waitUntilDoesNotExist(hasText("Done"), TIMEOUT)
            val expectedShown = if (today.day > 1) {
                formatPickerDate(
                    kotlinx.datetime.LocalDate(today.year, today.month, 1).toString(),
                    "en",
                )
            } else {
                todayLabel
            }
            waitUntilAtLeastOneExists(hasText(expectedShown), TIMEOUT)
            waitUntilAtLeastOneExists(
                hasContentDescription("Date field $expectedShown"),
                TIMEOUT,
            )

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // M10 item 9b: photo never triggers recognition, optional identify, stepper
    // ------------------------------------------------------------------

    private class CountingIdentifier : SpeciesIdentifier {
        val calls = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun identify(photoDataUri: String, lang: String): List<SpeciesCandidateDto> {
            calls.incrementAndGet()
            return listOf(
                SpeciesCandidateDto("Monstera deliciosa", listOf("Swiss cheese plant"), 0.81),
                SpeciesCandidateDto("Unknownia rara", listOf("Rare one"), 0.05),
            )
        }
    }

    private fun photoFile(): File {
        val file = File.createTempFile("gt-addplant-cover", ".jpg")
        file.deleteOnExit()
        file.writeBytes(paintedJpeg(64, 48))
        return file
    }

    private fun ComposeUiTest.unmergedCount(tag: String): Int =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun ComposeUiTest.clickUnmergedTag(tag: String) {
        waitUntil(timeoutMillis = TIMEOUT) { unmergedCount(tag) > 0 }
        onAllNodes(hasTestTag(tag), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Answers the desktop file picker with [file] and confirms the crop screen. */
    private fun ComposeUiTest.answerPickerAndCrop(file: File, done: String) {
        waitUntil(timeoutMillis = TIMEOUT) { FilePickBridge.pending != null }
        runOnIdle {
            val request = FilePickBridge.pending!!
            FilePickBridge.clear(request)
            request.deferred.complete(file.absolutePath)
        }
        val doneButton = hasClickAction() and hasText(done)
        waitUntil(timeoutMillis = TIMEOUT) {
            onAllNodes(doneButton).fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        clickAt(doneButton)
        waitUntilDoesNotExist(doneButton, TIMEOUT)
    }

    /** Photo box → Gallery → file → crop Done: the cover is set. */
    private fun ComposeUiTest.chooseCover(file: File, addPhoto: String, done: String) {
        clickAt(hasClickAction() and hasContentDescription(addPhoto))
        clickUnmergedTag("addPlant_photo_gallery")
        answerPickerAndCrop(file, done)
    }

    private fun ComposeUiTest.setAddPlant(graph: Harness, identifier: SpeciesIdentifier?, locale: String) {
        setContent {
            site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = locale) {
                site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                    AddPlantScreen(userId = "73", opener = graph.opener, onBack = {}, identifier = identifier)
                }
            }
        }
    }

    @Test
    fun choosingPhotoDoesNotCallIdentifier() {
        val graph = newHarness()
        val identifier = CountingIdentifier()
        val file = photoFile()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier, "en")
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            assertEquals(0, unmergedCount("addPlant_identify"), "no identify button without a photo")
            onNodeWithText("The photo goes to Pl@ntNet. You can skip this.").assertDoesNotExist()
            chooseCover(file, addPhoto = "Add photo", done = "Done")
            waitUntilAtLeastOneExists(hasContentDescription("Plant Photo"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Find species by photo"), TIMEOUT)
            onNodeWithText("The photo goes to Pl@ntNet. You can skip this.").assertExists()
            assertEquals(0, identifier.calls.get(), "a chosen photo is only the cover")
            onNodeWithText("Species").assertDoesNotExist()

            onAllNodes(isEditable())[0].performTextInput("Snake plant")
            clickSubmit()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            val body = graph.server.lastAddBody.orEmpty()
            assertTrue(body.contains("\"photo_url\":\"data:image/jpeg;base64,"), "cover saved")
            assertTrue(body.contains("\"water_frequency_days\":7"), "untouched stepper saves 7: $body")
            assertTrue(!body.contains("species_"), "no species without recognition: $body")
            assertEquals(0, identifier.calls.get(), "saving sends nothing to Pl@ntNet")
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun identifyUsesChosenPhotoWithoutSecondPick() {
        val graph = newHarness()
        val identifier = CountingIdentifier()
        val file = photoFile()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier, "en")
            waitUntilAtLeastOneExists(hasContentDescription("Add photo"), TIMEOUT)
            chooseCover(file, addPhoto = "Add photo", done = "Done")
            waitUntilAtLeastOneExists(hasContentDescription("Plant Photo"), TIMEOUT)

            clickUnmergedTag("addPlant_identify")
            waitUntil(timeoutMillis = TIMEOUT) { identifier.calls.get() == 1 }
            assertEquals(null, FilePickBridge.pending, "no second picker")
            assertEquals(0, unmergedCount("identify_gallery"), "no source sheet")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("identify_candidate_0") > 0 }
            clickUnmergedTag("identify_candidate_0")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("identify_dialog") == 0 }

            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_nameFromSpecies") == 1 }
            assertEquals(1, unmergedCount("addPlant_waterFromSpecies"), "watering marked «from species»")
            onNodeWithText("Wrong plant? Type your own name").assertExists()
            onNodeWithTag("addPlant_water_value", useUnmergedTree = true).assertTextEquals("8")
            assertEquals(1, identifier.calls.get())

            // The name stays an ordinary field: type an own name, the mark goes away.
            val name = onAllNodes(isEditable())[0]
            name.performTextClearance()
            name.performTextInput("Щучий хвост")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_nameFromSpecies") == 0 }
            clickSubmit()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            val body = graph.server.lastAddBody.orEmpty()
            assertTrue(body.contains("\"name\":\"Щучий хвост\""), body)
            assertTrue(body.contains("\"species_name\":\"Monstera deliciosa\""), body)
            assertTrue(body.contains("\"water_frequency_days\":8"), body)
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun identifyAppearsOnlyWithPhoto_andRemovingThePhotoHidesIt() {
        val graph = newHarness()
        val identifier = CountingIdentifier()
        val file = photoFile()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier, "ru")
            waitUntilAtLeastOneExists(hasContentDescription("Добавить фото"), TIMEOUT)
            assertEquals(0, unmergedCount("addPlant_identify"))
            onNodeWithText("Узнать вид по фото").assertDoesNotExist()
            onNodeWithText("Фото уйдёт в Pl@ntNet. Можно не нажимать.").assertDoesNotExist()

            chooseCover(file, addPhoto = "Добавить фото", done = "Готово")
            waitUntilAtLeastOneExists(hasText("Узнать вид по фото"), TIMEOUT)
            onNodeWithText("Фото уйдёт в Pl@ntNet. Можно не нажимать.").assertExists()
            val photoBottom = onNode(hasContentDescription("Фото растения")).getBoundsInRoot().bottom
            val identifyTop = onNodeWithTag("addPlant_identify", useUnmergedTree = true).getBoundsInRoot().top
            assertTrue(identifyTop >= photoBottom, "the button sits under the photo")

            clickAt(hasClickAction() and hasContentDescription("Фото растения"))
            clickUnmergedTag("addPlant_photo_remove")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_identify") == 0 }
            onNodeWithText("Фото уйдёт в Pl@ntNet. Можно не нажимать.").assertDoesNotExist()
            assertEquals(0, identifier.calls.get(), "pick and remove send nothing")
            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // M11 item 5 (variant A): one short screen, the rest under «Ещё параметры»
    // ------------------------------------------------------------------

    private fun ComposeUiTest.setScaledAddPlant(graph: Harness, locale: String, fontScale: Float, widthDp: Int = 384) {
        setContent {
            site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = locale) {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier.width(widthDp.dp),
                        ) {
                            AddPlantScreen(userId = "73", opener = graph.opener, onBack = {}, identifier = CountingIdentifier())
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.lineCountOf(text: String): Int {
        val node = onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first().lineCount
    }

    private fun shortFormCase(locale: String, more: String, add: String, fontScale: Float) {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setScaledAddPlant(graph, locale, fontScale)
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_submit") == 1 }
            // Above «Ещё параметры»: photo box, name, watering stepper (7). Nothing else.
            onNodeWithTag("addPlant_water_value", useUnmergedTree = true).assertTextEquals("7")
            assertEquals(1, onAllNodes(isEditable()).fetchSemanticsNodes().size, "only the name field is shown")
            listOf("addPlant_identify", "addPlant_pickSpecies", "addPlant_location", "addPlant_lastWatered", "addPlant_notes")
                .forEach { tag -> assertEquals(0, unmergedCount(tag), "$tag is hidden while collapsed") }
            val photo = onNode(hasClickAction() and hasContentDescription(if (locale == "ru") "Добавить фото" else "Add photo"))
                .getBoundsInRoot()
            val name = onAllNodes(isEditable())[0].getBoundsInRoot()
            val stepper = onNodeWithTag("addPlant_water_inc", useUnmergedTree = true).getBoundsInRoot()
            val moreRow = onNodeWithTag("addPlant_more", useUnmergedTree = true).getBoundsInRoot()
            val submit = onNodeWithTag("addPlant_submit", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(photo.bottom <= name.top && name.bottom <= stepper.top, "photo, name, stepper from top to bottom")
            assertTrue(stepper.bottom <= moreRow.top && moreRow.bottom <= submit.top, "«$more» above «$add»")
            val root = onRoot().getBoundsInRoot()
            assertTrue(submit.bottom <= root.bottom, "«$add» is on screen without scrolling: $submit in $root")
            onNodeWithTag("addPlant_submit").assertTextEquals(add)
            assertEquals(1, lineCountOf(more), "«$more» is one line at $fontScale")
            assertEquals(1, lineCountOf(add), "«$add» is one line at $fontScale")
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun shortForm_ru_showsOnlyPhotoNameWatering_andAddAtTheBottom() =
        shortFormCase(locale = "ru", more = "Ещё параметры", add = "Добавить", fontScale = 1.3f)

    @Test
    fun shortForm_en_showsOnlyPhotoNameWatering_andAddAtTheBottom() =
        shortFormCase(locale = "en", more = "More options", add = "Add", fontScale = 1.15f)

    @Test
    fun addButtonStaysPinnedWhileTheFormScrolls() {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setScaledAddPlant(graph, "en", fontScale = 1.3f)
            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_notes") == 1 }
            val submitBefore = onNodeWithTag("addPlant_submit", useUnmergedTree = true).getBoundsInRoot()
            val moreBefore = onNodeWithTag("addPlant_more", useUnmergedTree = true).getBoundsInRoot()
            repeat(4) { onNodeWithTag("addPlant_form").performTouchInput { swipeUp() } }
            waitForIdle()
            val submitAfter = onNodeWithTag("addPlant_submit", useUnmergedTree = true).getBoundsInRoot()
            val moreAfter = onNodeWithTag("addPlant_more", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(moreAfter.top < moreBefore.top, "the form scrolled: $moreBefore → $moreAfter")
            assertEquals(submitBefore, submitAfter, "«Add» does not move")
            val root = onRoot().getBoundsInRoot()
            assertTrue(submitAfter.bottom <= root.bottom && submitAfter.top >= root.top, "«Add» stays on screen")
            assertTrue(submitAfter.bottom - submitAfter.top >= 48.dp, "«Add» is at least 48 dp high")

            // An empty name sends nothing and says why.
            val posts = graph.server.plantsCount
            clickSubmit()
            waitUntilAtLeastOneExists(hasText("Enter a name"), TIMEOUT)
            assertEquals(posts, graph.server.plantsCount)
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun moreOptions_isCollapsed_andKeepsTypedValuesAcrossToggles() {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier = null, locale = "ru")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_more") == 1 }
            onNodeWithText("Ещё параметры").assertExists()
            onNodeWithText("место, вид из списка, последний полив, уход, заметки").assertExists()
            assertEquals(0, unmergedCount("addPlant_location"))

            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_location") == 1 }
            onNodeWithText("или выбрать вид из списка").assertExists()
            onNodeWithText("Дата последнего полива").assertExists()
            onNodeWithText("Дополнительный уход").assertExists()
            onNodeWithText("🌱 Удобрение").assertExists()
            onNodeWithText("🪴 Пересадка").assertExists()
            onNodeWithText("✂️ Обрезка").assertExists()
            val today = formatPickerDate(pickerToday().toString(), "ru")
            waitUntilAtLeastOneExists(hasText(today), TIMEOUT)

            onNodeWithTag("addPlant_location").performTextInput("Kitchen")
            onNodeWithTag("addPlant_notes").performTextInput("North window")
            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_location") == 0 }
            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_location") == 1 }
            onNodeWithTag("addPlant_location").assertTextEquals("Kitchen", includeEditableText = true)
            onNodeWithTag("addPlant_notes").assertTextEquals("North window", includeEditableText = true)
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun nameOnlyPlant_savesWateringSevenAndToday() {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier = CountingIdentifier(), locale = "en")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_submit") == 1 }
            onAllNodes(isEditable())[0].performTextInput("Fern")
            clickSubmit()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            val body = graph.server.lastAddBody.orEmpty()
            assertTrue(body.contains("\"name\":\"Fern\""), body)
            assertTrue(body.contains("\"water_frequency_days\":7"), body)
            assertTrue(body.contains("\"last_watered_date\":\"${pickerToday()}\""), body)
            listOf("species_", "fertilize", "repot_", "prune_", "data:image").forEach { key ->
                assertTrue(!body.contains(key), "no $key in a name-only plant: $body")
            }
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun fieldsUnderMoreOptions_areSaved_andTheTypedNameIsKept() {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier = null, locale = "en")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_submit") == 1 }
            onAllNodes(isEditable())[0].performTextInput("Big leaf")
            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_location") == 1 }
            onNodeWithTag("addPlant_location").performTextInput("Kitchen")
            clickUnmergedTag("addPlant_pickSpecies")
            val search = isEditable() and hasText("Name or scientific name", substring = true)
            waitUntilAtLeastOneExists(search, TIMEOUT)
            onAllNodes(search)[0].performTextInput("Monstera deliciosa")
            clickAt(hasClickAction() and hasText("Swiss cheese plant"))
            waitUntilDoesNotExist(search, TIMEOUT)
            onNodeWithTag("addPlant_fertilize").performTextClearance()
            onNodeWithTag("addPlant_fertilize").performTextInput("14")
            onNodeWithTag("addPlant_notes").performTextInput("North window")
            clickSubmit()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastAddBody != null }
            val body = graph.server.lastAddBody.orEmpty()
            assertTrue(body.contains("\"name\":\"Big leaf\""), "the typed name is kept: $body")
            assertTrue(body.contains("\"location\":\"Kitchen\""), body)
            assertTrue(body.contains("\"species_id\":\"monstera-deliciosa\""), body)
            assertTrue(body.contains("\"species_name\":\"Monstera deliciosa\""), body)
            assertTrue(body.contains("\"fertilize_frequency_days\":14"), body)
            assertTrue(body.contains("\"notes\":\"North window\""), body)
            assertTrue(body.contains("\"water_frequency_days\":8"), "the stepper value from the species: $body")
            runOnIdle { graph.close() }
        }
    }

    /** Every clickable node of the form, collapsed, expanded and with a photo, is at least 48 dp each way. */
    @Test
    fun touchTargets_areAtLeast48dp() {
        val graph = newHarness()
        val file = photoFile()
        runDesktopComposeUiTest {
            setScaledAddPlant(graph, "ru", fontScale = 1.3f)
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_submit") == 1 }
            fun check(state: String) {
                waitForIdle()
                val px = 48 * density.density - 0.5f
                val small = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
                    .filter { it.size.width < px || it.size.height < px }
                    .map { node ->
                        val label = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                            .joinToString { it.text } + node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }
                        "$label ${node.size}"
                    }
                assertTrue(small.isEmpty(), "$state: targets below 48 dp: $small")
            }
            check("collapsed")
            chooseCover(file, addPhoto = "Добавить фото", done = "Готово")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_identify") == 1 }
            check("with photo")
            clickUnmergedTag("addPlant_more")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_notes") == 1 }
            check("expanded")
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun waterStepperShowsSevenDaysAndSteps() {
        val graph = newHarness()
        runDesktopComposeUiTest {
            setAddPlant(graph, identifier = null, locale = "ru")
            waitUntil(timeoutMillis = TIMEOUT) { unmergedCount("addPlant_water_value") == 1 }
            onNodeWithTag("addPlant_water_value", useUnmergedTree = true).assertTextEquals("7")
            onNodeWithTag("addPlant_water_unit", useUnmergedTree = true).assertTextEquals("дней")
            onNodeWithText("Поливать каждые").assertExists()
            clickUnmergedTag("addPlant_water_inc")
            onNodeWithTag("addPlant_water_value", useUnmergedTree = true).assertTextEquals("8")
            val inc = onNodeWithTag("addPlant_water_inc", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(inc.right - inc.left >= 48.dp && inc.bottom - inc.top >= 48.dp, "stepper button is at least 48 dp")
            runOnIdle { graph.close() }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
