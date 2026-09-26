package site.xmpp.greenthumb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.ui.nav.GtBottomTabs
import site.xmpp.greenthumb.ui.nav.GtTab
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.plant_daysLeft
import site.xmpp.greenthumb.ui.res.profile_title
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Stage 6 п.5 (VAL-I18N-004): рантайм-переключение языка — смена
 * `customAppLocale` в [AppEnvironment] (то, что делает выбор в AppSettings:
 * `settings.language` Flow → recompose → key()) перекомпоновывает ВСЕ подписи
 * одной и той же композиции немедленно, включая плюральные формы, без
 * рестарта. Отрицательная проверка: старые строки исчезают (перекомпоновка,
 * а не наложение).
 *
 * Механизм (LocalAppLocale expect/actual) приехал из Stage 6 п.4 — здесь
 * верифицируется именно переключение на живом дереве; полноценный красный
 * прогон невозможен (тест поверх готового механизма), фиксируется наблюде­ние.
 * Характеристика wire-значений для подписки пушей (M9) — отдельным кейсом.
 */
@OptIn(ExperimentalTestApi::class)
class LocaleRuntimeSwitchTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        // jvm-actual LocalAppLocale мутирует Locale.setDefault — глобальное
        // состояние процесса; возвращаем исходное, чтобы не течь в другие тесты.
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    @Test
    fun runtime_locale_switch_recomposes_labels_and_plurals_without_restart() = try {
        if (originalLocale == null) originalLocale = Locale.getDefault()
        runDesktopComposeUiTest {
            // Состояние локали приложения — то же, что App() берёт из
            // settings.language: Flip = действие переключателя языка.
            var locale by mutableStateOf("ru")
            setContent {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            GtBottomTabs(selected = GtTab.Plants, onSelect = {})
                            Text(text = stringResource(Res.string.profile_title))
                            Text(text = pluralStringResource(Res.plurals.plant_daysLeft, 1, 1))
                            Text(text = pluralStringResource(Res.plurals.plant_daysLeft, 3, 3))
                        }
                    }
                }
            }

            // Старт на ru: подписи вкладок, заголовок и обе плюральные формы.
            onNodeWithText("Растения").assertExists()
            onNodeWithText("Профиль").assertExists()
            onNodeWithText("Мой профиль").assertExists()
            onNodeWithText("Остался 1 день").assertExists()
            onNodeWithText("Осталось 3 дня").assertExists()

            // Переключение языка той же живой композиции (без setContent).
            locale = "en"
            waitForIdle()

            // Все подписи, включая плюральные формы, перечитались немедленно.
            onNodeWithText("Plants").assertExists()
            onNodeWithText("Profile").assertExists()
            onNodeWithText("My Profile").assertExists()
            onNodeWithText("1 day left").assertExists()
            onNodeWithText("3 days left").assertExists()
            // Старые строки исчезли (перекомпоновка, а не наложение): локаль
            // входит в ключ stringResource-состояния, старые строки уходят.
            onNodeWithText("Растения").assertDoesNotExist()
            onNodeWithText("Мой профиль").assertDoesNotExist()
            onNodeWithText("Остался 1 день").assertDoesNotExist()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun null_locale_returns_to_system_resources() = try {
        if (originalLocale == null) originalLocale = Locale.getDefault()
        // «Системная» локаль детерминирована: JVM default = en (иначе тест зависел
        // бы от локали машины).
        Locale.setDefault(Locale.ENGLISH)
        runDesktopComposeUiTest {
            var locale by mutableStateOf<String?>("ru")
            setContent {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = false) {
                        Text(text = stringResource(Res.string.profile_title))
                    }
                }
            }
            onNodeWithText("Мой профиль").assertExists()

            // null = «системная» (дефолт RN): CompositionLocal меняется, ресурсы
            // отдают системную локаль (en — фолбэк values/).
            locale = null
            waitForIdle()

            onNodeWithText("My Profile").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun language_wire_values_match_push_subscription_contract() {
        // M9: POST /api/push/subscribe-fcm {language} — значение берётся из
        // AppSettings (AppLanguage.wire); контракт провода — строго 'ru'/'en'.
        assertEquals("ru", AppLanguage.Ru.wire)
        assertEquals("en", AppLanguage.En.wire)
        assertEquals(AppLanguage.Ru, AppLanguage.fromWire("ru"))
        assertEquals(AppLanguage.En, AppLanguage.fromWire("en"))
        // Чужие языки не проходят в провод (RN-фолбэк ресурсов en решает UI).
        assertNull(AppLanguage.fromWire("fr"))
        assertNull(AppLanguage.fromWire(null))
    }
}
