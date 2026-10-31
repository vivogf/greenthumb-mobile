package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.withExifOrientation
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * M11c item 4 on screen (VAL-CAM12-003/005) at 384 dp, font 1.15 and 1.3, ru and en: the live preview is a portrait
 * 3:4 box between the top bar and the mode/shutter rows (not a full-screen crop), the camera fills exactly that box,
 * the aiming frame is an overlay inside it, and the after-shot photo is shown in its own aspect: 3:4 for a shot,
 * the picked aspect for a gallery photo.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraFrameScreenTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", out)
        return out.toByteArray()
    }

    private val shot = withExifOrientation(jpeg(1600, 1200), 6)

    private val camera: @Composable (CameraShutter, (CameraStatus) -> Unit, Modifier) -> Unit = { shutter, onStatus, modifier ->
        LaunchedEffect(shutter) {
            shutter.attach { PickResult.Picked(shot) }
            onStatus(CameraStatus.Ready(hasFlash = true))
        }
        Box(modifier.background(Color.DarkGray).testTag("fake_camera"))
    }

    private fun ComposeUiTest.show(locale: String, fontScale: Float, mode: SmartCameraMode, picked: ByteArray) {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppEnvironment(customAppLocale = locale) {
                    GreenThumbTheme(darkTheme = true) {
                        SmartCameraScreen(
                            initialMode = mode,
                            identifier = { _, _ -> emptyList() },
                            diagnoser = { _, _, _ -> DiagnosisResponse(listOf(DiagnosisCauseDto("A", "high", "B", "C")), "S") },
                            onClose = {},
                            onAddToMine = {},
                            pick = { PickResult.Picked(picked) },
                            deleteCapture = {},
                            camera = camera,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.bounds(tag: String): Rect =
        onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.tap(tag: String) {
        waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT)
        onNode(hasTestTag(tag), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun assertRatio(what: String, rect: Rect, expected: Float) {
        val ratio = rect.width / rect.height
        assertTrue(abs(ratio - expected) <= expected * 0.02f, "$what: ${rect.width}×${rect.height} ratio $ratio, want $expected")
    }

    private fun Rect.containsRect(other: Rect) =
        other.left >= left - 1f && other.top >= top - 1f && other.right <= right + 1f && other.bottom <= bottom + 1f

    private fun frameCase(locale: String, fontScale: Float) {
        for (mode in SmartCameraMode.entries) {
            runDesktopComposeUiTest(width = 384, height = 800) {
                show(locale, fontScale, mode, picked = jpeg(1600, 900))
                waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
                val where = "[$locale $fontScale $mode]"
                val root = bounds("smartcam_viewfinder")
                val preview = bounds("smartcam_preview")
                assertRatio("$where preview box", preview, 0.75f)
                assertTrue(preview.height < root.height - 1f, "$where the preview is not the full screen: $preview in $root")
                val cam = bounds("fake_camera")
                assertTrue(abs(cam.width - preview.width) < 1f && abs(cam.height - preview.height) < 1f, "$where camera fills the 3:4 box: $cam vs $preview")
                assertTrue(preview.containsRect(bounds("smartcam_frame")), "$where the aiming frame is an overlay inside the preview")
                for (top in listOf("smartcam_close", "smartcam_flash")) {
                    assertTrue(bounds(top).bottom <= preview.top + 1f, "$where $top is in the top bar: ${bounds(top)} / $preview")
                }
                for (bottom in listOf("smartcam_modes", "smartcam_shutter", "smartcam_gallery")) {
                    val b = bounds(bottom)
                    assertTrue(b.top >= preview.bottom - 1f && b.bottom <= root.bottom + 1f, "$where $bottom is under the preview: $b / $preview")
                }

                tap("smartcam_shutter")
                waitUntilAtLeastOneExists(hasTestTag("smartcam_photo"), TIMEOUT)
                assertRatio("$where after-shot photo", bounds("smartcam_photo"), 0.75f)

                tap("smartcam_reshoot")
                tap("smartcam_gallery")
                waitUntilAtLeastOneExists(hasTestTag("smartcam_photo"), TIMEOUT)
                assertRatio("$where gallery photo keeps its aspect", bounds("smartcam_photo"), 16f / 9f)
            }
        }
    }

    @Test
    fun en_font115() = frameCase("en", 1.15f)

    @Test
    fun en_font130() = frameCase("en", 1.3f)

    @Test
    fun ru_font115() = frameCase("ru", 1.15f)

    @Test
    fun ru_font130() = frameCase("ru", 1.3f)

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
