package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * A one-line title with a small trailing action (for example «Ключ восстановления» · «Показать»).
 * The title always gets the full row width. The action sits at the end of the same line, on the title's
 * baseline, only when both fit; otherwise it moves under the title, indented by [wrappedActionIndent].
 */
@Composable
fun GtTitleWithAction(
    title: @Composable () -> Unit,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = Spacing.sm,
    wrappedActionIndent: Dp = 0.dp,
) {
    Layout(
        contents = listOf(title, action),
        modifier = modifier,
    ) { (titleMeasurables, actionMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val titlePlaceable = titleMeasurables.first().measure(loose)
        val actionPlaceable = actionMeasurables.first().measure(loose)
        val gapPx = gap.roundToPx()
        val oneLineWidth = titlePlaceable.width + gapPx + actionPlaceable.width
        val fitsOnOneLine = oneLineWidth <= constraints.maxWidth
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else oneLineWidth
        if (fitsOnOneLine) {
            val titleBaseline = titlePlaceable[FirstBaseline]
            val actionBaseline = actionPlaceable[FirstBaseline]
            val lineHeight = maxOf(titlePlaceable.height, actionPlaceable.height)
            val titleY: Int
            val actionY: Int
            if (titleBaseline != AlignmentLine.Unspecified && actionBaseline != AlignmentLine.Unspecified) {
                val baseline = maxOf(titleBaseline, actionBaseline)
                titleY = baseline - titleBaseline
                actionY = baseline - actionBaseline
            } else {
                titleY = (lineHeight - titlePlaceable.height) / 2
                actionY = (lineHeight - actionPlaceable.height) / 2
            }
            val height = maxOf(titleY + titlePlaceable.height, actionY + actionPlaceable.height)
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                titlePlaceable.placeRelative(0, titleY)
                actionPlaceable.placeRelative(width - actionPlaceable.width, actionY)
            }
        } else {
            val indent = wrappedActionIndent.roundToPx()
                .coerceAtMost((constraints.maxWidth - actionPlaceable.width).coerceAtLeast(0))
            val height = titlePlaceable.height + actionPlaceable.height
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                titlePlaceable.placeRelative(0, 0)
                actionPlaceable.placeRelative(indent, titlePlaceable.height)
            }
        }
    }
}
