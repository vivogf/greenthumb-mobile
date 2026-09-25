package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Фильтр-чип дашборда (`FilterTab` в `app/(tabs)/index.tsx`):
 * выбранный — primary / onPrimary, остальные — muted.
 * Пилюля — `RoundedCornerShape(percent = 50)`, не радиус со шкалы
 * (пилюля в RN — половина стороны, в Radii её нет).
 */
@Composable
fun GtChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val background = if (selected && enabled) scheme.primary else scheme.surfaceVariant
    val foreground = when {
        !enabled -> scheme.onSurfaceVariant
        selected -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    Text(
        text = label,
        modifier = modifier
            .background(background, RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = { if (enabled) onClick() }, role = Role.Button)
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = foreground,
        style = MaterialTheme.typography.labelLarge,
    )
}
