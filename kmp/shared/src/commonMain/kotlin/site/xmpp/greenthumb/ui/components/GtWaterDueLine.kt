package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.data.WaterDue
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.plantDetails_waterIn
import site.xmpp.greenthumb.ui.res.plantDetails_waterOverdue
import site.xmpp.greenthumb.ui.res.plantDetails_waterToday
import site.xmpp.greenthumb.ui.theme.GtType
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/** «Полить через 11 дней» / «Полить сегодня» / «Пора полить: 3 дня назад». */
@Composable
fun waterDueText(due: WaterDue): String = when (due) {
    is WaterDue.InDays -> pluralStringResource(Res.plurals.plantDetails_waterIn, due.days, due.days)
    WaterDue.Today -> stringResource(Res.string.plantDetails_waterToday)
    is WaterDue.Overdue -> pluralStringResource(Res.plurals.plantDetails_waterOverdue, due.days, due.days)
}

/**
 * One-line text that steps its size down from [style] to [GtType.waterDueMinSize] until it fits the width.
 * «Пора полить: 21 день назад» at font scale 1.3 on a 360 dp phone is wider than the card at full size; on a
 * narrower one the ellipsis stays as the last resort. Drawing waits until the size settles, so no frame shows the
 * clipped text.
 */
@Composable
private fun FittedOneLineText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val full = style.fontSize.value
        val min = GtType.waterDueMinSize.value
        val step = GtType.waterDueStep.value
        var size by remember(text, constraints.maxWidth, full) { mutableFloatStateOf(full) }
        var settled by remember(text, constraints.maxWidth, full) { mutableStateOf(false) }
        Text(
            text = text,
            style = if (size == full) style else style.copy(fontSize = size.sp),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if ((layout.isLineEllipsized(0) || layout.hasVisualOverflow) && size > min) {
                    size = (size - step).coerceAtLeast(min)
                } else {
                    settled = true
                }
            },
            modifier = modifier.drawWithContent { if (settled) drawContent() },
        )
    }
}

/**
 * Watering status of plant detail, variant B (D§1 item 4): one large status line that never wraps, a progress
 * bar to the next watering, then the last watering and the optional winter label. The words carry the status,
 * so the colour is never the only signal.
 */
@Composable
fun GtWaterDueLine(
    due: WaterDue,
    progress: Float,
    lastWateredText: String,
    modifier: Modifier = Modifier,
    winterText: String? = null,
    textTag: String = "plantDetail_waterDue",
) {
    val extended = greenThumbExtendedColors()
    val barColor = when (due) {
        is WaterDue.InDays -> MaterialTheme.colorScheme.secondary
        WaterDue.Today -> extended.amber
        is WaterDue.Overdue -> extended.causeHigh
    }
    val textColor = when (due) {
        is WaterDue.InDays -> extended.accentText
        WaterDue.Today -> extended.amber
        is WaterDue.Overdue -> extended.causeHigh
    }
    val fraction = progress.coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        GtWaterDropMark(
            tint = barColor,
            modifier = Modifier.size(Sizes.iconSlot),
        )
        Column(modifier = Modifier.weight(1f)) {
            FittedOneLineText(
                text = waterDueText(due),
                style = GtType.waterDue,
                color = textColor,
                modifier = Modifier.testTag(textTag),
            )
            Box(
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .fillMaxWidth()
                    .height(Sizes.progressBar)
                    .background(MaterialTheme.colorScheme.surfaceVariant, Radii.pill)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
                    .testTag("plantDetail_waterProgress"),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(barColor, Radii.pill),
                    )
                }
            }
            Text(
                text = lastWateredText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs).testTag("plantDetail_lastWatered"),
            )
            if (winterText != null) {
                Text(
                    text = winterText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = Spacing.xxs).testTag("detail_winterMode"),
                )
            }
        }
    }
}
