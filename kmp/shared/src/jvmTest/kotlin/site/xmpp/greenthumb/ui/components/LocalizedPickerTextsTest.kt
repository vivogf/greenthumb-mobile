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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.readPickedImage
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/** VAL-CROSS-018 / VAL-APP-019: the date field label and the unreadable-photo error follow the app language. */
@OptIn(ExperimentalTestApi::class)
class LocalizedPickerTextsTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun dateField_labelAndDialogTitle_areRussianInTheRuUi() = runDesktopComposeUiTest {
        val today = pickerToday()
        val shown = formatPickerDate("2026-01-09", "ru")
        setContent {
            AppEnvironment(customAppLocale = "ru") {
                GreenThumbTheme(darkTheme = false) {
                    GtDatePickerField(
                        value = "2026-01-09",
                        onValueChange = {},
                        placeholder = "",
                        confirmLabel = "Готово",
                        dismissLabel = "Отмена",
                        languageTag = "ru",
                    )
                }
            }
        }
        waitUntilAtLeastOneExists(hasContentDescription("Поле даты $shown"), TIMEOUT)
        onNode(hasContentDescription("Date field", substring = true)).assertDoesNotExist()

        onNode(hasClickAction() and hasContentDescription("Поле даты $shown")).performClick()
        waitUntilAtLeastOneExists(hasText("Не позднее ${formatPickerDate(today.toString(), "ru")}"), TIMEOUT)
        onNode(hasText("Maximum", substring = true)).assertDoesNotExist()
    }

    @Test
    fun unreadablePhoto_errorIsRussianInTheRuUi() = runDesktopComposeUiTest {
        val controller = PhotoPickerController(
            pick = { readPickedImage { ByteArray(0) } },
            crop = { bytes, _ -> bytes },
            onPicked = {},
        )
        lateinit var scope: CoroutineScope
        setContent {
            scope = rememberCoroutineScope()
            AppEnvironment(customAppLocale = "ru") {
                GreenThumbTheme(darkTheme = false) {
                    PhotoPickerHost(controller)
                }
            }
        }
        runOnIdle { controller.launch(scope, PickSource.Gallery) }
        waitUntilAtLeastOneExists(hasText("Не удалось открыть это фото. Выберите другое фото."), TIMEOUT)
        onNode(hasText("Ошибка")).assertExists()
        onNode(hasText("unsupported", substring = true)).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
