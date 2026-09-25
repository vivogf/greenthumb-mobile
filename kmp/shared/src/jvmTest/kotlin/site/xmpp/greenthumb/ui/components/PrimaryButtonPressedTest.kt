package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * VAL-DS-003: pressed-ветка PrimaryButton на реальных pointer-событиях.
 * Решение пользователя 2026-09-25: MCP click — синтетический action-вызов и
 * удержание указателя не создаёт, поэтому pressed проверяется узким Compose
 * UI-тестом на desktop-таргете: pointer down → пока указатель удерживается
 * stateDescription = "pressed" → pointer up → stateDescription = "idle".
 * Полный набор экранных UI-тестов — M11 (kmp-compose-ui-tests), не здесь.
 */
@OptIn(ExperimentalTestApi::class)
class PrimaryButtonPressedTest {

    @Test
    fun pointer_down_presses_while_held_then_up_returns_idle() = runDesktopComposeUiTest {
        var clicks = 0
        setContent {
            GreenThumbTheme(darkTheme = false) {
                PrimaryButton(text = "Press me", onClick = { clicks++ })
            }
        }

        val button = onNodeWithText("Press me")

        fun stateDescription(): String? =
            button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

        // До касания — idle.
        assertEquals("idle", stateDescription())

        // Указатель опущен на кнопку и УДЕРЖИВАЕТСЯ: pressed виден до отпускания.
        button.performTouchInput { down(center) }
        waitForIdle()
        assertEquals("pressed", stateDescription())

        // Отпускание — обратно в idle; click от реального конвейера событий ровно один.
        button.performTouchInput { up() }
        waitForIdle()
        assertEquals("idle", stateDescription())
        assertEquals(1, clicks)
    }
}
