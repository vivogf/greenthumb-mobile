package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.PushOutcome
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.platform.effectiveAppLanguage
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.season.SeasonHarness
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * VAL-APP-014: with nothing saved under the language key the Profile language row and picker show
 * the language the UI is actually in (the system locale: ru -> Russian, anything else -> English),
 * and opening and closing the picker saves nothing.
 *
 * ProfileScreen is rendered without AppEnvironment: AppEnvironment(null) on desktop re-applies the
 * locale captured by the first provides() in this JVM, which would undo Locale.setDefault here.
 */
@OptIn(ExperimentalTestApi::class)
class ProfileLanguageDisplayTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private class SilentPush : PushTokens() {
        override suspend fun requestSubscribe(language: String): PushOutcome = PushOutcome.Denied
        override suspend fun subscriptionStatus(): Boolean = false
        override suspend fun unsubscribe() {}
        override suspend fun sendLocalTestNotification() {}
    }

    private fun ComposeUiTest.selectedTexts(): List<String> =
        onAllNodes(isSelected()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text) }
            .flatten()
            .map { it.text }

    private fun checkDefaultLanguage(
        systemLocale: Locale,
        rowLabel: String,
        expectedValue: String,
        otherValue: String,
        pickerTitle: String,
    ) {
        Locale.setDefault(systemLocale)
        SeasonHarness().use { harness ->
            runBlocking { harness.signedInWithKey() }
            runDesktopComposeUiTest {
                setContent {
                    GreenThumbTheme(darkTheme = false) {
                        ProfileScreen(
                            session = harness.session,
                            settings = harness.settings,
                            push = SilentPush(),
                            pushLanguage = { "en" },
                        )
                    }
                }
                val row = hasClickAction() and hasText(rowLabel) and hasText(expectedValue)
                waitUntilAtLeastOneExists(row, TIMEOUT)

                onAllNodes(row)[0].performSemanticsAction(SemanticsActions.OnClick)
                waitUntilAtLeastOneExists(hasText(pickerTitle), TIMEOUT)
                val selected = selectedTexts()
                assertEquals(true, expectedValue in selected, "$systemLocale: $expectedValue selected, got $selected")
                assertEquals(false, otherValue in selected, "$systemLocale: $otherValue not selected, got $selected")

                // Tap on the scrim (the outermost clickable around the picker) closes it without a choice.
                onAllNodes(hasClickAction() and hasAnyDescendant(hasText(pickerTitle)))[0]
                    .performSemanticsAction(SemanticsActions.OnClick)
                waitUntilDoesNotExist(hasText(pickerTitle), TIMEOUT)
                waitForIdle()
                assertNull(runBlocking { harness.settings.getLanguage() }, "$systemLocale: nothing saved")
                onAllNodes(row)[0].assertExists()
            }
        }
    }

    @Test
    fun russianSystemLocaleShowsRussian() = checkDefaultLanguage(
        Locale("ru"),
        rowLabel = "Язык",
        expectedValue = "Русский",
        otherValue = "English",
        pickerTitle = "Выберите язык",
    )

    @Test
    fun englishSystemLocaleShowsEnglish() = checkDefaultLanguage(
        Locale.ENGLISH,
        rowLabel = "Language",
        expectedValue = "English",
        otherValue = "Русский",
        pickerTitle = "Choose Language",
    )

    @Test
    fun germanSystemLocaleShowsEnglish() = checkDefaultLanguage(
        Locale.GERMAN,
        rowLabel = "Language",
        expectedValue = "English",
        otherValue = "Русский",
        pickerTitle = "Choose Language",
    )

    @Test
    fun savedLanguageWinsOverSystemLanguage() {
        assertEquals(AppLanguage.En, effectiveAppLanguage(AppLanguage.En, "ru"))
        assertEquals(AppLanguage.Ru, effectiveAppLanguage(AppLanguage.Ru, "de"))
    }

    @Test
    fun nothingSavedFollowsSystemLanguage() {
        assertEquals(AppLanguage.Ru, effectiveAppLanguage(null, "ru"))
        assertEquals(AppLanguage.Ru, effectiveAppLanguage(null, "ru-RU"))
        assertEquals(AppLanguage.Ru, effectiveAppLanguage(null, "ru_RU"))
        assertEquals(AppLanguage.En, effectiveAppLanguage(null, "en"))
        assertEquals(AppLanguage.En, effectiveAppLanguage(null, "de"))
        assertEquals(AppLanguage.En, effectiveAppLanguage(null, ""))
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
