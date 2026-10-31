package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.Sizes

/**
 * VAL-CAM12-006 and the feedback part of VAL-CAM12-001 on screen with a fake camera: a two-finger pinch on the
 * viewfinder zooms (never switching the mode), the «N.N×» label is a named 48 dp reset target, a double tap on the
 * preview resets too, there is no slider, and a shutter tap shows the shot feedback at once.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraZoomScreenTest {

    private class Harness {
        val ratios = mutableListOf<Float>()
        var gate: CompletableDeferred<Unit>? = null

        val camera: @Composable (CameraShutter, (CameraStatus) -> Unit, Modifier) -> Unit = { shutter, onStatus, modifier ->
            LaunchedEffect(shutter) {
                shutter.attach { _ ->
                    ratios += shutter.zoomRatio
                    gate?.await()
                    PickResult.Picked(paintedJpeg(300, 400))
                }
                onStatus(CameraStatus.Ready(hasFlash = false, maxZoom = 4f))
            }
            Box(modifier.background(Color.DarkGray))
        }
    }

    private fun ComposeUiTest.show(h: Harness, locale: String, fontScale: Float = 1f) {
        setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    SmartCameraScreen(
                        initialMode = SmartCameraMode.Plant,
                        identifier = null,
                        diagnoser = null,
                        onClose = {},
                        onAddToMine = {},
                        pick = { PickResult.Cancelled },
                        deleteCapture = {},
                        camera = h.camera,
                    )
                }
            }
            }
        }
        waitForIdle()
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
    }

    private fun ComposeUiTest.node(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true)

    private fun ComposeUiTest.exists(tag: String) =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.text(tag: String): String =
        onNode(hasTestTag(tag)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun ComposeUiTest.description(tag: String): String =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun ComposeUiTest.selected(tag: String): Boolean =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    /** Two fingers move apart horizontally across the preview: the gesture a mode swipe must not catch. */
    private fun ComposeUiTest.pinchOut() {
        node("smartcam_preview").performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.05f, 0f),
                end0 = center - Offset(width * 0.4f, 0f),
                start1 = center + Offset(width * 0.05f, 0f),
                end1 = center + Offset(width * 0.4f, 0f),
            )
        }
        waitForIdle()
    }

    private fun zoomLabelAndResets(locale: String, resetWord: String) = runDesktopComposeUiTest {
        val h = Harness()
        show(h, locale)
        assertFalse(exists("smartcam_zoomLabel"), "no label at 1×")
        pinchOut()
        assertTrue(exists("smartcam_zoomLabel"), "a pinch zooms in")
        val label = text("smartcam_zoomLabel")
        assertTrue(Regex("""[1-4]\.\d×""").matches(label), "label «N.N×»: $label")
        assertTrue(label != "1.0×")
        assertTrue(selected("smartcam_mode_plant"), "a pinch does not switch the mode")
        assertTrue(description("smartcam_zoomLabel").contains(resetWord, ignoreCase = true), description("smartcam_zoomLabel"))
        assertTrue(description("smartcam_zoomLabel").contains(label), "the name says the ratio")
        val minPx = with(density) { Sizes.touch.toPx() } - 0.5f
        val size = node("smartcam_zoomLabel").fetchSemanticsNode().size
        assertTrue(size.width >= minPx && size.height >= minPx, "reset target is at least 48 dp: $size")

        node("smartcam_zoomLabel").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertFalse(exists("smartcam_zoomLabel"), "tapping the label returns to 1×")

        pinchOut()
        assertTrue(exists("smartcam_zoomLabel"))
        node("smartcam_preview").performTouchInput { doubleClick(center + Offset(0f, height * 0.3f)) }
        waitForIdle()
        assertFalse(exists("smartcam_zoomLabel"), "a double tap on the preview returns to 1×")
        assertTrue(selected("smartcam_mode_plant"))
    }

    @Test
    fun en_pinchShowsTheLabelAndBothResetsWork() = zoomLabelAndResets("en", "1×")

    @Test
    fun ru_pinchShowsTheLabelAndBothResetsWork() = zoomLabelAndResets("ru", "1×")

    /** Owner's phone runs font scale 1.15–1.3: «4.0×» must stay whole, not cut by a fixed 48 dp circle. */
    @Test
    fun theLabelIsWholeAtLargeFont() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "ru", fontScale = 1.3f)
        pinchOut()
        assertEquals("4.0×", text("smartcam_zoomLabel"))
        val layouts = mutableListOf<TextLayoutResult>()
        node("smartcam_zoomLabelText").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val textWidth = layouts.single().multiParagraph.intrinsics.maxIntrinsicWidth
        val target = node("smartcam_zoomLabel").fetchSemanticsNode().boundsInRoot
        val label = node("smartcam_zoomLabelText").fetchSemanticsNode().boundsInRoot
        assertTrue(
            label.left >= target.left - 0.5f && label.right <= target.right + 0.5f,
            "the label pill lies inside its reset target: $label in $target",
        )
        assertTrue(textWidth <= label.width + 0.5f, "the label text is clipped: $textWidth > ${label.width}")
        val minPx = with(density) { Sizes.touch.toPx() } - 0.5f
        assertTrue(target.width >= minPx && target.height >= minPx, "reset target is at least 48 dp: $target")
    }

    @Test
    fun theZoomedRatioReachesTheShot() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "en")
        pinchOut()
        node("smartcam_shutter").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(1, h.ratios.size)
        assertTrue(h.ratios.single() > 1f, "the shot is taken at the pinched ratio: ${h.ratios}")
    }

    @Test
    fun theShutterTapShowsFeedbackBeforeThePhotoArrives() = runDesktopComposeUiTest {
        val h = Harness()
        h.gate = CompletableDeferred()
        show(h, "en")
        assertFalse(exists("smartcam_shotFeedback"))
        node("smartcam_shutter").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(exists("smartcam_shotFeedback"), "the preview dims at once")
        assertTrue(exists("smartcam_shutterBusy"), "the shutter shows that it works")
        h.gate!!.complete(Unit)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
    }

    @Test
    fun thereIsNoZoomSlider() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "en")
        pinchOut()
        val sliders = onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(sliders.isEmpty(), "no slider node on the viewfinder")
        val dir = File("src/commonMain/kotlin/site/xmpp/greenthumb/ui/screens/smartcam")
        for (file in dir.listFiles { f -> f.extension == "kt" }.orEmpty()) {
            assertFalse(file.readText().contains("Slider("), "${file.name} has a slider")
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
