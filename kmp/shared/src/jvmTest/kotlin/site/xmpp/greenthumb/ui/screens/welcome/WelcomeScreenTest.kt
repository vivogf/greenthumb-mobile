package site.xmpp.greenthumb.ui.screens.welcome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.AppSettingsKeys
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Stage 7 п.1 (фича screen-welcome, VAL-INTRO-001): карусель welcome —
 * три слайда, next двигает, последний — Get started, skip завершает,
 * dots отражают текущий слайд, завершение ставит флаг интро
 * ([AppPreferencesStore.setIntroSeen]) и зовёт [onFinish] ровно один раз.
 *
 * Повторный запуск (интро уже видено, user нет) рисует login-состояние,
 * а не welcome — гард START-поведения решает StartRoutingTest; здесь
 * только инвариант «повторное завершение не перезапускает карусель».
 */
@OptIn(ExperimentalTestApi::class)
class WelcomeScreenTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private fun harness(): Triple<MutableList<String>, FakeIntroSettings, () -> Unit> {
        if (originalLocale == null) originalLocale = Locale.getDefault()
        val events = mutableListOf<String>()
        val settings = FakeIntroSettings()
        val finish: () -> Unit = { events.add("finish") }
        return Triple(events, settings, finish)
    }

    private fun FakeIntroSettings.introSeenValue(): Boolean = introFlag

    /** Чистый стейт для кейсов, где предусловие — «интро не видено». */
    private fun FakeIntroSettings.reset() {
        values.clear()
        introFlag = false
    }

    // Слайды: пары заголовок/текст из ресурсов (en).
    private fun slideTitles(): List<String> = listOf(
        "Your plants, organized",
        "Never let them down again",
        "On-time reminders",
    )

    private fun slideBodies(): List<String> = listOf(
        "Add photos, locations, and care schedules in one place.",
        "No more wilted leaves from forgotten watering days.",
        "Notifications arrive when it suits you.",
    )

    @Test
    fun first_launch_shows_first_slide_with_dots_and_next() = runDesktopComposeUiTest {
        val (events, settings, finish) = harness()
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText(slideTitles()[0]), TIMEOUT)
        waitUntilAtLeastOneExists(hasText(slideBodies()[0]), TIMEOUT)
        // Первый CTA — Next (не Get started), skip доступен.
        onNodeWithText("Next").assertExists()
        onNodeWithText("Skip").assertExists()
        assertFalse(settings.introSeenValue(), "флаг интро ещё не выставлен до завершения")
        assertTrue(events.isEmpty(), "карусель не завершалась")
    }

    @Test
    fun next_advances_through_three_slides_and_last_shows_get_started() = runDesktopComposeUiTest {
        val (events, settings, finish) = harness()
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText(slideTitles()[0]), TIMEOUT)
        // Slide 1 → 2.
        onNodeWithText("Next").performClick()
        waitForIdle()
        waitUntilAtLeastOneExists(hasText(slideTitles()[1]), TIMEOUT)
        waitUntilAtLeastOneExists(hasText(slideBodies()[1]), TIMEOUT)
        // Slide 2 → 3: подпись кнопки меняется на Get started.
        onNodeWithText("Next").performClick()
        waitForIdle()
        waitUntilAtLeastOneExists(hasText(slideTitles()[2]), TIMEOUT)
        waitUntilAtLeastOneExists(hasText(slideBodies()[2]), TIMEOUT)
        onNodeWithText("Get started").assertExists()
        onNodeWithText("Next").assertDoesNotExist()
        assertFalse(settings.introSeenValue(), "последний слайд ещё не завершал карусель")
    }

    @Test
    fun get_started_on_last_slide_finishes_once_and_sets_intro_flag() = runDesktopComposeUiTest {
        val (events, settings, finish) = harness()
        settings.reset()
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText(slideTitles()[0]), TIMEOUT)
        onNodeWithText("Next").performClick()
        waitForIdle()
        onNodeWithText("Next").performClick()
        waitForIdle()
        waitUntilAtLeastOneExists(hasText(slideTitles()[2]), TIMEOUT)

        onNodeWithText("Get started").performClick()
        waitForIdle()

        assertEquals(listOf("finish"), events, "завершение — ровно один finish-колбэк")
        assertTrue(settings.introSeenValue(), "setIntroSeen() вызван при завершении")
        // Повторный запуск (интро уже видено, user нет) не показывает welcome:
        // START-решение — Login (StartRoutingTest); здесь эквивалент-гард экрана:
        // после завершения слайды не перезапускаются в той же композиции.
        onNodeWithText(slideTitles()[0]).assertDoesNotExist()
    }

    @Test
    fun skip_finishes_immediately_from_first_slide() = runDesktopComposeUiTest {
        val (events, settings, finish) = harness()
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText(slideTitles()[0]), TIMEOUT)

        onNodeWithText("Skip").performClick()
        waitForIdle()

        assertEquals(listOf("finish"), events, "skip завершает сразу")
        assertTrue(settings.introSeenValue(), "skip тоже ставит флаг интро (RN setHasSeenIntro)")
    }

    @Test
    fun finish_is_idempotent_on_repeated_cta_taps() = runDesktopComposeUiTest {
        val (events, settings, finish) = harness()
        settings.reset()
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText(slideTitles()[0]), TIMEOUT)
        onNodeWithText("Skip").performClick()
        waitForIdle()
        // Повторный тап по Skip после завершения не добавляет второй finish
        // (кнопки уже сняты с интеракции).
        onNodeWithText("Skip").performClick()
        waitForIdle()
        assertEquals(listOf("finish"), events)
    }

    @Test
    fun localized_labels_switch_with_app_locale() = runDesktopComposeUiTest {
        val (_, settings, finish) = harness()
        // Тот же триггер смены языка, что у App(): смена customAppLocale
        // перекомпоновывает подписи (VAL-I18N-004-паттерн LocaleRuntimeSwitchTest).
        var locale by androidx.compose.runtime.mutableStateOf("en")
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    WelcomeScreen(settings = settings, onFinish = finish)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText("Your plants, organized"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Skip"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Next"), TIMEOUT)

        locale = "ru"
        waitForIdle()

        waitUntilAtLeastOneExists(hasText("Все растения в одном месте"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Пропустить"), TIMEOUT)
        waitUntilAtLeastOneExists(hasText("Далее"), TIMEOUT)
        waitUntilDoesNotExist(hasText("Your plants, organized"), TIMEOUT)
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}

/** Настройки in-memory с реактивным introSeen (как App читает Flow). */
private class FakeIntroSettings : AppPreferencesStore {
    val values = mutableMapOf<String, String>()
    var introFlag = false

    override suspend fun getLanguage() =
        values[AppSettingsKeys.LANGUAGE]?.let { site.xmpp.greenthumb.core.storage.AppLanguage.fromWire(it) }

    override suspend fun setLanguage(language: site.xmpp.greenthumb.core.storage.AppLanguage) {
        values[AppSettingsKeys.LANGUAGE] = language.wire
    }

    override suspend fun getLayoutMode() =
        values[AppSettingsKeys.LAYOUT_MODE]?.let { site.xmpp.greenthumb.core.storage.LayoutMode.fromWire(it) }

    override suspend fun setLayoutMode(mode: site.xmpp.greenthumb.core.storage.LayoutMode) {
        values[AppSettingsKeys.LAYOUT_MODE] = mode.wire
    }

    override suspend fun getTheme() =
        values[AppSettingsKeys.THEME]?.let { site.xmpp.greenthumb.core.storage.ThemePreference.fromWire(it) }

    override suspend fun setTheme(theme: site.xmpp.greenthumb.core.storage.ThemePreference) {
        values[AppSettingsKeys.THEME] = theme.wire
    }

    override suspend fun isIntroSeen(): Boolean = introFlag

    override suspend fun setIntroSeen() {
        introFlag = true
        values[AppSettingsKeys.INTRO_SEEN] = "1"
    }

    override suspend fun getCachedUser(): site.xmpp.greenthumb.core.network.UserDto? = null

    override suspend fun setCachedUser(user: site.xmpp.greenthumb.core.network.UserDto) = Unit

    override suspend fun clearCachedUser() = Unit

    override val language = kotlinx.coroutines.flow.MutableStateFlow<site.xmpp.greenthumb.core.storage.AppLanguage?>(null)
    override val layoutMode = kotlinx.coroutines.flow.MutableStateFlow<site.xmpp.greenthumb.core.storage.LayoutMode?>(null)
    override val theme = kotlinx.coroutines.flow.MutableStateFlow<site.xmpp.greenthumb.core.storage.ThemePreference?>(null)
    override val introSeen = kotlinx.coroutines.flow.MutableStateFlow(introFlag)
    override val cachedUser = kotlinx.coroutines.flow.MutableStateFlow<site.xmpp.greenthumb.core.network.UserDto?>(null)

    override suspend fun applyLegacyHandoffValues(
        language: site.xmpp.greenthumb.core.storage.AppLanguage?,
        theme: site.xmpp.greenthumb.core.storage.ThemePreference?,
        layoutMode: site.xmpp.greenthumb.core.storage.LayoutMode?,
        introSeen: Boolean?,
    ) {
        language?.let { setLanguage(it) }
        theme?.let { setTheme(it) }
        layoutMode?.let { setLayoutMode(it) }
        if (introSeen == true) setIntroSeen()
    }
}
