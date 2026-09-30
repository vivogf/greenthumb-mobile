package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M11 (Stage 11 п.3, VAL-TEST-002): семантика GtSwitch.
 *
 * Дефект M9 (kmp-push-fcm): GtSwitch принимал параметр contentDescription
 * (профиль передаёт `profile.notifications`), но в semantics его не писал —
 * тумблер был безымянным для a11y/семантических деревьев. Фикс M11 пишет
 * параметр в semantics; тест подтверждает:
 * - contentDescription узла доступен (hasContentDescription находит узел);
 * - поведение switch НЕ изменилось: тап зовёт onCheckedChange(!checked),
 *   семантика selected следует за значением (RN-паритет onValueChange).
 */
@OptIn(ExperimentalTestApi::class)
class GtSwitchSemanticsTest {

    @Test
    fun contentDescription_enters_semantics() = runDesktopComposeUiTest {
        var checked by mutableStateOf(false)
        setContent {
            GreenThumbTheme(darkTheme = false) {
                GtSwitch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    contentDescription = "Notifications",
                )
            }
        }

        // Узел по a11y-имени существует — параметр дошёл до semantics.
        val node = onNode(hasContentDescription("Notifications"))
        node.assertExists()
        // Выключенный switch: selected = false.
        assertFalse(
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true,
            "выключенный switch не помечен selected",
        )
    }

    @Test
    fun toggle_behavior_unchanged_selected_follows_value() = runDesktopComposeUiTest {
        var checked by mutableStateOf(false)
        var callbacks = 0
        setContent {
            GreenThumbTheme(darkTheme = false) {
                GtSwitch(
                    checked = checked,
                    onCheckedChange = { value ->
                        callbacks++
                        checked = value
                    },
                    contentDescription = "Notifications",
                )
            }
        }

        val node = onNode(hasContentDescription("Notifications"))

        // Тап: onCheckedChange(true) → checked → selected-семантика вкл.
        node.performClick()
        waitForIdle()
        assertEquals(1, callbacks, "ровно один колбэк на тап")
        assertTrue(checked, "тап включает switch (RN onValueChange)")
        assertTrue(
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true,
            "семантика selected следует за включённым значением",
        )

        // Повторный тап: onCheckedChange(false) → обратно.
        node.performClick()
        waitForIdle()
        assertEquals(2, callbacks)
        assertFalse(checked, "повторный тап выключает switch")
        assertFalse(
            node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true,
            "семантика selected снимается",
        )
        // a11y-имя живёт на обоих состояниях.
        onNode(hasContentDescription("Notifications")).assertExists()
    }
}
