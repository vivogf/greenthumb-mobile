@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
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
 * Stage 12 п.4 (фича kmp-account-deletion-ui, VAL-REL-003 desktop-ветка):
 * строка «Delete Account» в профиле → экран подтверждения с последствиями →
 * отмена/подтверждение/ошибка. Чистка после подтверждения — матрица signOut
 * (ключ/cached_user), сервер получает DELETE /api/auth/account, UI приходит
 * на экран входа (граф пересобирает [App]).
 *
 * Двойники — механика ProfileScreenTest (там приватные): MockEngine-сервер,
 * in-memory хранилища, JvmPlantDatabases + AccountPlantGate. Push — дефолтная
 * заглушка харнесса (взаимодействий с пушами в этих кейсах нет).
 */
@OptIn(ExperimentalTestApi::class)
class DeleteAccountScreenTest {

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

    private class FakeServer {
        var meCount = 0
        var plantsCount = 0
        var createCount = 0
        var deleteAccountCount = 0
        var deleteAccountStatus: HttpStatusCode = HttpStatusCode.OK

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":83,"name":"kmp-val-delacc","recovery_key":"key-delacc-83",""" +
                """"notification_time":"09:00","created_at":"2026-10-01T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
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
                "/api/auth/account" -> {
                    deleteAccountCount++
                    respond("""{"success":true}""", deleteAccountStatus, JSON_HEADERS)
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
        val dir: File = File.createTempFile("gt-delete-screen", "").let { f ->
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

    private fun FakeSettings.presetEnglish(): Unit = runBlocking {
        setLanguage(AppLanguage.En)
    }

    // ------------------------------------------------------------------
    // Пролог: welcome → login → create → вкладки → Profile
    // (без push-параметра: дефолтная заглушка, кейсы пушей не трогают)
    // ------------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.openProfileTab() {
        waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
        onNodeWithText("Skip").performClick()
        waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
        onNodeWithText("Create New Account").performClick()
        waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
        onNode(hasClickAction() and hasText("Create Account")).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { harness!!.server.createCount >= 1 }
        onNodeWithText("I've Saved My Key", substring = true).performClick()
        waitUntilAtLeastOneExists(hasText("Maybe later"), TIMEOUT)
        onNodeWithText("Maybe later").performClick()
        waitUntil(timeoutMillis = TIMEOUT) { harness!!.server.plantsCount >= 1 }
        waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
        onNodeWithText("Profile").performClick()
        waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
    }

    private fun hasSubText(value: String) = hasText(value, substring = true)

    private fun androidx.compose.ui.test.ComposeUiTest.clickAt(
        matcher: androidx.compose.ui.test.SemanticsMatcher,
        index: Int = 0,
    ) {
        onAllNodes(matcher)[index].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickByLabel(text: String) {
        clickAt(hasClickAction() and hasText(text))
    }

    // ------------------------------------------------------------------
    // Кейсы (VAL-REL-003, desktop-ветка)
    // ------------------------------------------------------------------

    @Test
    fun deleteAccountScreen_opensFromProfile_showsConsequences_cancelReturns() {
        val graph = newHarness()
        graph.settings.presetEnglish()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, PushTokens()) }
            openProfileTab()

            // Строка в профиле → экран подтверждения с последствиями.
            clickByLabel("Delete Account")
            waitUntilAtLeastOneExists(hasText("Delete account"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("permanently deletes"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("plants, photos and notes"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSubText("recovery key will stop working"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Delete my account"), TIMEOUT)

            // Отмена — назад на профиль, запроса нет.
            clickByLabel("Cancel")
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            assertEquals(0, graph.server.deleteAccountCount, "отмена не отправляет DELETE")
            assertEquals("key-delacc-83", secureKey(graph), "ключ на месте")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun deleteAccount_confirm_sendsDelete_landsOnLogin_cleansLocally() {
        val graph = newHarness()
        graph.settings.presetEnglish()
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, PushTokens()) }
            openProfileTab()

            clickByLabel("Delete Account")
            waitUntilAtLeastOneExists(hasText("Delete account"), TIMEOUT)
            // Индекс 1: строка профиля (0) + кнопка экрана (1) с тем же текстом
            // «Delete Account»/«Delete my account» — кнопка экрана по точному
            // тексту confirm-лейбла.
            clickAt(hasClickAction() and hasText("Delete my account"))

            waitUntil(timeoutMillis = TIMEOUT) { graph.server.deleteAccountCount == 1 }
            // Граф пересобран на экран входа (SignedOut + интро видено).
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            // Матрица чистки (VAL-DATA-008): ключ и cached_user стёрты.
            assertNull(secureKey(graph), "recovery key удалён")
            assertNull(graph.settings.getCachedUserBlocking(), "cached_user удалён")

            runOnIdle { graph.close() }
        }
    }

    @Test
    fun deleteAccount_serverError_showsAlert_keepsAccount() {
        val graph = newHarness()
        graph.settings.presetEnglish()
        graph.server.deleteAccountStatus = HttpStatusCode.ServiceUnavailable
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, PushTokens()) }
            openProfileTab()

            clickByLabel("Delete Account")
            waitUntilAtLeastOneExists(hasText("Delete account"), TIMEOUT)
            clickAt(hasClickAction() and hasText("Delete my account"))

            // Диалог ошибки; аккаунт цел (ключ/cached_user на месте),
            // экран подтверждения остался (можно повторить или выйти назад).
            waitUntilAtLeastOneExists(hasText("Error"), TIMEOUT)
            assertEquals(1, graph.server.deleteAccountCount)
            assertEquals("key-delacc-83", secureKey(graph), "провал ключ сохраняет")
            assertTrue(graph.settings.getCachedUserBlocking() != null, "cached_user цел")
            clickByLabel("OK")
            waitUntilDoesNotExist(hasText("Error"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Delete my account"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Хелперы блокирующих чтений
    // ------------------------------------------------------------------

    private fun FakeSettings.getCachedUserBlocking(): UserDto? = runBlocking { getCachedUser() }

    private fun secureKey(graph: Harness): String? = runBlocking {
        graph.secure.get(SecureStoreKeys.RECOVERY_KEY)
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
