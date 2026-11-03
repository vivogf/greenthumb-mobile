package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow

private val NameScales = listOf(1f, 0.95f, 0.9f, 0.85f, 0.8f)

/** Keep words intact, shrinking only when a word is wider than the grid's name slot. */
@Composable
internal fun GridPlantName(name: String, modifier: Modifier = Modifier) {
    val baseStyle = MaterialTheme.typography.labelSmall.copy(hyphens = Hyphens.None)
    val measurer = rememberTextMeasurer()
    val words = remember(name) { name.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val styles = NameScales.map { scale ->
            baseStyle.copy(fontSize = baseStyle.fontSize * scale, lineHeight = baseStyle.lineHeight * scale)
        }
        val fittingStyle = styles.firstOrNull { style ->
            words.all { word ->
                measurer.measure(word, style = style, softWrap = false).size.width <= widthPx
            }
        }
        // If a word still cannot fit at 80%, never let the text engine split it over two lines.
        Text(
            text = name,
            style = fittingStyle ?: styles.last(),
            color = Color.White,
            maxLines = if (fittingStyle != null) 2 else 1,
            softWrap = fittingStyle != null,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.fillMaxWidth(),
        )
    }
}
