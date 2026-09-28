@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.FcmPushSubscriptions
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.platform.PushOutcome
import site.xmpp.greenthumb.core.platform.PushSubscriptions
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.AppSettingsKeys
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.ThemePreference
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.PlantRepositoryOpener

/**
 * VAL-PUSH-007 (локальная часть Stage 9 п.4–6, фича kmp-push-offline-routing):
 * fake-SignedIn-харнесс полной поверхности App() проверяет
 *
 * 1. язык UI в теле POST subscribe-fcm — 'ru'/'en' следует за выбором языка
 *    (перехват тела тестом — разрешённая контрактом evidence; live-запросы
 *    не делаются — стоп M9 на live-аккаунты);
 * 2. вызов настоящей кнопки тестового уведомления профиля
 *    ([PushTokens.sendLocalTestNotification] — Success-модалка RN-паритета).
 *
 * Сеть — реальный шов [FcmPushSubscriptions] поверх MockEngine: двойник
 * [PushTokens] делегирует ему сеть (канал/разрешение/токен FCM на JVM
 * не существуют), поэтому проверяется ИМЕННО шов UI → подписки, как в проде.
 */
@OptIn(ExperimentalTestApi::class)
class PushLanguageWireTest {

    private var harness: Harness? = null

    @AfterTest
    fun cleanup() {
        harness?.close()
        harness = null
    }

    // ------------------------------------------------------------------
    // Двойники (механика ProfileScreenTest / LocaleNavigationParityTest)
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

    /** Запись запроса (перехват провода тестом — evidence VAL-PUSH-007). */
    private class CapturedRequest(
        val method: String,
        val path: String,
        val body: String?,
    )

    private class FakeServer {
        var requests = mutableListOf<CapturedRequest>()

        private val JSON_HEADERS = headersOf("Content-Type", "application/json")

        private fun userJson(): String =
            """{"user":{"id":91,"name":"kmp-val-push-lang","recovery_key":"key-push-91",""" +
                """"notification_time":"09:00","created_at":"2026-09-28T10:00:00.000Z"}}"""

        val handler: MockRequestHandler = { request ->
            requests += CapturedRequest(
                method = request.method.value,
                path = request.url.encodedPath,
                body = (request.body as? TextContent)?.text,
            )
            when (request.url.encodedPath) {
                // Старт: сессия жива (fake-SignedIn харнесс).
                "/api/auth/me" -> respond(userJson(), HttpStatusCode.OK, JSON_HEADERS)
                "/api/plants" -> respond("[]", HttpStatusCode.OK, JSON_HEADERS)
                "/api/push/subscribe-fcm" -> respond("""{"ok":true}""", HttpStatusCode.OK, JSON_HEADERS)
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, JSON_HEADERS)
                else -> respond("{}", HttpStatusCode.NotFound, JSON_HEADERS)
            }
        }

