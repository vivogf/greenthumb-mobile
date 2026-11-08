package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Morning notes item 29: the calendar of the date picker follows the app language, not the phone language.
 * The phone language is the process default locale here; the app language reaches the field as [languageTag].
 */
@OptIn(ExperimentalTestApi::class)
class DatePickerCalendarLocaleTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun russianApp_onEnglishPhone_showsRussianMonths() =
        calendarCase(phone = Locale.US, appLanguage = "ru", expected = "январ", foreign = "January")

    @Test
    fun englishApp_onRussianPhone_showsEnglishMonths() =
        calendarCase(phone = Locale.forLanguageTag("ru-RU"), appLanguage = "en", expected = "January", foreign = "январ")

    @Test
    fun appLanguageWithRegion_isUnderstood() =
        calendarCase(phone = Locale.US, appLanguage = "ru_RU", expected = "январ", foreign = "January")

    private fun calendarCase(phone: Locale, appLanguage: String, expected: String, foreign: String) =
        runDesktopComposeUiTest {
            Locale.setDefault(phone)
            setContent {
                GreenThumbTheme(darkTheme = false) {
                    GtDatePickerField(
                        value = "2026-01-09",
                        onValueChange = {},
                        placeholder = "",
                        confirmLabel = "OK",
                        dismissLabel = "Cancel",
                        languageTag = appLanguage,
                    )
                }
            }
            onNode(hasClickAction() and hasContentDescription(formatPickerDate("2026-01-09", appLanguage), substring = true))
                .performClick()
            waitUntilAtLeastOneExists(hasText(expected, substring = true, ignoreCase = true), TIMEOUT)
            val texts = onAllNodes(hasText(foreign, substring = true, ignoreCase = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue(texts.isEmpty(), "no \"$foreign\" in the calendar of a \"$appLanguage\" app")
            val described = onAllNodes(hasContentDescription(foreign, substring = true, ignoreCase = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString() }
            assertTrue(described.isEmpty(), "no \"$foreign\" in calendar descriptions: ${described.take(5)}")
        }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
