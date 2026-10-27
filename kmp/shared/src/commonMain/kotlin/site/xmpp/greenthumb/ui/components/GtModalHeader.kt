package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import site.xmpp.greenthumb.ui.theme.Spacing

/** A text action in a [GtModalHeader] ("Cancel", "Save"). */
internal class GtModalHeaderAction(
    val text: String,
    val color: Color,
    val onClick: () -> Unit,
)

internal const val GtModalHeaderTitleTag = "modal_header_title"
internal const val GtModalHeaderLeadingTag = "modal_header_leading"
internal const val GtModalHeaderTrailingTag = "modal_header_trailing"

private val MinTouchTarget = 48.dp

/**
 * Header row of a [GtModal]: optional leading action, title, optional trailing action.
 * Actions keep their full label on one line; the title takes the space that is left and
 * is ellipsized, so large system fonts never wrap an action or push it into the title.
 * The title is centred between two actions and start-aligned next to a single one.
 */
@Composable
internal fun GtModalHeader(
    title: String,
    leading: GtModalHeaderAction? = null,
    trailing: GtModalHeaderAction? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Action labels carry Spacing.xs of their own padding, so their text still lines up at Spacing.xl.
            .padding(horizontal = Spacing.xl - Spacing.xs, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        leading?.let { HeaderAction(it, GtModalHeaderLeadingTag) }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (leading != null) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .weight(1f)
                .then(if (leading == null) Modifier.padding(start = Spacing.xs) else Modifier)
                .testTag(GtModalHeaderTitleTag),
        )
        trailing?.let { HeaderAction(it, GtModalHeaderTrailingTag) }
    }
}

@Composable
private fun HeaderAction(action: GtModalHeaderAction, tag: String) {
    Box(
        modifier = Modifier
            .testTag(tag)
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = action.onClick,
            )
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = action.text,
            style = MaterialTheme.typography.bodyLarge,
            color = action.color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
