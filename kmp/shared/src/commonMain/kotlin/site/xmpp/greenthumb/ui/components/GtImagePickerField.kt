package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Оболочка `components/ImagePickerField.tsx`.
 * Сам пикер (камера, галерея, кроп, resize) — M8. Здесь квадрат и лист
 * источников: колбэки, без платформенного запуска.
 */
enum class GtImageSource { Camera, Gallery }

@Composable
fun GtImagePickerField(
    hasImage: Boolean,
    label: String,
    sourceTitle: String,
    cameraLabel: String,
    galleryLabel: String,
    removeLabel: String,
    cancelLabel: String,
    onPickRequested: (GtImageSource) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.lg)
    val borderColor = if (hasImage) scheme.primary else scheme.outline
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl * 4 + Spacing.lg)
                .background(scheme.surfaceVariant, shape)
                .borderHairline(borderColor, shape)
                .clickable(enabled = enabled, onClick = { if (enabled) open = true }, role = Role.Button)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (hasImage) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(scheme.scrim)
                        .padding(vertical = Spacing.xxs),
                    contentAlignment = Alignment.Center,
                ) {
                    GtCameraMark(
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(Spacing.md),
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GtCameraMark(
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.xxl),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (open) {
        GtModal(onDismissRequest = { open = false }) {
            Text(
                text = sourceTitle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xl, bottom = Spacing.xs),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            SourceRow(cameraLabel, scheme.primary) {
                open = false
                onPickRequested(GtImageSource.Camera)
            }
            SourceRow(galleryLabel, scheme.primary) {
                open = false
                onPickRequested(GtImageSource.Gallery)
            }
            if (hasImage) {
                SourceRow(removeLabel, scheme.error) {
                    open = false
                    onRemove()
                }
            }
            SourceRow(cancelLabel, scheme.onSurfaceVariant) {
                open = false
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
    }
}
