package site.xmpp.greenthumb.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.ui.nav.GtBottomTabs
import site.xmpp.greenthumb.ui.nav.GtTab
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.dashboard_lastSynced
import site.xmpp.greenthumb.ui.res.plant_daysLeft
import site.xmpp.greenthumb.ui.res.plant_overdue
import site.xmpp.greenthumb.ui.res.privacy_bugBody
import site.xmpp.greenthumb.ui.res.privacy_sectionLabel
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Stage 6 п.4 (VAL-I18N-001/002/003, механизм доступа к строкам из commonMain):
 * Compose Resources strings.xml (en) + values-ru, рантайм-локаль через
 * [AppEnvironment]/LocalAppLocale (рецепт CMP «Manage local resource environment»).
 *
 * Числа 1/3/5/21/22/25 — эталонные для ru-плюрализма (план Stage 6 п.4): 21 → «день»,
 * 22 → «дня», 25 → «дней»; en: 1 → singular, 3/5/21 → plural. Множества ключей
 * en/ru против i18n/locales проверяет scripts/i18n_check.py (в составе check.sh).
 */
@OptIn(ExperimentalTestApi::class)
class I18nResourcesTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        // jvm-actual LocalAppLocale мутирует Locale.setDefault — глобальное
        // состояние процесса; возвращаем исходное, чтобы не течь в другие тесты.
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private fun rememberLocale() {
        if (originalLocale == null) originalLocale = Locale.getDefault()
    }

    @Test
    fun ru_plural_forms_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 3, 5, 21, 22, 25).forEach { n ->
                                Text(pluralStringResource(Res.plurals.plant_daysLeft, n, n))
                                Text(pluralStringResource(Res.plurals.plant_overdue, n, n))
                            }
                        }
                    }
                }
            }

            // daysLeft: 1/21 → «день», 3/22 → «дня», 5/25 → «дней».
            onNodeWithText("Остался 1 день").assertExists()
            onNodeWithText("Осталось 3 дня").assertExists()
            onNodeWithText("Осталось 5 дней").assertExists()
            onNodeWithText("Остался 21 день").assertExists()
            onNodeWithText("Осталось 22 дня").assertExists()
            onNodeWithText("Осталось 25 дней").assertExists()
            // overdue: те же формы.
            onNodeWithText("Просрочен 1 день").assertExists()
            onNodeWithText("Просрочено 3 дня").assertExists()
            onNodeWithText("Просрочено 5 дней").assertExists()
            onNodeWithText("Просрочен 21 день").assertExists()
            onNodeWithText("Просрочено 22 дня").assertExists()
            onNodeWithText("Просрочено 25 дней").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun en_plural_forms_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 3, 5, 21).forEach { n ->
                                Text(pluralStringResource(Res.plurals.plant_daysLeft, n, n))
                                Text(pluralStringResource(Res.plurals.plant_overdue, n, n))
                            }
                        }
                    }
                }
            }

            // en: 1 → singular, 3/5/21 → plural.
            onNodeWithText("1 day left").assertExists()
            onNodeWithText("3 days left").assertExists()
            onNodeWithText("5 days left").assertExists()
            onNodeWithText("21 days left").assertExists()
            onNodeWithText("1 day overdue").assertExists()
            onNodeWithText("3 days overdue").assertExists()
            onNodeWithText("5 days overdue").assertExists()
            onNodeWithText("21 days overdue").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun tab_labels_come_from_resources_in_both_locales() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            // en: литералов «Plants»/«Profile» больше нет в коде — только ресурсы.
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        GtBottomTabs(selected = GtTab.Plants, onSelect = {})
                    }
                }
            }
            onNodeWithText("Plants").assertExists()
            onNodeWithText("Profile").assertExists()

            // ru: те же вкладки — «Растения»/«Профиль».
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        GtBottomTabs(selected = GtTab.Profile, onSelect = {})
                    }
                }
            }
            onNodeWithText("Растения").assertExists()
            onNodeWithText("Профиль").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun string_access_mechanism_escapes_and_interpolates_exactly() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            var seenLocale: String? = null
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    seenLocale = LocalAppLocale.current
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            // Апостроф (Android-экранирование \') и амперсанд (&amp;)
                            // должны отрендериться ровно как в RN-локалях.
                            Text(stringResource(Res.string.privacy_bugBody))
                            Text(stringResource(Res.string.privacy_sectionLabel))
                            // {{time}} → %1$s.
                            Text(stringResource(Res.string.dashboard_lastSynced, "12:05"))
                        }
                    }
                }
            }

            onNodeWithText("We'd love to hear about it:").assertTextEquals("We'd love to hear about it:")
            onNodeWithText("About & Privacy").assertTextEquals("About & Privacy")
            onNodeWithText("Updated at 12:05").assertTextEquals("Updated at 12:05")
            assertEquals("en", seenLocale)
        }
    } finally {
        restoreLocale()
    }
}
