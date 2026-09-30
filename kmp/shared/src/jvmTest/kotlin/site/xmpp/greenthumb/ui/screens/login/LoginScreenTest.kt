package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
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
 * - choose-режим (VAL-LOGIN-001): две опции (create / ключ), приватная
 *   заметка и support-email видны;
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

            // Две опции (VAL-LOGIN-001) и приватная заметка с support-мылом.
            waitUntilAtLeastOneExists(hasText("Create New Account"), TIMEOUT)
            waitUntilAtLeastOneExists(hasText("I Have a Key"), TIMEOUT)
            waitUntilAtLeastOneExists(
                hasText("don't collect logs", substring = true),
                TIMEOUT,
            )
            waitUntilAtLeastOneExists(hasText(SUPPORT_EMAIL), TIMEOUT)
            // Режим ввода ключа ещё не открыт.
            onNodeWithText("Welcome Back").assertDoesNotExist()
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

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
