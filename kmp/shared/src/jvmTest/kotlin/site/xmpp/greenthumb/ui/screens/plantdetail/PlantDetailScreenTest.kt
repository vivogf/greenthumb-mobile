@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.PlantDto
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
 * Stage 7 п.6 (фича screen-plant-detail) — desktop-ветка VAL-DETAIL-001/004/006:
 * полная поверхность App() (механика AddPlantScreenTest). Ключевые утверждения:
 *
 * - VAL-DETAIL-001: шапка рисует имя/локацию/метки назад-камера; тап по имени
 *   → поле → правка → blur (тап по соседнему кликабельному) → PATCH /api/
 *   plants/:id с телом {"name": ...} — тело фиксируется MockEngine;
 * - VAL-DETAIL-004: подтверждение → DELETE уходит, карточка исчезает из Room
 *   мгновенно (на дашборде после popBackStack её уже нет — RN onMutate);
 *   HTTP 500 → алерт ошибки НАД графом (экран уже ушёл — RN onError
 *   showAlert поверх дашборда), растение в Room восстановлено откатом;
 * - VAL-DETAIL-006: прямой composition PlantDetailScreen с id, которого нет
 *   в кэше → экран «Plant not found» + «Go Home», не краш;
 * - VAL-DETAIL-002 (UI-ветка): карточки ухода только для настроенных частот,
 *   подписи дней из plurals.
 */
@OptIn(ExperimentalTestApi::class)
class PlantDetailScreenTest {

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

    /** Фикстура растения с НАСТРОЕННЫМ продвинутым уходом (все карточки). */
    private fun fixturePlant(id: String = "det-1", name: String = "Ficus"): PlantDto = detailFixturePlant(id, name)

