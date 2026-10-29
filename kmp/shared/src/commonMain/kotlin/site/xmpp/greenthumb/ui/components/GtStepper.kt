package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/** [value] moved by [delta] and kept inside [range]. */
fun stepperValue(value: Int, delta: Int, range: IntRange): Int = (value + delta).coerceIn(range)

/**
 * − number unit + (M10 D§1 item 7a): 48 dp buttons on both sides, the number in bold with its unit word
 * next to it. A button at the end of [range] is disabled, so the value never leaves it. No text input.
 * With [testTagPrefix] the parts get the tags `<prefix>_dec`, `_inc`, `_value` and `_unit`.
 */
@Composable
fun GtStepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    unit: String,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    testTagPrefix: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    fun tag(part: String): Modifier = if (testTagPrefix == null) Modifier else Modifier.testTag(testTagPrefix + part)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        StepperButton(
            symbol = "−",
            label = decreaseLabel,
            enabled = value > range.first,
            onClick = { onValueChange(stepperValue(value, -1, range)) },
            modifier = tag(STEPPER_DEC),
        )
        Row(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
                modifier = tag(STEPPER_VALUE),
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xxs).then(tag(STEPPER_UNIT)),
            )
        }
        StepperButton(
            symbol = "+",
            label = increaseLabel,
            enabled = value < range.last,
            onClick = { onValueChange(stepperValue(value, +1, range)) },
            modifier = tag(STEPPER_INC),
        )
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.field)
    Box(
        modifier = modifier
            .size(Sizes.touch)
            .background(scheme.surfaceVariant, shape)
            .borderHairline(greenThumbExtendedColors().cardBorder, shape)
            // One node for TalkBack and uiautomator: label, role, click and the disabled state together.
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { onClick(); true } else disabled()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (enabled) scheme.secondary else scheme.onSurfaceVariant.copy(alpha = DisabledAlpha),
        )
    }
}

const val STEPPER_DEC = "_dec"
const val STEPPER_INC = "_inc"
const val STEPPER_VALUE = "_value"
const val STEPPER_UNIT = "_unit"

private const val DisabledAlpha = 0.38f