        fun postsToSubscribeFcm(): List<CapturedRequest> =
            requests.filter { it.method == "POST" && it.path == "/api/push/subscribe-fcm" }
    }

    /**
     * Push-двойник харнесса: сеть — РЕАЛЬНЫЙ шов [FcmPushSubscriptions]
     * (прод-провод subscribe-fcm), платформенные шаги (канал/разрешение/токен)
     * на JVM не существуют и заменены фиксированным токеном; тестовое
     * уведомление считается вызовом (Android-actual проверен эмулятором).
     * Статус подписки — выключен (тумблер off: клики дают ON → POST, OFF →
     * DELETE, ON → POST — как у пользователя).
     */
    private class NetworkedPush(private val seam: PushSubscriptions) : PushTokens() {
        var sendCalls = 0

        /** Статус подписки resolves (тумблер отрисован). */
        var statusResolved = false

        /** Значение статуса (GET fcm-subscription в проде): off по умолчанию. */
        var statusValue: Boolean = false

        override suspend fun requestSubscribe(language: String): PushOutcome {
            return try {
                seam.subscribe("fcm-harness-token", "android", language)
                PushOutcome.Subscribed("fcm-harness-token")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                PushOutcome.Error(error.message ?: error.toString())
            }
        }

        override suspend fun subscriptionStatus(): Boolean {
            statusResolved = true
            return statusValue
        }

        override suspend fun unsubscribe() {
            seam.unsubscribe()
        }

        override suspend fun sendLocalTestNotification() {
            sendCalls++
        }
    }

    private class Harness(
        val server: FakeServer,
        val settings: FakeSettings,
        val session: SessionManager,
        val push: NetworkedPush,
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

    private fun newHarness(): Harness {
        val dir: File = createTempDirectory(prefix = "gt-push-lang").toFile()
        val databases = JvmPlantDatabases(dir)
        val server = FakeServer()
        val settings = FakeSettings()
        val client = ApiClient(MockEngine(server.handler), NoSessionRecoveryProvider)
        val api = GreenThumbApi(client)
        val session = SessionManager(settings = settings, secure = FakeSecureStore(), handoff = NoHandoff(), api = api)
        val push = NetworkedPush(FcmPushSubscriptions(api))
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
        return Harness(server, settings, session, push, opener, connectivity, client, repos, dir)
            .also { harness = it }
    }

    // ------------------------------------------------------------------
    // Клик-помощники (механика ProfileScreenTest: семантика без вьюпорта)
    // ------------------------------------------------------------------

    private fun hasAnyDescendantOrSelfRole(role: Role) = SemanticsMatcher(
        "has role $role",
    ) { node -> node.config.getOrNull(SemanticsProperties.Role) == role }

    private fun androidx.compose.ui.test.ComposeUiTest.clickAt(
        matcher: SemanticsMatcher,
        index: Int = 0,
    ) {
        onAllNodes(matcher)[index].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickSwitch() {
        clickAt(hasClickAction() and hasAnyDescendantOrSelfRole(Role.Switch))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickButton(label: String) {
        clickAt(hasClickAction() and hasText(label) and hasAnyDescendantOrSelfRole(Role.Button))
    }

    // ------------------------------------------------------------------
    // VAL-PUSH-007: язык UI в теле POST subscribe-fcm
    // ------------------------------------------------------------------

    @Test
    fun subscribePostsCurrentUiLanguage_ruThenEn() {
        val graph = newHarness()
        runBlocking { graph.settings.setLanguage(AppLanguage.Ru) }
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }

            // fake-SignedIn (me → 200): старт на дашборде, вкладки на ru.
            waitUntilAtLeastOneExists(hasText("Растения"), TIMEOUT)
            onNodeWithText("Профиль").performClick()
            waitUntilAtLeastOneExists(hasText("Мой профиль"), TIMEOUT)

            // Тумблер: статус подписки resolves (double → true) → switch on.
            waitUntil(timeoutMillis = TIMEOUT) { graph.push.statusResolved }
            waitUntilAtLeastOneExists(hasAnyDescendantOrSelfRole(Role.Switch), TIMEOUT)

            // Вкл (ru): POST с языком UI.
            clickSwitch()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.postsToSubscribeFcm().size == 1 }
            val ruBody = graph.server.postsToSubscribeFcm().single().body
            assertTrue(
                ruBody!!.contains("\"language\":\"ru\"") &&
                    ruBody.contains("\"fcm_token\":\"fcm-harness-token\"") &&
                    ruBody.contains("\"platform\":\"android\""),
                "тело POST subscribe-fcm при ru: $ruBody",
            )
            // Модалка успеха (RN showAlert) — закрыть.
            waitUntilAtLeastOneExists(hasText("Уведомления включены"), TIMEOUT)
            clickButton("OK")
            waitUntilDoesNotExist(hasText("Уведомления включены"), TIMEOUT)

            // Смена языка UI — тот же триггер, что пикер профиля (AppSettings).
            // key(localAppLocale) пересоздаёт remember-состояние экрана
            // (VAL-I18N-006): тумблер перечитывает статус (double → off),
            // следующий клик — снова включение (ON → POST).
            runBlocking { graph.settings.setLanguage(AppLanguage.En) }
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            waitUntil(timeoutMillis = TIMEOUT) { graph.push.statusResolved }
            waitUntilAtLeastOneExists(hasAnyDescendantOrSelfRole(Role.Switch), TIMEOUT)

            // Вкл (en): POST с языком 'en' (значение НА МОМЕНТ действия).
            clickSwitch()
            waitUntil(timeoutMillis = TIMEOUT) { graph.server.postsToSubscribeFcm().size == 2 }
            val enBody = graph.server.postsToSubscribeFcm().last().body
            assertTrue(
                enBody!!.contains("\"language\":\"en\"") &&
                    enBody.contains("\"platform\":\"android\""),
                "тело POST subscribe-fcm при en: $enBody",
            )

            runOnIdle { graph.close() }
        }
    }

    // ------------------------------------------------------------------
    // Кнопка тестового уведомления профиля (Stage 9 п.6): вызов actual'а
    // ------------------------------------------------------------------

    @Test
    fun profileTestNotificationButton_callsSendLocalTestNotification() {
        val graph = newHarness()
        runBlocking { graph.settings.setLanguage(AppLanguage.En) }
        // Подписка включена: строка тестового уведомления видима (RN —
        // только при pushEnabled).
        graph.push.statusValue = true
        runDesktopComposeUiTest {
            setContent { App(graph.session, graph.connectivity, graph.opener, graph.settings, graph.push) }

            waitUntilAtLeastOneExists(hasText("Plants"), TIMEOUT)
            onNodeWithText("Profile").performClick()
            waitUntilAtLeastOneExists(hasText("My Profile"), TIMEOUT)
            waitUntil(timeoutMillis = TIMEOUT) { graph.push.statusResolved }

            // Строка тестового уведомления видна при включённых пушах; клик —
            // вызов sendLocalTestNotification + Success-модалка (RN showAlert).
            assertEquals(0, graph.push.sendCalls)
            clickButton("Send Test Notification")
            waitUntil(timeoutMillis = TIMEOUT) { graph.push.sendCalls == 1 }
            waitUntilAtLeastOneExists(hasText("Test sent"), TIMEOUT)
            clickButton("OK")
            waitUntilDoesNotExist(hasText("Test sent"), TIMEOUT)

            runOnIdle { graph.close() }
        }
    }

    private companion object {
        /** Дедлайн ожиданий теста (мс): бут сессии + пересборка дерева. */
        const val TIMEOUT: Long = 10_000L
    }
}