    private class FakeServer {
        var plantsCount = 0
        var createCount = 0
        var lastPatchBody: String? = null
        var lastPatchPath: String? = null
        var deleteCount = 0
        var deleteStatus: HttpStatusCode = HttpStatusCode.OK
        var lastDeletePath: String? = null

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":73,"name":"kmp-val-detail","recovery_key":"fixture-not-credential",""" +
                """"created_at":"2026-09-27T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/auth/me" ->
                    respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                path == "/api/plants" && request.method == HttpMethod.Get -> {
                    plantsCount++
                    respond("""[${detailFixturePlant().let { p ->
                        """{"id":"${p.id}","user_id":"73","name":"${p.name}","location":"${p.location}",""" +
                            """"photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20",""" +
                            """"fertilize_frequency_days":30,"last_fertilized_date":"2026-08-01",""" +
                            """"repot_frequency_months":12,"last_repotted_date":"2026-01-10",""" +
                            """"prune_frequency_months":6,"last_pruned_date":"2026-03-15",""" +
                            """"notes":"Loves humidity","created_at":"2026-09-01T10:00:00.000Z"}"""
                    }}]""", HttpStatusCode.OK, JSON_HEADERS)
                }
                path.startsWith("/api/plants/") && request.method == HttpMethod.Patch -> {
                    val body = (request.body as? TextContent)?.text
                    lastPatchBody = body
                    lastPatchPath = path
                    // Сервер эхом возвращает обновлённое растение (контракт
                    // PATCH): имя — из тела патча (если прислан), остальное —
                    // как в фикстуре.
                    val name = Regex("\"name\":\"([^\"]*)\"").find(body ?: "")?.groupValues?.get(1)
                        ?: "Ficus"
                    respond("""{"id":"det-1","user_id":"73","name":"$name","location":"Living Room",""" +
                        """"photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20",""" +
                        """"notes":"Loves humidity","created_at":"2026-09-01T10:00:00.000Z"}""", HttpStatusCode.OK, JSON_HEADERS)
                }
                path.startsWith("/api/plants/") && request.method == HttpMethod.Delete -> {
                    deleteCount++
                    lastDeletePath = path
                    respond("""{"success":true}""", deleteStatus, JSON_HEADERS)
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
        val databases: JvmPlantDatabases,
        private val client: site.xmpp.greenthumb.core.network.ApiClient,
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
        val dir: File = File.createTempFile("gt-detail-screen", "").let { f ->
            f.delete()
            f
        }
        assertTrue(dir.mkdirs() || dir.isDirectory, "temp dir created")
        val databases = JvmPlantDatabases(dir)
        val server = FakeServer()
        val settings = FakeSettings()
        val secure = FakeSecureStore()
        val client = site.xmpp.greenthumb.core.network.ApiClient(
            MockEngine(server.handler),
            InMemoryRecoveryProvider(secure, settings),
        )
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
        return Harness(server, settings, secure, session, push, opener, connectivity, databases, client, repos, dir)
            .also { harness = it }
    }

    private fun presetEnglishSignedOut(): Unit = runBlocking {
        harness!!.settings.setLanguage(AppLanguage.En)
    }

    private fun hasSubText(value: String) = hasText(value, substring = true)

    /** Пролог AddPlantScreenTest: welcome → … → дашборд. */
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
        waitUntilAtLeastOneExists(hasSubText("Ficus"), TIMEOUT)
    }

    /** Тап по карточке растения (дашборд, режим list) → экран деталей. */
    private fun androidx.compose.ui.test.ComposeUiTest.openPlantDetail() {
        clickAt(hasClickAction() and hasSubText("Ficus"))
        waitUntilAtLeastOneExists(hasSubText("Living Room"), TIMEOUT)
        waitUntilAtLeastOneExists(hasSubText("LAST WATERED"), TIMEOUT)
    }

    /** Кнопка удаления экрана: unmerged contentDescription «Delete plant». */
    private fun androidx.compose.ui.test.ComposeUiTest.openDeleteConfirm() {
        waitUntilAtLeastOneExists(deleteButtonMatcher(), TIMEOUT)
        // contentDescription — unmerged-свойство: матчим через unmerged-дерево
        // (useUnmergedTree=true), иначе hasText по merged-узлу его не видит.
        onAllNodes(deleteButtonMatcher(), useUnmergedTree = true)[0].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
        waitUntilAtLeastOneExists(hasText("Delete this plant?"), TIMEOUT)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickAt(
        matcher: SemanticsMatcher,
        index: Int = 0,
    ) {
        onAllNodes(matcher)[index].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    /**
     * Тап Delete В МОДАЛКЕ (не по кнопке экрана позади): диалоговые узлы
     * позже в дереве композиции — берём последний совпавший.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.clickLast(
        matcher: SemanticsMatcher,
    ) {
        val nodes = onAllNodes(matcher).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "no nodes for $matcher" }
        onAllNodes(matcher)[nodes.size - 1].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    /**
     * Кнопка удаления экрана: a11y-лейбл «Delete plant» на модификаторе
     * экрана — unmerged contentDescription (hasText merged-дерева его не
     * видит). Матчим только contentDescription: единственный узел с ним —
     * кнопка удаления.
     */
    private fun deleteButtonMatcher(): SemanticsMatcher =
        hasClickAction() and hasContentDescription("Delete plant")

    // ------------------------------------------------------------------
    // VAL-DETAIL-001: шапка, инлайн-редактирование имени → PATCH
    // ------------------------------------------------------------------

    @Test
    fun header_showsNameLocation_andNameEdit_sendsPatch() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            // Шапка: имя, локация, статус полива; назад/камера — иконки с
            // unmerged contentDescription.
            waitUntilAtLeastOneExists(hasSubText("Ficus"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Living Room"), TIMEOUT)
            onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().also {
                assertTrue(it.isNotEmpty(), "кнопка назад несёт a11y «Back»")
            }
            onAllNodes(hasContentDescription("Change plant photo")).fetchSemanticsNodes().also {
                assertTrue(it.isNotEmpty(), "кнопка камеры несёт a11y «Change plant photo»")
            }
            // Карточки ухода (VAL-DETAIL-002 UI-ветка): настроенные частоты.
            waitUntilAtLeastOneExists(hasSubText("Fertilizing"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Repotting"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Pruning"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Care Settings"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("Loves humidity"), TIMEOUT)

            // Инлайн-редактирование: тап по имени → editable → ввод → Done
            // (RN onSubmitEditing; blur — RN onBlur) → PATCH {"name": ...}.
            clickAt(hasClickAction() and hasSubText("Ficus"))
            waitUntilAtLeastOneExists(isEditable(), TIMEOUT)
            val editable = onAllNodes(isEditable())
            assertTrue(editable.fetchSemanticsNodes().isNotEmpty(), "поле имени появилось")
            editable[0].performTextInput(" Green")
            // RN onSubmitEditing: Done в поле → PATCH. Тело: "Ficus Green".
            editable[0].performImeAction()

            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastPatchBody != null }
            val body = graph.server.lastPatchBody.orEmpty()
            assertTrue(body.contains("\"name\":\"GreenFicus\"") || body.contains("\"name\":\"Ficus Green\""), "PATCH с новым именем: $body")
            assertTrue(graph.server.lastPatchPath == "/api/plants/det-1", "путь PATCH: ${graph.server.lastPatchPath}")

            // Оптимистичное имя уже в шапке (Room отдал до ответа).
            waitUntilAtLeastOneExists(hasSubText("GreenFicus"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-DETAIL-004: удаление — подтверждение, DELETE, мгновенный возврат
    // ------------------------------------------------------------------

    @Test
    fun delete_confirmed_sendsDelete_andReturnsToDashboard_instantly() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            // Подтверждение: DestructiveButton несёт a11y «Delete plant»
            // (contentDescription, unmerged-свойство).
            openDeleteConfirm()
            // Модалка: заголовок + описание с именем.
            waitUntilAtLeastOneExists(hasText("Delete this plant?"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("permanently delete Ficus"), TIMEOUT)

            // Тап Delete в модалке (последний совпавший — не кнопка экрана
            // позади): экран уходит на дашборд СРАЗУ (RN onMutate), DELETE
            // доезжает в фоне. Карточки растения на дашборде уже НЕТ —
            // репозиторий убрал строку из Room до ответа (мгновенное
            // исчезновение).
            clickLast(hasClickAction() and hasText("Delete", substring = false))
            waitUntilDoesNotExist(hasText("Delete this plant?"), TIMEOUT)

            // DELETE зафиксирован; карточка не вернулась (список пуст —
            // дашборд screen-dashboard показывает empty-state «нет растений
            // вообще», не «ничего не найдено фильтром»).
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.deleteCount >= 1 }
            assertEquals("/api/plants/det-1", graph.server.lastDeletePath)
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodesWithText("No plants yet", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun delete_serverError_showsAlertOverDashboard_andPlantRestored() {
        val graph = newHarness()
        presetEnglishSignedOut()
        graph.server.deleteStatus = HttpStatusCode.InternalServerError
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            openDeleteConfirm()
            clickLast(hasClickAction() and hasText("Delete", substring = false))

            // Экран уже на дашборде (мгновенный уход), алерт ошибки — НАД графом.
            waitUntilDoesNotExist(hasText("Delete this plant?"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Error"), TIMEOUT)
            // Растение восстановлено откатом снимка (репозиторий).
            waitUntilAtLeastOneExists(hasSubText("Ficus"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-DETAIL-003: модалка настроек — префилл, сброс частоты → явный null
    // ------------------------------------------------------------------

    @Test
    fun careSettingsModal_prefilled_clearFertilize_saveSendsExplicitNull() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            // Открыть модалку: строка «Care Settings» — OnClick семантика
            // напрямую (паттерн clickAt файла; координатный performClick по
            // строке ниже фолда попадает мимо — см. deleteButtonMatcher).
            clickAt(hasClickAction() and hasText("Care Settings"))
            waitUntilAtLeastOneExists(hasText("Watering Frequency (days)"), TIMEOUT)

            // Префилл из растения (RN openSettings): частоты 7/30/12/6.
            waitUntilAtLeastOneExists(hasText("Fertilizing Frequency (days)"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Repotting Frequency (months)"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Pruning Frequency (months)"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Leave empty to disable"), TIMEOUT)

            // Очистить частоту удобрения (у растения 30) → Save в шапке модалки
            // (клик по последнему совпавшему — узлы модалки позже в дереве).
            onAllNodes(hasText("30"))[0].performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetText,
            ) { it(androidx.compose.ui.text.AnnotatedString("")) }
            clickLast(hasClickAction() and hasText("Save"))

            // PATCH уходит с ЯВНЫМ null (VAL-DETAIL-003), модалка закрылась.
            waitUntil(timeoutMillis = TIMEOUT) {
                graph.server.lastPatchBody?.contains("\"fertilize_frequency_days\":null") == true
            }
            waitUntilDoesNotExist(hasText("Watering Frequency (days)"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun careSettingsModal_cancel_sendsNothing() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            clickAt(hasClickAction() and hasText("Care Settings"))
            waitUntilAtLeastOneExists(hasText("Watering Frequency (days)"), TIMEOUT)

            clickLast(hasClickAction() and hasText("Cancel"))
            waitUntilDoesNotExist(hasText("Watering Frequency (days)"), TIMEOUT)
            assertTrue(graph.server.lastPatchBody == null, "Cancel не шлёт PATCH")

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-DETAIL-006: растение не найдено — экран, не краш
    // ------------------------------------------------------------------

    @Test
    fun unknownId_showsNotFoundScreen_withoutCrash() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "en") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        // Прямая поверхность: id отсутствует в кэше (наблюдение
                        // отдаёт пустой список → NotFound).
                        PlantDetailScreen(
                            userId = "73",
                            plantId = "missing-id",
                            opener = graph.opener,
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Plant not found"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Go Home"), TIMEOUT)
            runOnIdle { graph.close() }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}

/** Фикстура растения с НАСТРОЕННЫМ продвинутым уходом (все карточки). */
private fun detailFixturePlant(id: String = "det-1", name: String = "Ficus"): PlantDto = PlantDto(
    id = id,
    userId = "73",
    name = name,
    location = "Living Room",
    photoUrl = "",
    waterFrequencyDays = 7,
    lastWateredDate = "2026-09-20",
    fertilizeFrequencyDays = 30,
    lastFertilizedDate = "2026-08-01",
    repotFrequencyMonths = 12,
    lastRepottedDate = "2026-01-10",
    pruneFrequencyMonths = 6,
    lastPrunedDate = "2026-03-15",
    notes = "Loves humidity",
    createdAt = "2026-09-01T10:00:00.000Z",
)
