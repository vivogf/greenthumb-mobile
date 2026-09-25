package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Кнопки экранов. Форма — [Radii.md], вертикальный паддинг — [Spacing.md]
 * (RN `primaryButtonStyle` / `outlineButtonStyle` в `login.tsx`).
 * Цвета только из ColorScheme. Pressed отдаётся наружу и в semantics,
 * disabled — через `enabled` (серый surfaceVariant, не primary).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPressedChange: (Boolean) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    Button(
        onClick = { if (enabled) onClick() },
        modifier = modifier.pressSemantics(pressed),
        enabled = enabled,
        shape = RoundedCornerShape(Radii.md),
        interactionSource = source,
        contentPadding = buttonPadding(),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primary,
            contentColor = scheme.onPrimary,
            disabledContainerColor = scheme.surfaceVariant,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPressedChange: (Boolean) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    OutlinedButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.pressSemantics(pressed),
        enabled = enabled,
        shape = RoundedCornerShape(Radii.md),
        interactionSource = source,
        contentPadding = buttonPadding(),
        border = BorderStroke(
            width = Spacing.xxs / 4,
            color = if (enabled) scheme.outline else scheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurface,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Опасное действие экрана: контур и текст error, не заливка.
 * Так выглядит удаление в `plant/[id].tsx` (рамка destructive, не solid).
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPressedChange: (Boolean) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) { onPressedChange(pressed) }
    OutlinedButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.pressSemantics(pressed),
        enabled = enabled,
        shape = RoundedCornerShape(Radii.md),
        interactionSource = source,
        contentPadding = buttonPadding(),
        border = BorderStroke(
            width = Spacing.xxs / 4,
            color = if (enabled) scheme.error else scheme.outlineVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.error,
            disabledContentColor = scheme.onSurfaceVariant,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun buttonPadding(): PaddingValues = PaddingValues(
    horizontal = Spacing.xl,
    vertical = Spacing.md,
)

private fun Modifier.pressSemantics(pressed: Boolean): Modifier = semantics {
    stateDescription = if (pressed) "pressed" else "idle"
}

/** Галерея и формы растягивают кнопку на ширину родителя. */
fun Modifier.gtButtonWidth(): Modifier = fillMaxWidth()
