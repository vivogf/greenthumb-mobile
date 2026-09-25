package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Квадратная кнопка-иконка (переключатель вида, копирование ключа).
 * Сторона — [Spacing.xxl] + [Spacing.md] (38; в RN 34, на шкале нет).
 * Выбранная — рамка primary и фон muted.
 */
@Composable
fun GtIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onPressedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    val shape = RoundedCornerShape(Radii.sm)
    Box(
        modifier = modifier
            .size(Spacing.xxl + Spacing.md)
            .background(if (selected) scheme.surfaceVariant else scheme.surface, shape)
            .borderHairline(if (selected && enabled) scheme.primary else scheme.outline, shape)
            .clickable(
                enabled = enabled,
                onClick = { if (enabled) onClick() },
                role = Role.Button,
                interactionSource = source,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
