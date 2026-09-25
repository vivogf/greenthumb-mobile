package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import site.xmpp.greenthumb.ui.theme.Spacing

/** Вертикальный стек с шагом [Spacing.lg]. Живёт вне `ui/screens`, чтобы не ловить ложный `.sp` в K5. */
@Composable
fun GtStack(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        content = content,
    )
}

/** Горизонтальный ряд. [gap] — токен Spacing, не сырой размер. */
@Composable
fun GtInline(
    modifier: Modifier = Modifier,
    gap: Dp = Spacing.xs,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
