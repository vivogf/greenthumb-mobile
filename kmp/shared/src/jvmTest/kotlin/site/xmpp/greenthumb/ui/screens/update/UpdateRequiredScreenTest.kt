package site.xmpp.greenthumb.ui.screens.update

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.Locale
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Блокирующий экран обновления (Stage 12 п.1, VAL-REL-001): заголовок, текст
 * и кнопка стора видны, тап по кнопке зовёт колбэк (OpenUrl решает точка
 * входа); обе локали. Поверхность — desktop-таргет (правило commonMain).
 */
@OptIn(ExperimentalTestApi::class)
class UpdateRequiredScreenTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    @Test
    fun screen_shows_texts_and_store_button_invokes_callback() = runDesktopComposeUiTest {
        var storeClicks = 0
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    UpdateRequiredScreen(onOpenStore = { storeClicks++ })
                }
            }
        }
        onNodeWithText("Update required").assertExists()
        onNodeWithText(
            "This version of GreenThumb is no longer supported. " +
                "Update the app to continue caring for your plants.",
        ).assertExists()
        onNodeWithText("Update in Google Play").performClick()
        assertEquals(1, storeClicks)
    }

    @Test
    fun screen_localizes_to_russian() = runDesktopComposeUiTest {
        setContent {
            AppEnvironment(customAppLocale = "ru") {
                GreenThumbTheme(darkTheme = false) {
                    UpdateRequiredScreen(onOpenStore = {})
                }
            }
        }
        onNodeWithText("Обновите приложение").assertExists()
        onNodeWithText(
            "Эта версия GreenThumb больше не поддерживается. " +
                "Обновите приложение, чтобы продолжить уход за растениями.",
        ).assertExists()
        onNodeWithText("Обновить в Google Play").assertExists()
    }
}
