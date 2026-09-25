package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Карточка: surface + cardBorder, радиус [Radii.lg] (RN-карточки 12).
 * Внутренний отступ [Spacing.lg] — как `padding: 16` у карточек списка.
 */
@Composable
fun GtCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, shape)
            .borderHairline(greenThumbExtendedColors().cardBorder, shape)
            .padding(Spacing.lg),
        content = content,
    )
}
