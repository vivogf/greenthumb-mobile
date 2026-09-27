package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Поле формы. Фон — input (`surfaceContainerHighest`), рамка — border,
 * радиус [Radii.md], как `inputStyle` в `login.tsx`.
 * Ошибка: рамка error и подпись под полем (`add-plant.tsx` errorStyle).
 */
@Composable
fun GtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    /** Клавиатура поля (число у частот — RN keyboardType numeric). */
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Действия клавиатуры (RN onSubmitEditing — Done у поля имени шапки). */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val hasError = !error.isNullOrEmpty()
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            isError = hasError,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            placeholder = if (placeholder.isEmpty()) {
                null
            } else {
                { Text(text = placeholder) }
            },
            supportingText = if (!hasError) {
                null
            } else {
                { Text(text = error) }
            },
            shape = RoundedCornerShape(Radii.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.primary,
                unfocusedBorderColor = scheme.outline,
                disabledBorderColor = scheme.outlineVariant,
                errorBorderColor = scheme.error,
                focusedContainerColor = scheme.surfaceContainerHighest,
                unfocusedContainerColor = scheme.surfaceContainerHighest,
                disabledContainerColor = scheme.surfaceVariant,
                errorContainerColor = scheme.surfaceContainerHighest,
                focusedTextColor = scheme.onSurface,
                unfocusedTextColor = scheme.onSurface,
                disabledTextColor = scheme.onSurfaceVariant,
                errorTextColor = scheme.onSurface,
                cursorColor = scheme.primary,
                errorCursorColor = scheme.error,
                focusedPlaceholderColor = scheme.onSurfaceVariant,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
                errorSupportingTextColor = scheme.error,
                focusedSupportingTextColor = scheme.onSurfaceVariant,
                unfocusedSupportingTextColor = scheme.onSurfaceVariant,
            ),
        )
    }
}
