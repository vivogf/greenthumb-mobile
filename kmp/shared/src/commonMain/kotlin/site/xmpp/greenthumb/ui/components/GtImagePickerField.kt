package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
    /** A fixed height makes the box fill the width (Add Plant); null keeps the small square. */
    height: Dp? = null,
    /** Prefix of the source sheet row tags (`<prefix>_camera`, `_gallery`, `_remove`, `_cancel`). */
    sheetTagPrefix: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.lg)
    val dashColor = scheme.onSurfaceVariant.copy(alpha = 0.7f)
    val boxSize = Spacing.xxl * 4 + Spacing.lg
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                // Without a photo the box widens to keep «Добавить фото» on one line (large font scale).
                .then(
                    when {
                        height != null -> Modifier.fillMaxWidth().height(height)
                        hasImage -> Modifier.size(boxSize)
                        else -> Modifier.height(boxSize).widthIn(min = boxSize)
                    },
                )
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
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    GtCameraMark(
                        tint = scheme.secondary,
                        modifier = Modifier.size(Spacing.xxl * 2),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    if (open) {
        GtPhotoSourceSheet(
            title = sourceTitle,
            cameraLabel = cameraLabel,
            galleryLabel = galleryLabel,
            cancelLabel = cancelLabel,
            onPick = { source ->
                open = false
                onPickRequested(source)
            },
            onDismiss = { open = false },
            removeLabel = removeLabel.takeIf { hasImage },
            onRemove = {
                open = false
                onRemove()
            },
            tagPrefix = sheetTagPrefix,
        )
    }
}

/**
 * Camera / gallery (/ remove) / cancel sheet of a photo, built from [GtSheetRow]s.
 * [removeLabel] null hides the remove row. With [tagPrefix] the rows get the tags
 * `<prefix>_camera`, `_gallery`, `_remove` and `_cancel`.
 */
@Composable
fun GtPhotoSourceSheet(
    title: String,
    cameraLabel: String,
    galleryLabel: String,
    cancelLabel: String,
    onPick: (GtImageSource) -> Unit,
    onDismiss: () -> Unit,
    removeLabel: String? = null,
    onRemove: () -> Unit = {},
    tagPrefix: String? = null,
) {
    fun tag(part: String): String = if (tagPrefix == null) GtSheetTags.ROW else tagPrefix + part
    GtModal(onDismissRequest = onDismiss) {
        GtSheetTitle(title)
        GtSheetRow(
            label = cameraLabel,
            icon = { GtCameraMark(it) },
            onClick = { onPick(GtImageSource.Camera) },
            tag = tag("_camera"),
        )
        GtSheetRow(
            label = galleryLabel,
            icon = { GtGalleryMark(it) },
            onClick = { onPick(GtImageSource.Gallery) },
            tag = tag("_gallery"),
        )
        if (removeLabel != null) {
            GtSheetRow(
                label = removeLabel,
                icon = { GtTrashMark(it) },
                role = GtSheetRowRole.Destructive,
                onClick = onRemove,
                tag = tag("_remove"),
            )
        }
        GtSheetRow(
            label = cancelLabel,
            icon = null,
            role = GtSheetRowRole.Cancel,
            onClick = onDismiss,
            tag = tag("_cancel"),
        )
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
