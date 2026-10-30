package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import site.xmpp.greenthumb.core.SUPPORT_EMAIL
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.AppSettingsKeys
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.HandoffPayload
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.ThemePreference
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Stage 11 п.3 (фича kmp-compose-ui-tests, VAL-TEST-002): экран логина —
 * открытие + ключевое действие + семантическая проверка.
 *
 * ПостСайнФлоу-тесты (PostSignInFlowTest) гоняют create/sign-in через полную
 * App(); здесь поверхность самого LoginScreen:
 * - choose-режим (VAL-LOGIN-001): две опции (create / ключ), короткая приватная
 *   заметка, ссылка на страницу о данных и support-email (M10 VAL-LOOK-012..015);
 * - ключевое действие: «I Have a Key» переключает в режим ввода ключа
 *   («Welcome Back»), назад возвращает choose (RN setMode('login')/back).
 *
 * Сеть не трогается: choose/login-режимы вызывают SessionManager только на
 * submit; MockEngine отвечает 404 на всё — вызовов нет, поверхность чистая.
 */
@OptIn(ExperimentalTestApi::class)
class LoginScreenTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    /** Ин-мемори двойники стора (сессия в этих кейсах сети не касается). */
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
        override suspend fun getLanguage(): AppLanguage? = null
        override suspend fun setLanguage(language: AppLanguage) = Unit
        override suspend fun getLayoutMode(): LayoutMode? = null
        override suspend fun setLayoutMode(mode: LayoutMode) = Unit
        override suspend fun getTheme(): ThemePreference? = null
        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun isIntroSeen(): Boolean = false
        override suspend fun setIntroSeen() = Unit
        override suspend fun getCachedUser(): site.xmpp.greenthumb.core.network.UserDto? = null
        override suspend fun setCachedUser(user: site.xmpp.greenthumb.core.network.UserDto) = Unit
        override suspend fun clearCachedUser() = Unit
        override val language = kotlinx.coroutines.flow.MutableStateFlow<AppLanguage?>(null)
        override val layoutMode = kotlinx.coroutines.flow.MutableStateFlow<LayoutMode?>(null)
        override val theme = kotlinx.coroutines.flow.MutableStateFlow<ThemePreference?>(null)
        override val introSeen = kotlinx.coroutines.flow.MutableStateFlow(false)
        override val cachedUser =
            kotlinx.coroutines.flow.MutableStateFlow<site.xmpp.greenthumb.core.network.UserDto?>(null)
        override suspend fun applyLegacyHandoffValues(
            language: AppLanguage?,
            theme: ThemePreference?,
            layoutMode: LayoutMode?,
            introSeen: Boolean?,
        ) = Unit
    }

    private class NoHandoff : site.xmpp.greenthumb.core.storage.HandoffSource {
        override fun readHandoff(): HandoffPayload? = null
        override fun clearHandoff() {}
    }

    /** 404-движок: любой неожидаемый вызов виден как не-OK, сеть не течёт. */
    private fun newSession(): SessionManager {
        val api = GreenThumbApi(
            site.xmpp.greenthumb.core.network.ApiClient(
                MockEngine {
                    respond(
                        "{}",
                        HttpStatusCode.NotFound,
                        headersOf("Content-Type", "application/json"),
                    )
                },
                object : site.xmpp.greenthumb.core.network.SessionRecoveryProvider {
                    override suspend fun getRecoveryKey(): String? = null
                    override suspend fun onSessionReset() = Unit
                },
            ),
        )
        return SessionManager(FakeSecureStore(), FakeSettings(), NoHandoff(), api)
    }

    /** Answers create-anonymous and login-recovery; records what reached the wire. */
    private class RecordingServer {
        /** `recovery_key` of the create-anonymous response; null leaves the field out. */
        var createRecoveryKey: String? = null
        val loginBodies = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var createCount = 0

        val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
            val headers = headersOf("Content-Type", "application/json")
            when (request.url.encodedPath) {
                "/api/auth/create-anonymous" -> {
                    createCount++
                    val keyField = createRecoveryKey?.let { "\"recovery_key\":\"$it\"," } ?: ""
                    respond("""{"user":{"id":91,$keyField"created_at":"2026-10-01T10:00:00.000Z"}}""", HttpStatusCode.OK, headers)
                }
                "/api/auth/login-recovery" -> {
                    loginBodies += (request.body as io.ktor.http.content.TextContent).text
                    respond("""{"user":{"id":91,"created_at":"2026-10-01T10:00:00.000Z"}}""", HttpStatusCode.OK, headers)
                }
                else -> respond("{}", HttpStatusCode.NotFound, headers)
            }
        }
    }

    private fun recordingSession(server: RecordingServer, secure: FakeSecureStore): SessionManager {
        val api = GreenThumbApi(
            site.xmpp.greenthumb.core.network.ApiClient(
                MockEngine(server.handler),
                object : site.xmpp.greenthumb.core.network.SessionRecoveryProvider {
                    override suspend fun getRecoveryKey(): String? = null
                    override suspend fun onSessionReset() = Unit
                },
            ),
        )
        return SessionManager(secure, FakeSettings(), NoHandoff(), api)
    }

    private fun rememberLocale() {
        if (originalLocale == null) originalLocale = Locale.getDefault()
    }

    @Test
    fun choose_mode_shows_options_privacy_hint_and_support_email() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        LoginScreen(session = newSession())
                    }
                }
            }

            // Две опции (VAL-LOGIN-001) и короткая приватная заметка с support-мылом.
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("I Have a Key"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText(SHORT_HINT_EN), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("More about data →") and hasClickAction(), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("If something breaks, write to:"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText(SUPPORT_EMAIL), TIMEOUT)
            // The long M9 text is gone from the start screen.
            onNode(hasText("stores your recovery key only as a hash", substring = true)).assertDoesNotExist()
            // Режим ввода ключа ещё не открыт.
            onNodeWithText("Welcome Back").assertDoesNotExist()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun hint_style_is_14_sp() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = true) {
                        LoginScreen(session = newSession())
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText(SHORT_HINT_RU), TIMEOUT)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            onNode(hasText(SHORT_HINT_RU)).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            kotlin.test.assertEquals(14.sp, layouts.single().layoutInput.style.fontSize)
            kotlin.test.assertEquals(20.sp, layouts.single().layoutInput.style.lineHeight)
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun contact_shows_support_email() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = true) {
                        LoginScreen(session = newSession())
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Если что-то сломалось — напишите:"), TIMEOUT)
            onNode(hasText("greenthumbapp@proton.me") and hasClickAction()).assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun privacy_link_opens_privacy_page() = try {
        rememberLocale()
        val opened = mutableListOf<String>()
        val uriHandler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
                    ) {
                        GreenThumbTheme(darkTheme = true) {
                            LoginScreen(session = newSession())
                        }
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Подробнее о данных →"), TIMEOUT)
            onNode(hasText("Подробнее о данных →") and hasClickAction()).performClick()
            waitForIdle()
            kotlin.test.assertEquals(listOf(site.xmpp.greenthumb.core.PRIVACY_POLICY_URL), opened)
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun privacy_link_without_a_browser_does_not_crash() = try {
        rememberLocale()
        var attempts = 0
        // AndroidUriHandler.openUri throws when no app can open https links (ActivityNotFoundException).
        val uriHandler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                attempts++
                throw IllegalArgumentException("No Activity found to handle Intent { act=android.intent.action.VIEW }")
            }
        }
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
                    ) {
                        GreenThumbTheme(darkTheme = true) {
                            LoginScreen(session = newSession())
                        }
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("More about data →"), TIMEOUT)
            onNode(hasText("More about data →") and hasClickAction()).performClick()
            waitForIdle()
            kotlin.test.assertEquals(1, attempts)
            onNode(hasText("Create New Account")).assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun card_fills_width_minus_outer_padding() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = true) {
                        LoginScreen(session = newSession())
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(LoginTags.CARD), TIMEOUT)
            val root = onNodeWithTag(LoginTags.ROOT).fetchSemanticsNode().boundsInRoot
            val card = onNodeWithTag(LoginTags.CARD).fetchSemanticsNode().boundsInRoot
            val inset = with(density) { 16.dp.toPx() }
            kotlin.test.assertEquals(root.left + inset, card.left, 0.5f)
            kotlin.test.assertEquals(root.right - inset, card.right, 0.5f)
            kotlin.test.assertEquals(root.width - 2 * inset, card.width, 0.5f)
        }
    } finally {
        restoreLocale()
    }

    /** VAL-LOOK-008: every tappable element of the three login modes is at least 48 dp. */
    @Test
    fun login_touch_targets_are_at_least_48_dp() = try {
        rememberLocale()
        for (start in LoginMode.entries) {
            runDesktopComposeUiTest {
                setContent {
                    AppEnvironment(customAppLocale = "ru") {
                        GreenThumbTheme(darkTheme = true) {
                            LoginScreen(session = newSession(), initialMode = start)
                        }
                    }
                }
                waitUntilAtLeastOneExists(hasTestTag(LoginTags.CARD), TIMEOUT)
                val min = with(density) { 48.dp.toPx() } - 0.5f
                val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
                kotlin.test.assertTrue(nodes.isNotEmpty(), "$start has tappable nodes")
                for (node in nodes) {
                    val b = node.boundsInRoot
                    kotlin.test.assertTrue(
                        b.width >= min && b.height >= min,
                        "$start: ${node.config} is ${b.width}x${b.height} px",
                    )
                }
            }
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun i_have_a_key_switches_to_login_mode() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            val session = newSession()
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        LoginScreen(session = session)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("I Have a Key"), TIMEOUT)

            // Ключевое действие: переход в режим ввода ключа — заголовок
            // «Welcome Back», поле ключа (editable) и кнопка «Sign In».
            onNodeWithText("I Have a Key").performClick()
            waitForIdle()
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            waitUntilAtLeastOneExists(hasSetTextAction(), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("Sign In"), TIMEOUT)
            onNodeWithText("I Have a Key").assertDoesNotExist()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun create_response_without_key_is_an_error_not_another_accounts_key() = try {
        rememberLocale()
        for (missing in listOf(null, "")) {
            val server = RecordingServer().apply { createRecoveryKey = missing }
            val secure = FakeSecureStore().apply {
                map[site.xmpp.greenthumb.core.storage.SecureStoreKeys.RECOVERY_KEY] = "previous-account-key"
            }
            val session = recordingSession(server, secure)
            val shownKeys = mutableListOf<String>()
            runDesktopComposeUiTest {
                setContent {
                    AppEnvironment(customAppLocale = "en") {
                        GreenThumbTheme(darkTheme = false) {
                            LoginScreen(session = session, onAccountCreated = { shownKeys += it }, initialMode = LoginMode.Create)
                        }
                    }
                }
                waitUntilAtLeastOneExists(hasText("Create Account"), TIMEOUT)
                onNode(androidx.compose.ui.test.hasClickAction() and hasText("Create Account")).performClick()
                waitUntil(timeoutMillis = TIMEOUT) { server.createCount == 1 }
                waitUntilAtLeastOneExists(hasText("Service is temporarily unavailable", substring = true), TIMEOUT)
                kotlin.test.assertEquals(emptyList(), shownKeys, "no key is shown for key=$missing")
                kotlin.test.assertFalse(
                    session.state.value is site.xmpp.greenthumb.core.storage.SessionState.SignedIn,
                    "the keyless account does not open",
                )
            }
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun typed_key_is_canonicalized_before_sending_and_storing() = try {
        rememberLocale()
        val server = RecordingServer()
        val secure = FakeSecureStore()
        val session = recordingSession(server, secure)
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        LoginScreen(session = session, initialMode = LoginMode.Login)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            onNode(hasSetTextAction()).performTextInput("  {ABCD-EF01-2345-6789-ABCD-EF01-2345-6789}  ")
            onNodeWithText("Sign In").performClick()
            waitUntil(timeoutMillis = TIMEOUT) { server.loginBodies.size == 1 }
            kotlin.test.assertEquals("""{"recoveryKey":"$CANONICAL_KEY"}""", server.loginBodies.single())
            waitUntil(timeoutMillis = TIMEOUT) {
                kotlinx.coroutines.runBlocking { secure.get(site.xmpp.greenthumb.core.storage.SecureStoreKeys.RECOVERY_KEY) } ==
                    CANONICAL_KEY
            }
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun malformed_typed_key_is_not_sent() = try {
        rememberLocale()
        val server = RecordingServer()
        val session = recordingSession(server, FakeSecureStore())
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        LoginScreen(session = session, initialMode = LoginMode.Login)
                    }
                }
            }
            waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
            // A hyphen after three hex digits: Postgres rejects it, so must we.
            onNode(hasSetTextAction()).performTextInput("abc-def01-2345-6789-abcd-ef0123456789")
            onNodeWithText("Sign In").performClick()
            waitUntilAtLeastOneExists(hasText("Invalid recovery key"), TIMEOUT)
            waitForIdle()
            kotlin.test.assertEquals(emptyList(), server.loginBodies.toList(), "nothing reached the server")
        }
    } finally {
        restoreLocale()
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L

        const val CANONICAL_KEY: String = "abcdef01-2345-6789-abcd-ef0123456789"

        const val SHORT_HINT_EN: String =
            "No ads, analytics or visit logs. Your sign-in key stays with you — the server keeps only its fingerprint, which cannot rebuild the key. Crash reports are anonymous."

        const val SHORT_HINT_RU: String =
            "Без рекламы, аналитики и журналов посещений. Ключ входа хранится у вас — сервер помнит только его отпечаток, по которому ключ не восстановить. Отчёты о сбоях анонимные."
    }
}
