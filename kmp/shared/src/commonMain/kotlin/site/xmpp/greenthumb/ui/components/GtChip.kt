package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Chip of the v2 mockup (`.chip` / `.chip.on`): a pill on `card2` with the card border; the selected
 * chip is a tinted [chipSelected][site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors.chipSelected]
 * fill with the accent border and accent text, never a solid primary fill (white text on the accent
 * fill is below 4.5:1 in Light «Лес»).
 * [leading] gets the label colour and draws a 20 dp mark before the label.
 */
@Composable
fun GtChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable (Color) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val on = selected && enabled
    val shape = RoundedCornerShape(percent = 50)
    val foreground = when {
        !enabled -> scheme.onSurfaceVariant
        on -> extended.accentText
        else -> scheme.onSurface
    }
    Row(
        modifier = modifier
            .background(if (on) extended.chipSelected else scheme.surfaceVariant, shape)
            .borderHairline(if (on) scheme.secondary else extended.cardBorder, shape)
            .clickable(enabled = enabled, onClick = { if (enabled) onClick() }, role = Role.Button)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (leading != null) {
            Box(modifier = Modifier.size(Spacing.xl), contentAlignment = Alignment.Center) { leading(foreground) }
        }
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (on) FontWeight.SemiBold else null,
        )
    }
}
