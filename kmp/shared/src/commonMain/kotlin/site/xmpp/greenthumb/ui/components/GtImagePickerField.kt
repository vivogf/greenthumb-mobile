package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Оболочка `components/ImagePickerField.tsx`.
 * Квадрат и лист источников (камера/галерея/удалить); запуск платформенного
 * пикера и кропа — за вызывающим кодом через [rememberPhotoPicker] +
 * [PhotoPickerHost] (Stage 8 п.1).
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
    photoUrl: String = "",
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.lg)
    val dashColor = scheme.onSurfaceVariant.copy(alpha = 0.7f)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl * 4 + Spacing.lg)
                .background(scheme.surfaceVariant, shape)
                .then(
                    if (hasImage) {
                        Modifier.borderHairline(scheme.primary, shape)
                    } else {
                        Modifier.dashedBorder(dashColor, Radii.lg)
                    },
                )
                .clickable(enabled = enabled, onClick = { if (enabled) open = true }, role = Role.Button)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            if (hasImage) {
                if (photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(shape),
                        contentScale = ContentScale.Crop,
                    )
                }
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    GtCameraMark(
                        tint = scheme.primary,
                        modifier = Modifier.size(Spacing.xxl * 2),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
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

private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val strokeWidth = (Spacing.xxs / 2).toPx()
    val dash = Spacing.xs.toPx()
    val inset = strokeWidth / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
    )
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
