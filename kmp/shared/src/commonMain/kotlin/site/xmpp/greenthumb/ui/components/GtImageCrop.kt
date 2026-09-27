package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import site.xmpp.greenthumb.core.platform.CropRect
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_done
import site.xmpp.greenthumb.ui.res.photoCrop_hint
import site.xmpp.greenthumb.ui.res.photoCrop_title
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import kotlin.math.max
import kotlin.math.min

/**
 * Экран квадратного кропа (Stage 8 п.1): у Android-пикера кропа нет
 * (RN `allowsEditing 1:1` давал его бесплатно), поэтому после выбора
 * показывается свой Compose-экран: квадратный вьюпорт, картинка
 * (ContentScale.Crop = cover), pinch-zoom 1..5× и пан со clamp'ом.
 *
 * Подтверждение отдаёт нормализованный [CropRect] пересечения вьюпорта с
 * отображённым прямоугольником картинки (см. [CropRect.fromDisplay]);
 * отмена — молчит (RN: закрытый нативный кроп = canceled).
 * Токены темы обязательны: файл под `ui/components` K5 не сканирует, но
 * литералы оформления здесь не используются.
 */
@Composable
internal fun GtImageCropScreen(
    bytes: ByteArray,
    onCancel: () -> Unit,
    onConfirm: (CropRect) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dataUri = remember(bytes) { photoDataUri(bytes) }

    // Размер картинки (px) из пейнтера Coil; сторона вьюпорта (px) из layout.
    var intrinsic by remember(bytes) { mutableStateOf<Size?>(null) }
    var viewportSidePx by remember(bytes) { mutableStateOf(0f) }
    var userZoom by remember(bytes) { mutableStateOf(1f) }
    var offset by remember(bytes) { mutableStateOf(Offset.Zero) }

    val titleText = stringResource(Res.string.photoCrop_title)
    val hintText = stringResource(Res.string.photoCrop_hint)
    val cancelText = stringResource(Res.string.common_cancel)
    val doneText = stringResource(Res.string.common_done)

    fun confirm() {
        val intr = intrinsic ?: return
        val side = viewportSidePx
        if (side <= 0f) return
        val cover = side / min(intr.width, intr.height)
        val imgW = intr.width * cover * userZoom
        val imgH = intr.height * cover * userZoom
        val displayed = Rect(
            left = (side - imgW) / 2f + offset.x,
            top = (side - imgH) / 2f + offset.y,
            right = (side + imgW) / 2f + offset.x,
            bottom = (side + imgH) / 2f + offset.y,
        )
        onConfirm(CropRect.fromDisplay(displayed, Rect(0f, 0f, side, side)))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(text = cancelText, color = scheme.onSurfaceVariant)
                }
                Text(
                    text = titleText,
                    modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { confirm() }, enabled = intrinsic != null) {
                    Text(text = doneText, color = scheme.primary)
                }
            }

            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val sideDp = minOf(maxWidth, maxHeight) - Spacing.xl * 2
                Box(
                    modifier = Modifier
                        .size(sideDp)
                        .clip(RoundedCornerShape(Radii.md))
                        .background(scheme.surfaceVariant)
                        .onGloballyPositioned { coords ->
                            viewportSidePx = min(coords.size.width, coords.size.height).toFloat()
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val intr = intrinsic
                                val side = viewportSidePx
                                if (intr == null || side <= 0f) return@detectTransformGestures
                                val cover = side / min(intr.width, intr.height)
                                val newZoom = (userZoom * zoom).coerceIn(1f, 5f)
                                val imgW = intr.width * cover * newZoom
                                val imgH = intr.height * cover * newZoom
                                val maxX = max(0f, (imgW - side) / 2f)
                                val maxY = max(0f, (imgH - side) / 2f)
                                val next = offset + pan
                                offset = Offset(
                                    next.x.coerceIn(-maxX, maxX),
                                    next.y.coerceIn(-maxY, maxY),
                                )
                                userZoom = newZoom
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = dataUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { state -> intrinsic = state.painter.intrinsicSize },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = userZoom
                                scaleY = userZoom
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
            }

            Text(
                text = hintText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

