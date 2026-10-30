package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import site.xmpp.greenthumb.ui.theme.GtTypography
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * A card whose header row opens and closes its [content] (Add Plant «Ещё параметры»).
 * The whole header is the touch target (at least [Sizes.touch] high); [hint] is shown
 * only while the card is closed. [headerTag] tags the header row.
 */
@Composable
fun GtExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    headerTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface, shape)
            .borderHairline(greenThumbExtendedColors().cardBorder, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (headerTag == null) Modifier else Modifier.testTag(headerTag))
                .clickable(role = Role.Button, onClick = onToggle)
                .heightIn(min = Sizes.touch)
                .padding(horizontal = Sizes.cardPadding, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(text = title, style = GtTypography.sectionTitle, color = scheme.onSurface)
                if (!expanded && hint != null) {
                    Text(text = hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            GtChevronMark(
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(Spacing.lg),
                direction = if (expanded) GtChevronDirection.Up else GtChevronDirection.Down,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Sizes.cardPadding, end = Sizes.cardPadding, bottom = Sizes.cardPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                content = content,
            )
        }
    }
}
