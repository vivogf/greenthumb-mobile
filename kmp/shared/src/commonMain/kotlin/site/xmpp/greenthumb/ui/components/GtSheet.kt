package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

/** Test tags of [GtSheetTitle] / [GtSheetRow] (jvm tests and device dumps). */
object GtSheetTags {
    const val TITLE = "gt_sheet_title"
    const val ROW = "gt_sheet_row"
    const val ICON_SLOT = "gt_sheet_row_icon"
    const val LABEL = "gt_sheet_row_label"
}

/**
 * [GtSheetRowRole.Cancel] closes the sheet: neutral colour, no icon, same alignment as the other rows.
 * [GtSheetRowRole.Destructive] (for example «Удалить фото») draws label and icon in the error colour.
 */
enum class GtSheetRowRole { Normal, Destructive, Cancel }

/** Label colour of a [GtSheetRow]: Cancel is neutral (D§1 item 8), never the error colour. */
fun gtSheetRowLabelColor(role: GtSheetRowRole, scheme: ColorScheme): Color = when (role) {
    GtSheetRowRole.Normal -> scheme.onSurface
    GtSheetRowRole.Destructive -> scheme.error
    GtSheetRowRole.Cancel -> scheme.onSurfaceVariant
}

/** Centred title of a bottom-sheet style [GtModal] (`titleMedium` 18/24). */
@Composable
fun GtSheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .testTag(GtSheetTags.TITLE)
            .padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.xxl, bottom = Spacing.xs),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

/**
 * One row of a sheet: at least [Sizes.sheetRow] high, a fixed [Sizes.iconSlot] icon slot that stays
 * in place when [icon] is null, so every label in a sheet starts at the same x.
 * [icon] gets the tint; the slot hands it its full size.
 */
@Composable
fun GtSheetRow(
    label: String,
    icon: (@Composable (Color) -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: GtSheetRowRole = GtSheetRowRole.Normal,
    supporting: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.sheetRow)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(GtSheetTags.ROW)
            .padding(horizontal = Spacing.xxl, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            modifier = Modifier.size(Sizes.iconSlot).testTag(GtSheetTags.ICON_SLOT),
            contentAlignment = Alignment.Center,
            propagateMinConstraints = true,
        ) {
            when (role) {
                GtSheetRowRole.Normal -> icon?.invoke(scheme.secondary)
                GtSheetRowRole.Destructive -> icon?.invoke(scheme.error)
                GtSheetRowRole.Cancel -> Unit
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                modifier = Modifier.testTag(GtSheetTags.LABEL),
                style = MaterialTheme.typography.bodyLarge,
                color = gtSheetRowLabelColor(role, scheme),
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
