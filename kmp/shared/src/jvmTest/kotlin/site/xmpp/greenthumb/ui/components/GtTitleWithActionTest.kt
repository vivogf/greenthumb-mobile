package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Profile «Ключ восстановления» + «Показать»: the title keeps one line; the action shares the line only when it
 * fits and otherwise moves under the title (Samsung 384 dp at font scale 1.15 and 1.3).
 */
@OptIn(ExperimentalTestApi::class)
class GtTitleWithActionTest {

    private fun ComposeUiTest.bounds(tag: String): DpRect =
        onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.layout(tag: String): TextLayoutResult {
        val node = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun run(width: Int, check: ComposeUiTest.(scale: Float) -> Unit) =
        runDesktopComposeUiTest(width = width, height = 400) {
            var fontScale by mutableFloatStateOf(1f)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    GreenThumbTheme(darkTheme = true) {
                        GtTitleWithAction(
                            title = {
                                Row {
                                    Text(
                                        text = "Ключ восстановления",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("title"),
                                    )
                                }
                            },
                            action = {
                                Text(
                                    text = "Показать",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("action"),
                                )
                            },
                            wrappedActionIndent = 0.dp,
                        )
                    }
                }
            }
            for (scale in listOf(1.15f, 1.3f)) {
                fontScale = scale
                waitForIdle()
                check(scale)
            }
        }

    @Test
    fun actionSharesTheLineWhenItFits() = run(width = 400) { scale ->
        val title = bounds("title")
        val action = bounds("action")
        assertTrue(layout("title").lineCount == 1, "scale $scale: title wraps")
        assertTrue(action.top < title.bottom && action.left >= title.right, "scale $scale: $action not beside $title")
        assertTrue(action.right > 390.dp, "scale $scale: action $action is not at the end of the row")
    }

    @Test
    fun actionMovesUnderTheTitleWhenTheLineIsFull() = run(width = 200) { scale ->
        val title = bounds("title")
        val action = bounds("action")
        assertTrue(layout("title").lineCount == 1, "scale $scale: title wraps")
        assertTrue(action.top >= title.bottom, "scale $scale: action $action is not under the title $title")
        assertTrue(action.left < 1.dp, "scale $scale: action $action is not at the start")
    }
}
