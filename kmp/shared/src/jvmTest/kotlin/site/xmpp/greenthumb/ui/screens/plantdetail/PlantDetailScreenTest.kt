@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import androidx.compose.ui.unit.dp
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
 *   → окно «Name and place» → правка → ✓ → PATCH /api/plants/:id с телом
 *   {"name": ...} — тело фиксируется MockEngine;
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
        var patchCount = 0
        var deleteCount = 0
        var deleteStatus: HttpStatusCode = HttpStatusCode.OK
        var deleteThrows: Throwable? = null
        var lastDeletePath: String? = null
        var lastWateredDate = "2026-09-20"
        var waterFrequencyDays = 7
        var lastFertilizedDate: String? = "2026-08-01"
        var lastRepottedDate: String? = "2026-01-10"

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        /** Care journal of det-1 on the "server", newest first after [sortedEvents]. */
        data class FakeEvent(
            val id: String,
            val type: String,
            val occurredOn: String,
            val note: String? = null,
            val photoId: String? = null,
        )

        /** Timeline of det-1 on the "server"; served newest first. */
        data class FakePhoto(val id: String, val takenOn: String, val caption: String? = null)

        val photos = mutableListOf<FakePhoto>()
        var photoGets = 0
        var photosThrows: Throwable? = null
        var photoPosts = 0
        var photoDeletes = 0

        private fun photoJson(p: FakePhoto): String {
            val caption = p.caption?.let { "\"$it\"" } ?: "null"
            return """{"id":"${p.id}","url":"","taken_on":"${p.takenOn}","caption":$caption,""" +
                """"created_at":"${p.takenOn}T08:00:00.000000Z"}"""
        }

        val events = mutableListOf<FakeEvent>()
        var eventGets = 0
        var eventsThrows: Throwable? = null
        var notePosts = 0
        var lastNoteBody: String? = null
        var noteThrows: Throwable? = null
        var eventDeletes = 0
        private var nextEvent = 0

        private fun sortedEvents(): List<FakeEvent> = synchronized(events) {
            events.sortedWith(compareByDescending<FakeEvent> { it.occurredOn }.thenByDescending { it.id })
        }

        private fun eventJson(e: FakeEvent): String {
            val note = e.note?.let { "\"$it\"" } ?: "null"
            return """{"id":"${e.id}","plant_id":"det-1","user_id":"73","type":"${e.type}",""" +
                """"occurred_on":"${e.occurredOn}","note":$note,"photo_id":${e.photoId?.let { "\"$it\"" } ?: "null"},""" +
                """"created_at":"${e.occurredOn}T08:00:00.000000Z"}"""
        }

        private fun userJson(): String =
            """{"user":{"id":73,"name":"kmp-val-detail","recovery_key":"fixture-not-credential",""" +
                """"created_at":"2026-09-27T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/auth/me" ->
                    respond(userJson(), HttpStatusCode.Unauthorized, JSON_HEADERS)
                path == "/api/plants/det-1/events" && request.method == HttpMethod.Get -> {
                    eventGets++
                    eventsThrows?.let { throw it }
                    respond(
                        """{"events":[${sortedEvents().joinToString(",") { eventJson(it) }}],"nextCursor":null}""",
                        HttpStatusCode.OK,
                        JSON_HEADERS,
                    )
                }
                path == "/api/plants/det-1/events" && request.method == HttpMethod.Post -> {
                    notePosts++
                    val body = (request.body as? TextContent)?.text
                    lastNoteBody = body
                    noteThrows?.let { throw it }
                    val note = Regex("\"note\":\"([^\"]*)\"").find(body ?: "")!!.groupValues[1]
                    val created = FakeEvent("n-${++nextEvent}", "note", "2026-09-24", note)
                    synchronized(events) { events += created }
                    respond(eventJson(created), HttpStatusCode.Created, JSON_HEADERS)
                }
                path == "/api/plants/det-1/photos" && request.method == HttpMethod.Get -> {
                    photoGets++
                    photosThrows?.let { throw it }
                    val rows = synchronized(photos) { photos.sortedByDescending { it.takenOn } }
                    respond("""{"photos":[${rows.joinToString(",") { photoJson(it) }}]}""", HttpStatusCode.OK, JSON_HEADERS)
                }
                path == "/api/plants/det-1/photos" && request.method == HttpMethod.Post -> {
                    photoPosts++
                    respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS)
                }
                path.startsWith("/api/plants/det-1/photos/") && request.method == HttpMethod.Delete -> {
                    photoDeletes++
                    val id = path.substringAfterLast('/')
                    synchronized(photos) { photos.removeAll { it.id == id } }
                    synchronized(events) { events.removeAll { it.photoId == id } }
                    respond("", HttpStatusCode.NoContent, JSON_HEADERS)
                }
                path.startsWith("/api/plants/det-1/events/") && request.method == HttpMethod.Delete -> {
                    eventDeletes++
                    val id = path.substringAfterLast('/')
                    synchronized(events) { events.removeAll { it.id == id } }
                    respond("", HttpStatusCode.NoContent, JSON_HEADERS)
                }
                path == "/api/plants" && request.method == HttpMethod.Get -> {
                    plantsCount++
                    respond("""[${detailFixturePlant().let { p ->
                        """{"id":"${p.id}","user_id":"73","name":"${p.name}","location":"${p.location}",""" +
                            """"photo_url":"","water_frequency_days":$waterFrequencyDays,"last_watered_date":"$lastWateredDate",""" +
                            """"fertilize_frequency_days":30,"last_fertilized_date":${lastFertilizedDate.jsonOrNull()},""" +
                            """"repot_frequency_months":12,"last_repotted_date":${lastRepottedDate.jsonOrNull()},""" +
                            """"prune_frequency_months":6,"last_pruned_date":"2026-03-15",""" +
                            """"notes":"Loves humidity","created_at":"2026-09-01T10:00:00.000Z"}"""
                    }}]""", HttpStatusCode.OK, JSON_HEADERS)
                }
                path.startsWith("/api/plants/") && request.method == HttpMethod.Patch -> {
                    val body = (request.body as? TextContent)?.text
                    patchCount++
                    lastPatchBody = body
                    lastPatchPath = path
                    // The server journals a changed watering date (inferCareEvents).
                    Regex("\"last_watered_date\":\"([0-9-]+)\"").find(body ?: "")?.groupValues?.get(1)?.let { day ->
                        synchronized(events) { events += FakeEvent("w-${++nextEvent}", "water", day) }
                    }
                    // Сервер эхом возвращает обновлённое растение (контракт
                    // PATCH): имя — из тела патча (если прислан), остальное —
                    // как в фикстуре.
                    val name = Regex("\"name\":\"([^\"]*)\"").find(body ?: "")?.groupValues?.get(1)
                        ?: "Ficus"
                    val notes = Regex("\"notes\":\"([^\"]*)\"").find(body ?: "")?.groupValues?.get(1)
                        ?: "Loves humidity"
                    // A fertilize mark keeps the card: echo the fertilize fields (undo tests tap it twice).
                    val fertilize = Regex("\"last_fertilized_date\":(\"[0-9-]+\"|null)").find(body ?: "")?.groupValues?.get(1)
                        ?.let { """"fertilize_frequency_days":30,"last_fertilized_date":$it,""" }
                        .orEmpty()
                    respond("""{"id":"det-1","user_id":"73","name":"$name","location":"Living Room",""" +
                        """"photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20",$fertilize""" +
                        """"notes":"$notes","created_at":"2026-09-01T10:00:00.000Z"}""", HttpStatusCode.OK, JSON_HEADERS)
                }
                path.startsWith("/api/plants/") && request.method == HttpMethod.Delete -> {
                    deleteCount++
                    lastDeletePath = path
                    // Транспортный сбой до ответа (ApiError.Network через
                    // ApiClient.mapException) — ветка очереди VAL-DETAIL-004.
                    deleteThrows?.let { throw it }
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
        waitUntilAtLeastOneExists(hasSubText("Last watered:"), TIMEOUT)
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

            // Name and place sheet (M10 items 5-6): tap the name → sheet → edit → Done on the keyboard
            // saves nothing; only ✓ sends PATCH {"name": ...}.
            clickAt(hasClickAction() and hasTestTag("plantDetail_name"))
            waitUntilAtLeastOneExists(hasTestTag(NamePlaceTags.NAME) and isEditable(), TIMEOUT)
            val editable = onAllNodes(hasTestTag(NamePlaceTags.NAME) and isEditable(), useUnmergedTree = true)
            assertTrue(editable.fetchSemanticsNodes().isNotEmpty(), "поле имени появилось")
            editable[0].performTextInput(" Green")
            editable[0].performImeAction()
            waitForIdle()
            assertTrue(graph.server.lastPatchBody == null, "Done in the name field saves nothing")
            clickAt(hasClickAction() and hasContentDescription("Save"))

            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastPatchBody != null }
            val body = graph.server.lastPatchBody.orEmpty()
            assertTrue(body.contains("\"name\":\"GreenFicus\"") || body.contains("\"name\":\"Ficus Green\""), "PATCH с новым именем: $body")
            assertTrue(!body.contains("location"), "unchanged place is not sent: $body")
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

    /**
     * VAL-DETAIL-004, ветка Network/Timeout: карточка исчезла оптимистично,
     * удаление стоит в очереди M4, глобальный алерт говорит ЧЕСТНЫЙ статус
     * «удаление выполнится после подключения», а не «ошибка» (misleading
     * сообщение round-1 user-testing). Detерминированно: MockEngine бросает
     * транспортный сбой вместо продовых fault-контролов.
     */
    @Test
    fun delete_networkError_showsQueuedStatusOverDashboard_notError() {
        val graph = newHarness()
        presetEnglishSignedOut()
        graph.server.deleteThrows = IllegalStateException("Connection reset")
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            openDeleteConfirm()
            clickLast(hasClickAction() and hasText("Delete", substring = false))

            // Экран ушёл на дашборд сразу (RN onMutate), DELETE упал в сети.
            waitUntilDoesNotExist(hasText("Delete this plant?"), TIMEOUT)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.deleteCount >= 1 }

            // Честный локализованный статус очереди (не «Error»).
            waitUntilAtLeastOneExists(hasText("Delete postponed"), TIMEOUT)
            waitUntilAtLeastOneExists(
                hasSubText("deletion will run after you reconnect"),
                TIMEOUT,
            )
            assertTrue(
                onAllNodes(hasText("Error"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "Network/Timeout — не ошибка: заголовок «Error» не показывается",
            )
            // Карточка исчезла оптимистично (не воскресла от отката).
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodesWithText("No plants yet", substring = true).fetchSemanticsNodes().isNotEmpty()
            }

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
            waitUntilAtLeastOneExists(hasText("Plant care"), TIMEOUT)

            // Префилл из растения: удобрение включено (30 дней у растения).
            waitUntilAtLeastOneExists(hasText("Fertilizing"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Repotting"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Pruning"), TIMEOUT)

            // Выключить удобрение тумблером → «Save» внизу листа.
            clickLast(hasTestTag(CareTags.switch(CareType.Fertilize)))
            clickLast(hasTestTag(CareTags.SAVE))

            // PATCH уходит с ЯВНЫМ null (VAL-DETAIL-003), лист закрылся.
            waitUntil(timeoutMillis = TIMEOUT) {
                graph.server.lastPatchBody?.contains("\"fertilize_frequency_days\":null") == true
            }
            waitUntilDoesNotExist(hasText("Plant care"), TIMEOUT)

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
            waitUntilAtLeastOneExists(hasText("Plant care"), TIMEOUT)

            clickLast(hasTestTag(CareTags.CLOSE))
            waitUntilDoesNotExist(hasText("Plant care"), TIMEOUT)
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

    // ------------------------------------------------------------------
    // Journal (VAL-JOURNAL-014/015/016/018)
    // ------------------------------------------------------------------

    private fun seedAllEventTypes(server: FakeServer) {
        synchronized(server.events) {
            server.events += listOf(
                FakeServer.FakeEvent("e-water", "water", "2026-09-20"),
                FakeServer.FakeEvent("e-note", "note", "2026-09-22", "Moved to the window"),
                FakeServer.FakeEvent("e-photo", "photo", "2026-09-21"),
                FakeServer.FakeEvent("e-fert", "fertilize", "2026-08-01"),
                FakeServer.FakeEvent("e-prune", "prune", "2026-03-15"),
                FakeServer.FakeEvent("e-repot", "repot", "2026-01-10"),
            )
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.topOf(text: String): Float =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top

    private fun deleteNoteMatcher(): SemanticsMatcher =
        hasClickAction() and hasContentDescription("Delete note")

    @Test
    fun journal_rendersAllTypes_newestFirst_inEnglish() {
        val graph = newHarness()
        presetEnglishSignedOut()
        seedAllEventTypes(graph.server)
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            waitUntilAtLeastOneExists(hasText("Journal"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Moved to the window"), TIMEOUT)
            listOf("Watered", "Fertilized", "Repotted", "Pruned", "Note", "Photo added").forEach { label ->
                waitUntilAtLeastOneExists(hasText(label), TIMEOUT)
            }
            val dates = listOf("Sep 22", "Sep 21", "Sep 20", "Aug 1", "Mar 15", "Jan 10")
            dates.forEach { date ->
                assertEquals(1, onAllNodes(hasText(date), useUnmergedTree = true).fetchSemanticsNodes().size, date)
            }
            val tops = dates.map { topOf(it) }
            assertEquals(tops.sorted(), tops, "newest first: $tops")
            // Only the note is offered for deletion.
            assertEquals(1, onAllNodes(deleteNoteMatcher(), useUnmergedTree = true).fetchSemanticsNodes().size)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun journal_rendersInRussian_withRussianDates() {
        val graph = newHarness()
        seedAllEventTypes(graph.server)
        val repo = graph.opener.open("73")
        runBlocking { repo.refresh() }
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "ru") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        PlantDetailScreen(
                            userId = "73",
                            plantId = "det-1",
                            opener = graph.opener,
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Журнал"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("О растении"), TIMEOUT)
            // No composer label and no save bar at rest: the journal card is just the field.
            assertTrue(
                onAllNodes(hasTestTag("journal_entryLabel"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no composer label in the journal",
            )
            assertTrue(
                onAllNodes(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no save bar before the field takes focus",
            )
            assertTrue(onAllNodes(hasText("Заметки"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            listOf("Полили", "Подкормили", "Пересадили", "Обрезали", "Заметка", "Фото").forEach { label ->
                waitUntilAtLeastOneExists(hasText(label), TIMEOUT)
            }
            waitUntilAtLeastOneExists(hasText("22 сент."), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("10 янв."), TIMEOUT)
            assertTrue(onAllNodes(hasText("Sep 22"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            runOnIdle { graph.close() }
        }
    }

    /**
     * 0.8.2 variant C: at rest the journal card is the bare field, with no label and no save bar. Focus brings
     * the full-width bar under the field and scrolls the page so the field stands at the top of the scroll
     * viewport, right under the photo header — even though the journal is the last card on the page.
     */
    @Test
    fun journal_composer_scrollsTheFieldToTheTop_andTheSaveBarFollowsFocus() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()
            waitUntilAtLeastOneExists(hasText("No entries yet"), TIMEOUT)

            fun field(): androidx.compose.ui.semantics.SemanticsNode =
                onNode(hasTestTag("journal_noteInput"), useUnmergedTree = true).fetchSemanticsNode()

            val viewport = onNode(hasTestTag("plantDetail_headerPhoto"), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            println("M15 journal focus viewportTop=${viewport.bottom} fieldTop=${field().positionInRoot.y}")
            assertTrue(
                field().positionInRoot.y > viewport.bottom + 40f,
                "at rest the field is below the visible top: ${field().positionInRoot.y} vs ${viewport.bottom}",
            )
            assertTrue(
                onAllNodes(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no save bar at rest",
            )

            onNode(hasTestTag("journal_noteInput"))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            waitUntil(timeoutMillis = TIMEOUT) {
                kotlin.math.abs(field().positionInRoot.y - viewport.bottom) <= 4f
            }
            println("M15 journal focus fieldTop=${field().positionInRoot.y} viewportTop=${viewport.bottom}")
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val bar = onNode(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(field().size.width.toFloat(), bar.size.width.toFloat(), 2f, "the bar spans the card")
            assertTrue(bar.positionInRoot.y > field().positionInRoot.y, "the bar sits under the field")
            assertTrue(
                onAllNodes(hasTestTag("journal_addNote") and isNotEnabled()).fetchSemanticsNodes().isNotEmpty(),
                "the bar is disabled while the draft is empty",
            )

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun note_add_survivesReopen_andDeleteNeedsConfirmation() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            waitUntilAtLeastOneExists(hasText("No entries yet"), TIMEOUT)
            onNode(hasTestTag("journal_noteInput")).performTextInput("Unique note 4711")
            // The composer scrolls on focus: let the move settle before tapping the bar.
            waitForIdle()
            clickAt(hasClickAction() and hasText("Add note"))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.notePosts == 1 }
            assertEquals("""{"type":"note","note":"Unique note 4711"}""", graph.server.lastNoteBody)
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Unique note 4711") and !hasSetTextAction()).fetchSemanticsNodes().size == 1
            }

            // Leave and reopen: the note is still there, once.
            onAllNodes(hasContentDescription("Back"), useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntilAtLeastOneExists(hasSubText("Ficus"), TIMEOUT)
            openPlantDetail()
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Unique note 4711")).fetchSemanticsNodes().size == 1
            }

            // Cancel keeps the note.
            onAllNodes(deleteNoteMatcher(), useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntilAtLeastOneExists(hasText("Delete this note?"), TIMEOUT)
            clickLast(hasClickAction() and hasText("Cancel"))
            waitUntilDoesNotExist(hasText("Delete this note?"), TIMEOUT)
            assertEquals(0, graph.server.eventDeletes)
            waitUntilAtLeastOneExists(hasText("Unique note 4711"), TIMEOUT)

            // Confirm removes it.
            onAllNodes(deleteNoteMatcher(), useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntilAtLeastOneExists(hasText("Delete this note?"), TIMEOUT)
            clickLast(hasClickAction() and hasText("Delete", substring = false))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.eventDeletes == 1 }
            waitUntilDoesNotExist(hasText("Unique note 4711"), TIMEOUT)
            assertEquals(1, graph.server.notePosts)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun note_offline_keepsText_isNotSentOnItsOwn_andRetrySavesOnce() {
        val graph = newHarness()
        presetEnglishSignedOut()
        graph.server.noteThrows = IllegalStateException("Connection reset")
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            waitUntilAtLeastOneExists(hasText("No entries yet"), TIMEOUT)
            onNode(hasTestTag("journal_noteInput")).performTextInput("Offline 99")
            // The composer scrolls on focus: let the move settle before tapping the bar.
            waitForIdle()
            clickAt(hasClickAction() and hasText("Add note"))
            waitUntilAtLeastOneExists(hasSubText("Network connection required"), TIMEOUT)
            // The message sits next to the field, above the button, so it is on screen after the tap.
            val messageTop = onNode(hasTestTag("journal_message"), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            val buttonTop = onNode(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            assertTrue(messageTop < buttonTop, "message $messageTop above button $buttonTop")
            // The typed text stays in the field and nothing reached the journal.
            assertEquals(1, onAllNodes(hasTestTag("journal_noteInput") and hasText("Offline 99")).fetchSemanticsNodes().size)
            assertEquals(1, graph.server.notePosts)
            assertTrue(graph.server.events.isEmpty())

            // Network is back: nothing is sent until the user retries.
            graph.server.noteThrows = null
            Thread.sleep(500)
            waitForIdle()
            assertEquals(1, graph.server.notePosts)

            // The composer scrolls on focus: let the move settle before tapping the bar.
            waitForIdle()
            clickAt(hasClickAction() and hasText("Add note"))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.notePosts == 2 }
            waitUntilDoesNotExist(hasSubText("Network connection required"), TIMEOUT)
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Offline 99") and !hasSetTextAction()).fetchSemanticsNodes().size == 1
            }
            assertEquals(1, graph.server.events.count { it.note == "Offline 99" })

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun plantNotes_andJournalEntry_haveDistinctLabels_andSeparateRequests() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            waitUntilAtLeastOneExists(hasText("No entries yet"), TIMEOUT)
            assertEquals(1, onAllNodes(hasTestTag("plantDetail_notesTitle") and hasText("About the plant"), useUnmergedTree = true).fetchSemanticsNodes().size)
            // The journal composer no longer carries a label, and its save bar waits for focus.
            assertTrue(
                onAllNodes(hasTestTag("journal_entryLabel"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no composer label in the journal",
            )
            assertTrue(
                onAllNodes(hasTestTag("journal_addNote"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "no save bar before the field takes focus",
            )
            assertTrue(onAllNodes(hasText("Notes"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

            // Editing the plant notes is one PATCH of notes only; no dated journal entry is created.
            onNode(hasTestTag("plantDetail_notesEdit"), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntilAtLeastOneExists(hasTestTag("plantDetail_notesInput"), TIMEOUT)
            onNode(hasTestTag("plantDetail_notesInput")).performTextReplacement("Loves humidity by the window")
            onNode(hasTestTag("plantDetail_notesSave"), useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 1 }
            assertEquals("""{"notes":"Loves humidity by the window"}""", graph.server.lastPatchBody)
            waitUntilAtLeastOneExists(hasText("Loves humidity by the window") and !hasSetTextAction(), TIMEOUT)
            Thread.sleep(300)
            waitForIdle()
            assertEquals(0, graph.server.notePosts)
            assertTrue(graph.server.events.none { it.type == "note" })

            // A journal entry is one POST to the events endpoint; the plant notes stay as saved.
            onNode(hasTestTag("journal_noteInput")).performTextInput("Entry 512")
            // The composer scrolls on focus: let the move settle before tapping the bar.
            waitForIdle()
            clickAt(hasClickAction() and hasText("Add note"))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.notePosts == 1 }
            assertEquals("""{"type":"note","note":"Entry 512"}""", graph.server.lastNoteBody)
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Entry 512") and !hasSetTextAction()).fetchSemanticsNodes().size == 1
            }
            assertEquals(1, graph.server.patchCount)
            assertEquals(1, onAllNodes(hasText("Loves humidity by the window") and !hasSetTextAction()).fetchSemanticsNodes().size)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun journal_offline_showsCachedEntries_withSavedDataBanner() {
        val graph = newHarness()
        presetEnglishSignedOut()
        seedAllEventTypes(graph.server)
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()
            waitUntilAtLeastOneExists(hasText("Moved to the window"), TIMEOUT)

            onAllNodes(hasContentDescription("Back"), useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntilAtLeastOneExists(hasSubText("Ficus"), TIMEOUT)
            graph.server.eventsThrows = IllegalStateException("Connection reset")
            val getsBefore = graph.server.eventGets
            openPlantDetail()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.eventGets > getsBefore }
            waitUntilAtLeastOneExists(hasSubText("showing saved data"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Moved to the window"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Watered"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun watering_online_addsOneWaterEntry() {
        val graph = newHarness()
        presetEnglishSignedOut()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()
            waitUntilAtLeastOneExists(hasText("No entries yet"), TIMEOUT)

            onAllNodes(hasClickAction() and hasContentDescription("Water plant Ficus"), useUnmergedTree = true)[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.lastPatchBody?.contains("last_watered_date") == true }
            waitUntilAtLeastOneExists(hasText("Watered"), TIMEOUT)
            assertEquals(1, graph.server.events.count { it.type == "water" })
            assertEquals(1, onAllNodes(hasText("Watered"), useUnmergedTree = true).fetchSemanticsNodes().size)

            runOnIdle { graph.close() }
        }
    }
    private fun seedTimeline(server: FakeServer) {
        server.photos += FakeServer.FakePhoto("ph-old", "2026-09-30", "First bud")
        server.photos += FakeServer.FakePhoto("ph-new", "2026-10-02", "New leaf")
        server.events += FakeServer.FakeEvent("ev-old", "photo", "2026-09-30", photoId = "ph-old")
        server.events += FakeServer.FakeEvent("ev-new", "photo", "2026-10-02", photoId = "ph-new")
    }

    private fun openPhotoMatcher(date: String): SemanticsMatcher =
        hasClickAction() and hasContentDescription("Open photo from $date")

    private fun androidx.compose.ui.test.ComposeUiTest.waitUnmerged(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.waitGoneUnmerged(matcher: SemanticsMatcher) {
        waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickUnmerged(matcher: SemanticsMatcher) {
        onAllNodes(matcher, useUnmergedTree = true)[0]
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun timeline_rendersNewestFirst_andOpensFullScreen() {
        val graph = newHarness()
        presetEnglishSignedOut()
        seedTimeline(graph.server)
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()

            waitUntilAtLeastOneExists(hasText("Photo diary"), TIMEOUT)
            waitUnmerged(openPhotoMatcher("10/02/2026"))
            waitUnmerged(openPhotoMatcher("09/30/2026"))
            val newTop = onAllNodes(hasTestTag("timeline_photo_ph-new"), useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot
            val oldTop = onAllNodes(hasTestTag("timeline_photo_ph-old"), useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot
            assertTrue(newTop.y < oldTop.y || (newTop.y == oldTop.y && newTop.x < oldTop.x), "newest first: $newTop $oldTop")

            clickUnmerged(openPhotoMatcher("10/02/2026"))
            waitUnmerged(hasTestTag("timeline_viewer"))
            assertEquals(1, onAllNodes(hasTestTag("timeline_viewer_caption") and hasText("New leaf"), useUnmergedTree = true).fetchSemanticsNodes().size)
            clickLast(hasClickAction() and hasText("Close"))
            waitGoneUnmerged(hasTestTag("timeline_viewer"))
            waitUntilAtLeastOneExists(hasText("Photo diary"), TIMEOUT)
            assertEquals(0, graph.server.photoDeletes)
            assertEquals(0, graph.server.photoPosts)

            // M15: the button and the source sheet title read "Add snapshot"; Cancel closes the sheet, nothing is sent.
            assertEquals(1, onAllNodes(hasTestTag("timeline_addPhoto") and hasAnyDescendant(hasText("Add snapshot")), useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue(onAllNodes(hasText("Add photo"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            clickUnmerged(hasClickAction() and hasTestTag("timeline_addPhoto"))
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText("Add snapshot"), useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
            waitUntilAtLeastOneExists(hasText("Gallery"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Camera"), TIMEOUT)
            clickLast(hasClickAction() and hasText("Cancel"))
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText("Gallery"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            assertEquals(0, graph.server.photoPosts)

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun timeline_rendersInRussian() {
        val graph = newHarness()
        seedTimeline(graph.server)
        val repo = graph.opener.open("73")
        runBlocking { repo.refresh() }
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "ru") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        PlantDetailScreen(
                            userId = "73",
                            plantId = "det-1",
                            opener = graph.opener,
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Фотодневник"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Добавить снимок"), TIMEOUT)
            waitUnmerged(hasContentDescription("Открыть фото от 02.10.2026"))
            waitUnmerged(hasContentDescription("Открыть фото от 30.09.2026"))
            assertTrue(onAllNodes(hasText("timeline", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            runOnIdle { graph.close() }
        }
    }

    @Test
    fun timeline_delete_needsConfirmation_andUpdatesTimelineAndJournal() {
        val graph = newHarness()
        presetEnglishSignedOut()
        seedTimeline(graph.server)
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }
            openDashboard()
            openPlantDetail()
            waitUnmerged(openPhotoMatcher("10/02/2026"))
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Photo added"), useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }

            clickUnmerged(openPhotoMatcher("10/02/2026"))
            waitUnmerged(hasTestTag("timeline_viewer"))
            clickLast(hasClickAction() and hasText("Delete photo"))
            waitUntilAtLeastOneExists(hasText("Delete this photo?"), TIMEOUT)
            clickLast(hasClickAction() and hasText("Cancel"))
            waitUntilDoesNotExist(hasText("Delete this photo?"), TIMEOUT)
            assertEquals(0, graph.server.photoDeletes)

            clickLast(hasClickAction() and hasText("Delete photo"))
            waitUntilAtLeastOneExists(hasText("Delete this photo?"), TIMEOUT)
            clickLast(hasClickAction() and hasText("Delete", substring = false))
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.photoDeletes == 1 }
            waitGoneUnmerged(hasTestTag("timeline_viewer"))
            waitGoneUnmerged(openPhotoMatcher("10/02/2026"))
            waitUnmerged(openPhotoMatcher("09/30/2026"))
            waitUntil(timeoutMillis = TIMEOUT) {
                onAllNodes(hasText("Photo added"), useUnmergedTree = true).fetchSemanticsNodes().size == 1
            }
            assertEquals(listOf("ph-old"), graph.server.photos.map { it.id })

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun timeline_offline_keepsCache_andAddSaysNetworkRequired() {
        val graph = newHarness()
        seedTimeline(graph.server)
        val repo = graph.opener.open("73")
        runBlocking {
            repo.refresh()
            repo.timeline.refresh("det-1")
        }
        graph.server.photosThrows = IllegalStateException("Connection reset")
        graph.server.eventsThrows = IllegalStateException("Connection reset")
        val offline = MutableStateFlow(false)
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "en") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = false) {
                        PlantDetailScreen(
                            userId = "73",
                            plantId = "det-1",
                            opener = graph.opener,
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                            isOnline = offline,
                        )
                    }
                }
            }
            waitUnmerged(openPhotoMatcher("10/02/2026"))
            waitUnmerged(hasTestTag("timeline_offline"))
            clickUnmerged(hasClickAction() and hasTestTag("timeline_addPhoto"))
            waitUntilAtLeastOneExists(hasSubText("Network connection required to add a photo"), TIMEOUT)
            // No camera/gallery choice is offered offline, and nothing is sent.
            assertTrue(onAllNodes(hasText("Camera")).fetchSemanticsNodes().isEmpty())
            val messageTop = onNode(hasTestTag("timeline_message"), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            val photoTop = onAllNodes(hasTestTag("timeline_photo_ph-new"), useUnmergedTree = true).fetchSemanticsNodes().single().positionInRoot.y
            assertTrue(messageTop < photoTop, "message next to the button, above the photos")
            assertEquals(0, graph.server.photoPosts)
            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // VAL-CARD-002: the watering line stays on one line at 320 dp (ru, n = 11)
    // ------------------------------------------------------------------

    @Test
    fun waterDueIsOneLine() {
        waterDueLineCase(frequencyDays = 14, wateredDaysAgo = 3, expected = "Полить через 11 дней", fontScale = 1f) {
            lastBounds ->
            // The old «Осталось …» pill is gone from the watering card; the only such texts left belong to the
            // fertilize/repot/prune cards further down.
            val leftovers = onAllNodes(hasText("Осталось", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue(leftovers.all { it.boundsInRoot.top > lastBounds.bottom }, "no «Осталось» in the watering card")
            assertTrue(
                onAllNodes(hasText("Последний полив: 3 дн. назад", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /**
     * The longest form, «Пора полить: 21 день назад», at font scale 1.3 on a 411 dp phone (elt_test) stays whole on
     * one line: at full size it is wider than the card, so the line steps down.
     */
    @Test
    fun longestWaterDueFitsAtFontScale13() {
        waterDueLineCase(
            frequencyDays = 7,
            wateredDaysAgo = 28,
            expected = "Пора полить: 21 день назад",
            fontScale = 1.3f,
            widthDp = 411,
        ) {}
    }

    private fun waterDueLineCase(
        frequencyDays: Int,
        wateredDaysAgo: Int,
        expected: String,
        fontScale: Float,
        widthDp: Int = 320,
        extra: androidx.compose.ui.test.ComposeUiTest.(androidx.compose.ui.geometry.Rect) -> Unit,
    ) {
        val graph = newHarness()
        val today = site.xmpp.greenthumb.ui.components.pickerToday()
        graph.server.waterFrequencyDays = frequencyDays
        graph.server.lastWateredDate =
            kotlinx.datetime.LocalDate.fromEpochDays(today.toEpochDays() - wateredDaysAgo).toString()
        val repo = graph.opener.open("73")
        runBlocking { repo.refresh() }
        runDesktopComposeUiTest {
            setContent {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "ru") {
                        site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier.width(widthDp.dp),
                            ) {
                                PlantDetailScreen(
                                    userId = "73",
                                    plantId = "det-1",
                                    opener = graph.opener,
                                    onBack = {},
                                    onDeleted = {},
                                    deleteErrorState = PlantDeleteErrorState(),
                                )
                            }
                        }
                    }
                }
            }
            val line = hasTestTag("plantDetail_waterDue")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(line, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val node = onNode(line, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals(expected, node.config[SemanticsProperties.Text].joinToString("") { it.text })
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            val layout = layouts.single()
            assertEquals(1, layout.lineCount, "one line")
            assertTrue(
                !layout.isLineEllipsized(0) && !layout.hasVisualOverflow,
                "no ellipsis, no overflow: width ${layout.multiParagraph.width} of ${layout.layoutInput.constraints.maxWidth}, " +
                    "font ${layout.layoutInput.style.fontSize}, height overflow ${layout.didOverflowHeight}",
            )
            val lineHeight = androidx.compose.ui.unit.Density(density.density, fontScale).run {
                site.xmpp.greenthumb.ui.theme.GtType.waterDue.run { fontSize.toPx() * lineHeight.value }
            }
            assertTrue(node.size.height < 2 * lineHeight, "height ${node.size.height} < 2 × $lineHeight")
            // The bar and the last watering sit under the line.
            val barTop = onNode(hasTestTag("plantDetail_waterProgress"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val lastBounds = onNode(hasTestTag("plantDetail_lastWatered"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(node.boundsInRoot.bottom <= barTop && barTop < lastBounds.top, "line, bar, last watering top to bottom")
            extra(lastBounds)
            runOnIdle { graph.close() }
        }
    }
    // ------------------------------------------------------------------
    // 0.8.1: a frequency without a last date asks for the date (morning notes item 11)
    // ------------------------------------------------------------------

    /** One line on a 360 dp phone at font 1.3; on a 320 dp one it may wrap, but never cut. */
    @Test
    fun careCardWithoutLastDate_saysSetTheDate_inRussianAt13_andAsksForTheDate() {
        careNeedsDateCase(locale = "ru", expected = "Укажите дату", title = "Удобрение", fontScale = 1.3f, widthDp = 360, ru = true)
        careNeedsDateCase(locale = "ru", expected = "Укажите дату", title = "Удобрение", fontScale = 1.3f, widthDp = 320, ru = true)
    }

    @Test
    fun careCardWithoutLastDate_saysSetTheDate_inEnglishAt13_andAsksForTheDate() {
        careNeedsDateCase(locale = "en", expected = "Set the date", title = "Fertilizing", fontScale = 1.3f, widthDp = 360, ru = false)
        careNeedsDateCase(locale = "en", expected = "Set the date", title = "Fertilizing", fontScale = 1.3f, widthDp = 320, ru = false)
    }

    /** A dated care card keeps "tap = done today"; TalkBack hears the action ("Fertilize"), not just the title. */
    @Test
    fun careCardWithDate_tapActionIsNamed_andStillMarksToday() {
        val graph = newHarness()
        val today = site.xmpp.greenthumb.ui.components.pickerToday()
        val repo = graph.opener.open("73")
        runBlocking { repo.refresh() }
        runDesktopComposeUiTest {
            setContent {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = "en") {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                        // Phone width: in the default 1024 dp window the cards sit below the fold.
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                            PlantDetailScreen(
                                userId = "73",
                                plantId = "det-1",
                                opener = graph.opener,
                                onBack = {},
                                onDeleted = {},
                                deleteErrorState = PlantDeleteErrorState(),
                            )
                        }
                    }
                }
            }
            val card = hasClickAction() and hasText("Fertilizing", substring = true)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            val label = onNode(card).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label
            assertEquals("Fertilize", label)
            onNode(card).performClick()
            waitUntil(timeoutMillis = TIMEOUT) {
                graph.server.lastPatchBody?.contains("\"last_fertilized_date\":\"$today\"") == true
            }
            runOnIdle { graph.close() }
        }
    }

    private fun careNeedsDateCase(locale: String, expected: String, title: String, fontScale: Float, widthDp: Int, ru: Boolean) {
        val dialogTitle = if (ru) "Когда это делали последний раз?" else "When was it last done?"
        val cancel = if (ru) "Отмена" else "Cancel"
        val done = if (ru) "Готово" else "Done"
        val graph = newHarness()
        graph.server.lastFertilizedDate = null
        graph.server.lastRepottedDate = null
        val today = site.xmpp.greenthumb.ui.components.pickerToday()
        val repo = graph.opener.open("73")
        runBlocking { repo.refresh() }
        runDesktopComposeUiTest {
            setContent {
                val base = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(base.density, fontScale),
                ) {
                    site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = locale) {
                        site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                            androidx.compose.foundation.layout.Box(
                                modifier = androidx.compose.ui.Modifier.width(widthDp.dp),
                            ) {
                                PlantDetailScreen(
                                    userId = "73",
                                    plantId = "det-1",
                                    opener = graph.opener,
                                    onBack = {},
                                    onDeleted = {},
                                    deleteErrorState = PlantDeleteErrorState(),
                                )
                            }
                        }
                    }
                }
            }
            val label = hasText(expected)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
            // Fertilize and repot ask for the date; the old text is gone.
            assertTrue(onAllNodes(hasText("Не настроено"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodes(hasText("Not configured"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            onAllNodes(label, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                if (widthDp >= 360) assertEquals(1, layout.lineCount, "\"$expected\" on one line at font $fontScale, $widthDp dp")
                // A wrap-content Text reports didOverflowWidth against its paragraph width, so measure the lines.
                val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                assertTrue(
                    (0 until layout.lineCount).none { layout.isLineEllipsized(it) } && !layout.didOverflowHeight &&
                        widest <= layout.layoutInput.constraints.maxWidth,
                    "no ellipsis, no overflow at $widthDp dp: widest line $widest of ${layout.layoutInput.constraints.maxWidth}",
                )
            }
            val card = hasClickAction() and hasText(title, substring = true) and hasText(expected, substring = true)
            // The tap asks for the date and writes nothing yet: a care event would land in the journal.
            onNode(card).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText(dialogTitle)).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasText(dialogTitle), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                assertTrue((0 until layout.lineCount).none { layout.isLineEllipsized(it) }, "dialog title not cut at $widthDp dp")
            }
            waitForIdle()
            assertEquals(null, graph.server.lastPatchBody, "no PATCH before the date is confirmed")
            // Cancel changes nothing.
            onNode(hasClickAction() and hasText(cancel)).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText(dialogTitle)).fetchSemanticsNodes().isEmpty() }
            waitForIdle()
            assertEquals(null, graph.server.lastPatchBody, "Cancel sends no PATCH")
            assertEquals(2, onAllNodes(hasText(expected), useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(
                expected,
                onNode(card).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label,
                "TalkBack names the tap action",
            )
            // Done saves the picked day, not today.
            val picked = if (today.day > 1) kotlinx.datetime.LocalDate(today.year, today.month, 1) else today
            val pickedText = androidx.compose.material3.DatePickerDefaults.dateFormatter().formatDate(
                site.xmpp.greenthumb.ui.components.pickerMillis(picked),
                site.xmpp.greenthumb.ui.components.calendarLocaleOf(locale),
                forContentDescription = true,
            )!!
            onNode(card).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText(dialogTitle)).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasClickAction() and hasText(pickedText, substring = true, ignoreCase = true)).performClick()
            onNode(hasClickAction() and hasText(done)).performClick()
            waitUntil(timeoutMillis = TIMEOUT) {
                graph.server.lastPatchBody?.contains("\"last_fertilized_date\":\"$picked\"") == true
            }
            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // 0.8.2: «Отменить» after a care card mark (care journal item (в))
    // ------------------------------------------------------------------

    private fun ComposeUiTest.showDetail(
        graph: Harness,
        locale: String,
        fontScale: Float = 1f,
        screenReaderOn: () -> Boolean = { false },
    ) {
        setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale),
            ) {
                site.xmpp.greenthumb.core.platform.AppEnvironment(customAppLocale = locale) {
                    site.xmpp.greenthumb.ui.theme.GreenThumbTheme(darkTheme = true) {
                        androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                            PlantDetailScreen(
                                userId = "73",
                                plantId = "det-1",
                                opener = graph.opener,
                                onBack = {},
                                onDeleted = {},
                                deleteErrorState = PlantDeleteErrorState(),
                                screenReaderOn = screenReaderOn,
                            )
                        }
                    }
                }
            }
        }
    }

    /** A dated card: tap marks today, «Отменить» sends the previous date back; ru at font 1.3. */
    @Test
    fun careCardTap_offersUndo_thatRestoresThePreviousDate() {
        val graph = newHarness()
        val today = site.xmpp.greenthumb.ui.components.pickerToday()
        runBlocking { graph.opener.open("73").refresh() }
        runDesktopComposeUiTest {
            showDetail(graph, "ru", fontScale = 1.3f)
            val card = hasClickAction() and hasText("Удобрение", substring = true)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNode(card).performClick()
            val undo = hasClickAction() and hasText("Отменить")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("""{"last_fertilized_date":"$today"}""", graph.server.lastPatchBody)
            assertTrue(onAllNodes(hasText("Отмечено: Удобрение", substring = true)).fetchSemanticsNodes().isNotEmpty(), "snackbar names the care")
            // A second tap while the offer is open: one undo still returns to the date before the first tap.
            onNode(card).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 2 }
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
            onNode(undo).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 3 }
            assertEquals("""{"last_fertilized_date":"2026-08-01"}""", graph.server.lastPatchBody)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isEmpty() }
            runOnIdle { graph.close() }
        }
    }

    /** A card without a date: the picked date can be undone too, back to no date (PATCH null). */
    @Test
    fun careDatePick_offersUndo_thatClearsTheDateAgain() {
        val graph = newHarness()
        graph.server.lastFertilizedDate = null
        val today = site.xmpp.greenthumb.ui.components.pickerToday()
        runBlocking { graph.opener.open("73").refresh() }
        runDesktopComposeUiTest {
            showDetail(graph, "en")
            val card = hasClickAction() and hasText("Fertilizing", substring = true) and hasText("Set the date", substring = true)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNode(card).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText("When was it last done?")).fetchSemanticsNodes().isNotEmpty() }
            onNode(hasClickAction() and hasText("Done")).performClick()
            val undo = hasClickAction() and hasText("Undo")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("""{"last_fertilized_date":"$today"}""", graph.server.lastPatchBody)
            onNode(undo).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 2 }
            assertEquals("""{"last_fertilized_date":null}""", graph.server.lastPatchBody)
            runOnIdle { graph.close() }
        }
    }

    /** Without a tap on «Undo» the bar goes away by itself and nothing more is sent; watering offers no undo. */
    @Test
    fun undoBar_closesByItself_andWateringHasNone() {
        val graph = newHarness()
        runBlocking { graph.opener.open("73").refresh() }
        runDesktopComposeUiTest {
            showDetail(graph, "en")
            val card = hasClickAction() and hasText("Fertilizing", substring = true)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNode(card).performClick()
            val undo = hasClickAction() and hasText("Undo")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(2_000)
            waitForIdle()
            assertTrue(
                onAllNodes(undo).fetchSemanticsNodes().isNotEmpty(),
                "the bar outlives a two-second look: a wrong mark is noticed well inside 3 s",
            )
            mainClock.advanceTimeBy(CARE_UNDO_MILLIS - 2_000 + 500)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isEmpty() }
            assertEquals(1, graph.server.patchCount)
            onNode(hasClickAction() and hasContentDescription("Water", substring = true)).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 2 }
            waitForIdle()
            assertTrue(onAllNodes(undo).fetchSemanticsNodes().isEmpty(), "no undo after watering")
            runOnIdle { graph.close() }
        }
    }

    /**
     * With a screen reader on the bar has no timer: it is still there a minute later, and «Undo» then works.
     * Nobody who needs a screen reader loses the offer while the message is still being read.
     */
    @Test
    fun undoBar_waitsForTheUser_whenAScreenReaderIsOn() {
        val graph = newHarness()
        runBlocking { graph.opener.open("73").refresh() }
        runDesktopComposeUiTest {
            showDetail(graph, "en", screenReaderOn = { true })
            val card = hasClickAction() and hasText("Fertilizing", substring = true)
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
            onNode(card).performClick()
            val undo = hasClickAction() and hasText("Undo")
            waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(undo).fetchSemanticsNodes().isNotEmpty() }
            mainClock.advanceTimeBy(60_000)
            waitForIdle()
            assertTrue(
                onAllNodes(undo).fetchSemanticsNodes().isNotEmpty(),
                "no timer with a screen reader: the offer does not vanish on its own",
            )
            assertTrue(
                onAllNodes(hasContentDescription("Dismiss")).fetchSemanticsNodes().isNotEmpty(),
                "the bar has a close button, so it can be dismissed without a timer",
            )
            onNode(undo).performClick()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.patchCount == 2 }
            assertTrue(onAllNodes(undo).fetchSemanticsNodes().isEmpty(), "the bar goes once the user acts")
            runOnIdle { graph.close() }
        }
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
private fun String?.jsonOrNull(): String = this?.let { "\"$it\"" } ?: "null"

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