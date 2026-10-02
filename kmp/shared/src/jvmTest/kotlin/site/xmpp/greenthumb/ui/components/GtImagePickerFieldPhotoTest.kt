package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Выбранное фото рисуется внутри квадрата пикера, а не только полоска камеры:
 * левая половина тестового кадра красная, правая синяя.
 */
@OptIn(ExperimentalTestApi::class)
class GtImagePickerFieldPhotoTest {

    @Test
    fun chosen_photo_is_drawn_inside_the_picker_box() = runDesktopComposeUiTest {
        val uri = photoDataUri(paintedJpeg(200, 200))
        setContent {
            GreenThumbTheme(darkTheme = true) {
                GtImagePickerField(
                    hasImage = true,
                    label = "Photo box",
                    sourceTitle = "Source",
                    cameraLabel = "Camera",
                    galleryLabel = "Gallery",
                    removeLabel = "Remove",
                    cancelLabel = "Cancel",
                    photoUrl = uri,
                    onPickRequested = {},
                    onRemove = {},
                )
            }
        }

        val box = onNodeWithContentDescription("Photo box")
        waitUntil(timeoutMillis = 10_000) {
            val pixels = box.captureToImage().toPixelMap()
            val y = pixels.height / 3
            val left = pixels[pixels.width / 4, y]
            val right = pixels[pixels.width * 3 / 4, y]
            left.red > 0.6f && left.blue < 0.4f && right.blue > 0.6f && right.red < 0.4f
        }

        val pixels = box.captureToImage().toPixelMap()
        val y = pixels.height / 3
        assertTrue(pixels[pixels.width / 4, y].red > 0.6f, "left half must show the red half of the photo")
        assertTrue(pixels[pixels.width * 3 / 4, y].blue > 0.6f, "right half must show the blue half of the photo")
    }
}
