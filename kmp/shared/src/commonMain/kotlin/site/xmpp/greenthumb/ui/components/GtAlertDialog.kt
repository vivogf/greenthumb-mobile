package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Замена `components/AlertDialog.tsx`.
 *
 * Кнопка: сначала закрыть, потом callback (навигация из onClick не остаётся
 * под модалкой). Подложка и back: если есть кнопка Cancel — её callback,
 * затем закрытие. Пустой список кнопок — одна OK, как в RN.
 */
enum class GtAlertButtonStyle { Default, Cancel, Destructive }

data class GtAlertButton(
    val text: String,
    val style: GtAlertButtonStyle = GtAlertButtonStyle.Default,
    val onClick: () -> Unit = {},
)

data class GtAlertRequest(
    val title: String,
    val message: String?,
    val buttons: List<GtAlertButton>,
)

class GtAlertController {
    var request by mutableStateOf<GtAlertRequest?>(null)
        private set

    fun show(
        title: String,
        message: String? = null,
        buttons: List<GtAlertButton> = listOf(GtAlertButton(text = "OK")),
    ) {
        request = GtAlertRequest(title, message, buttons.ifEmpty { listOf(GtAlertButton(text = "OK")) })
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberGtAlertController(): GtAlertController = remember { GtAlertController() }

@Composable
fun GtAlertDialogHost(controller: GtAlertController) {
    val request = controller.request ?: return
    GtAlertDialog(
        title = request.title,
        message = request.message,
        buttons = request.buttons,
        onDismissRequest = controller::dismiss,
    )
}

@Composable
fun GtAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    message: String? = null,
    buttons: List<GtAlertButton> = listOf(GtAlertButton(text = "OK")),
) {
    val resolved = buttons.ifEmpty { listOf(GtAlertButton(text = "OK")) }
    val scheme = MaterialTheme.colorScheme
    GtModal(
        onDismissRequest = {
            resolved.firstOrNull { it.style == GtAlertButtonStyle.Cancel }?.onClick?.invoke()
            onDismissRequest()
        },
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.xl,
                bottom = Spacing.xs,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
        )
        if (!message.isNullOrEmpty()) {
            Text(
                text = message,
                modifier = Modifier
                    .padding(horizontal = Spacing.xl)
                    .padding(bottom = Spacing.xl),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(Spacing.sm))
        }
        HorizontalDivider(color = scheme.outline)
        Row(modifier = Modifier.fillMaxWidth().height(Spacing.xxl + Spacing.xl)) {
            resolved.forEachIndexed { index, button ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .width(Spacing.xxs / 4)
                            .fillMaxHeight()
                            .background(scheme.outline),
                    )
                }
                val color = when (button.style) {
                    GtAlertButtonStyle.Destructive -> scheme.error
                    GtAlertButtonStyle.Cancel -> scheme.onSurfaceVariant
                    GtAlertButtonStyle.Default -> scheme.primary
                }
                TextButton(
                    onClick = {
                        onDismissRequest()
                        button.onClick()
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    Text(
                        text = button.text,
                        color = color,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
